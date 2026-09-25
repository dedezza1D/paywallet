# PayWallet

Digital wallet payments platform built with **Java 21 and Spring Boot 3.5**, using polyglot persistence:
each kind of data lives in the store that best fits it.

```
                         ┌─────────────────── API (Spring Boot) ───────────────────┐
                         │  auth  user  wallet  ledger  feed  kyc  external        │
                         └────┬──────────┬──────────┬────────┬───────┬─────────────┘
                              ▼          ▼          ▼        ▼       ▼
                      PostgreSQL      Redis     PostgreSQL MongoDB  S3 (LocalStack in dev)
                  users, accounts,  idempotency, (same)     social   private KYC bucket,
                  ledger, postings, daily limits,           feed     presigned URLs
                  refresh tokens,   balance cache,
                  KYC metadata      login throttling
```

## Business rules

- Two user types: **individuals** (CPF) send and receive money; **merchants** (CNPJ) only receive.
- A transfer requires enough funds, stays within the daily limit and is checked by an external authorizer.
- Money movements are atomic: any failure rolls back both debit and credit.
- After commit, the payee is notified asynchronously and the activity is written to the social feed.

## Authentication

- `POST /auth/login` returns a short-lived **access token** (JWT, RS256, 15 min) and an opaque **refresh token**.
- Access tokens are signed with an asymmetric key. Other services can validate them with the public key
  at `GET /.well-known/jwks.json`, without sharing a secret.
- Refresh tokens are stored only as SHA-256 hashes and **rotate** on every use. Presenting an already-rotated
  token is treated as theft and revokes the whole session family.
- After 5 consecutive failed logins for an email, further attempts get **429** for 15 minutes. Unknown emails
  still run BCrypt, so response time does not reveal whether an account exists.
- Authorization: users can only access `/users/{their id}/**`. The payer of a transfer is always the token owner.
  Listing users, the ledger audit and deposits into other users' wallets require the `ADMIN` role, which can
  only be granted directly in the database.

Configure `JWT_PRIVATE_KEY` (RSA PKCS#8 PEM) outside development. Without it an ephemeral key is generated at
startup, so tokens die on restart and do not work across instances.

## Data layer

### Ledger (PostgreSQL): the source of truth for money

Double-entry bookkeeping. Balances are never overwritten: every movement is a `ledger_transaction` with two or
more `postings` that sum to zero.

| Table | Role |
|---|---|
| `accounts` | Ledger account (user wallet or system account). `balance` is a snapshot updated in the same transaction as the postings |
| `ledger_transactions` | Groups the legs of a movement. `idempotency_key` is unique |
| `postings` | A debit or credit on one account, with `balance_after`. Immutable |

Guarantees enforced **by the database itself**:

- Amounts in **cents (`BIGINT`)**, never floating point.
- `postings` and `ledger_transactions` reject `UPDATE` and `DELETE`; corrections are new reversing transactions.
- A deferred constraint trigger rejects, at `COMMIT`, any transaction whose debits and credits differ.
- `CHECK (allow_negative OR balance >= 0)`: user wallets never go negative.

System accounts: `SYSTEM_CASH_IN` offsets money entering from outside and goes negative, and `SYSTEM_FEES`
holds revenue. **All balances always sum to zero**, which `GET /ledger/reconciliation` verifies along with each
snapshot against its postings.

Accounts involved in a movement are locked with `SELECT ... FOR UPDATE` in a fixed order, so crossing
transfers cannot deadlock.

### Redis: hot data

| Use | How |
|---|---|
| Idempotency | Clients send an `Idempotency-Key` per payment intent. `SET NX` (30 s) blocks concurrent duplicates with 409. The unique constraint in Postgres is the final guarantee: repeating a key returns the original result (200 + `Idempotent-Replayed: true`) without moving money again, and reusing it with a different payload returns 422 |
| Daily limit | Atomic Lua reservation per user and day (`America/Sao_Paulo`), released if the transfer fails |
| Balance cache | Cache-aside, 30 s TTL, evicted after every movement. Display only; the funds check always runs in Postgres under lock |
| Login throttling | Failed-attempt counter per email with expiry |

If Redis is down the balance cache falls back to the database, while transfers and logins return **503**.

### MongoDB: social feed

Feed entries hold the message, visibility (`PUBLIC`/`PRIVATE`) and participants, with a unique index on
`transactionId` so reprocessing an event never duplicates it. The public feed never exposes amounts.

### Object storage (S3): KYC documents

- Private bucket. Metadata (object key, SHA-256, review status) lives in Postgres and the file in the bucket.
- File type is validated by **content** (JPEG/PNG/PDF magic bytes), not by the declared `Content-Type`.
- Object keys are generated server-side (`kyc/{userId}/{uuid}.ext`) and contain nothing supplied by the client.
- Files are read only through **presigned URLs** that expire after 5 minutes.
- On AWS, set `S3_SSE=aws:kms` and `S3_KMS_KEY_ID` for KMS encryption.

## Transfer flow

1. The payer is taken from the access token; the payload and `Idempotency-Key` are validated. A key that was
   already processed returns the original result.
2. Idempotency lock in Redis.
3. Business rules (merchants cannot send; unlocked funds pre-check).
4. Daily limit reservation (Redis, atomic).
5. External authorizer, called outside any database transaction so no lock waits on HTTP.
6. Ledger: lock both accounts, debit and credit, write postings, all in one transaction.
7. After commit: evict balance cache and publish an event for the feed (MongoDB) and notification.
8. Any failure in steps 5 and 6 releases the limit reservation.

## Running

```bash
docker compose up --build
```

Swagger UI: http://localhost:8080/swagger-ui.html (use **Authorize** with the access token).
Local S3 (LocalStack): http://localhost:4566.

Infrastructure only, with the application running locally (JDK 21 and Maven):

```bash
docker compose up -d postgres redis mongo s3
mvn spring-boot:run
```

Integration tests use **Testcontainers** (Postgres, Redis, MongoDB and S3 via LocalStack), so Docker must be running:

```bash
mvn test
```

## API

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/users` | public | Sign up (also opens the ledger wallet) |
| POST | `/auth/login` | public | Email and password to access and refresh tokens |
| POST | `/auth/refresh` | public | Rotates the refresh token and issues a new access token |
| POST | `/auth/logout` | public | Revokes the refresh token's session |
| GET | `/.well-known/jwks.json` | public | Public key to validate access tokens |
| GET | `/feed` | public | Public feed, without amounts |
| GET | `/users/{id}` | owner | User profile |
| POST | `/transfer` | user | P2P transfer from the token owner. Requires `Idempotency-Key` |
| GET | `/users/{id}/balance` | owner | Balance (cached) |
| GET | `/users/{id}/statement` | owner | Paged statement from the ledger |
| GET | `/users/{id}/limits` | owner | Daily limit used and remaining |
| GET | `/users/{id}/feed` | owner | User feed, with amounts |
| POST | `/users/{id}/kyc-documents` | owner | Upload a document (multipart: `type`, `file`) |
| GET | `/users/{id}/kyc-documents` | owner | List documents |
| GET | `/users/{id}/kyc-documents/{doc}/download-url` | owner | Presigned URL (5 min) |
| POST | `/users/{id}/deposit` | admin | Incoming money (simulates Pix-in). Requires `Idempotency-Key`. Owner allowed when `ALLOW_SELF_DEPOSIT=true` (dev) |
| GET | `/users` | admin | List users |
| GET | `/ledger/reconciliation` | admin | Ledger audit |

### Example

```bash
curl -X POST localhost:8080/users -H "Content-Type: application/json" -d '{
  "fullName": "Mary Smith", "document": "12345678901", "email": "mary@mail.com",
  "password": "password1234", "type": "COMMON" }'

TOKEN=$(curl -s -X POST localhost:8080/auth/login -H "Content-Type: application/json" \
  -d '{ "email": "mary@mail.com", "password": "password1234" }' | jq -r .accessToken)

curl -X POST localhost:8080/users/1/deposit -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "Idempotency-Key: 6f1c2a10-deposit-1" -d '{ "value": 100.00 }'

curl -X POST localhost:8080/transfer -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: 0b7e9f52-friday-pizza" -d '{
  "value": 25.50, "payee": 2, "message": "pizza", "visibility": "PUBLIC" }'
```

### Status codes

| Status | When |
|---|---|
| 201 | Transfer or sign-up completed |
| 200 + `Idempotent-Replayed` | Same `Idempotency-Key` repeated: original result, no money moved |
| 400 | Invalid payload or missing/invalid `Idempotency-Key` |
| 401 | Missing, invalid or expired token; wrong credentials; invalid refresh token |
| 403 | Accessing another user's resources, missing role, or transfer denied by the authorizer |
| 404 | User or document not found |
| 409 | Same `Idempotency-Key` still in progress, or duplicate record |
| 422 | Business rule violated (funds, daily limit, merchant sending, key reused with another payload...) |
| 429 | Too many failed logins (see `Retry-After`) |
| 503 | Authorizer, Redis or storage unavailable |

## Troubleshooting

- **`Could not find a valid Docker environment` / `Status 400`** in tests: Docker Engine 29+ requires API 1.44 or
  later. Already handled by `src/test/resources/docker-java.properties`.
- **`Unable to establish loopback connection`** on Windows: the JDK creates a Unix domain socket in the temp
  folder, which fails in some restricted environments. Point it elsewhere with
  `mvn test "-DargLine=-Djdk.net.unixdomain.tmpdir=C:\short\path"` (and the same `-D` when running the app).
- **`minio/minio` image not found**: MinIO stopped publishing images to Docker Hub, which is why local S3 uses LocalStack.
- **Flyway checksum mismatch** after pulling changes to existing migrations: this project is pre-release and
  migrations may still be edited. Reset local data with `docker compose down -v`.
- LocalStack validates presigned URL signatures but does not block anonymous reads of the bucket. On real S3,
  Block Public Access is on by default and must stay on.

## Known limitations and next steps

- **Outbox**: the post-commit event is published in memory; if the app crashes between commit and publish, the
  feed entry and notification are lost (the money is not). Outbox plus Kafka fixes this.
- Access tokens cannot be revoked before they expire (15 min); refresh tokens can.
- Login throttling is per email only, so an attacker can temporarily lock out a victim. Add per-IP limits at the gateway.
- The cached balance may be up to 30 s stale in a rare read/write race; it never affects decisions.
- The daily limit lives only in Redis; if Redis loses data, the day's counter resets.
- CPF/CNPJ check digits are not validated.
