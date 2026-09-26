# PayWallet

Digital wallet payments platform built with **Java 21 and Spring Boot 3.5**, using polyglot persistence:
each kind of data lives in the store that best fits it.

```
  POST /transfer
       │
       ▼
  ┌──────────── one PostgreSQL transaction ────────────┐        ┌───────── Kafka ─────────┐
  │ ledger postings  +  outbox_events row              │─relay─▶│ transfers.completed     │
  └────────────────────────────────────────────────────┘        └───┬─────────────────┬───┘
                                                                     ▼                 ▼
                                                           group "feed"       group "notifications"
                                                           → MongoDB feed     → notification service
                                                                     └── failures ──▶ transfers.completed-dlt

  Redis: idempotency locks, daily limits, balance cache, login throttling
  S3 (LocalStack in dev): private KYC bucket served through presigned URLs
```

## Business rules

- Two user types: **individuals** (CPF) send and receive money; **merchants** (CNPJ) only receive.
- A transfer requires enough funds, stays within the daily limit and is checked by an external authorizer.
- Money movements are atomic: any failure rolls back both debit and credit.
- Every committed transfer produces exactly one event, from which the payee is notified and the social feed
  is updated asynchronously.

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

System accounts: `SYSTEM_CASH_IN` (manual cash-in), `SYSTEM_PIX_SETTLEMENT` (Pix exchanged with other
institutions), `SYSTEM_BILL_SETTLEMENT` (bill payments awaiting settlement) and `SYSTEM_FEES` (revenue). The
settlement and cash-in accounts may go negative because they offset money held for customers.
**All balances always sum to zero**, which `GET /ledger/reconciliation` verifies along with each snapshot
against its postings.

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

### Events: transactional outbox and Kafka

- The transfer writes its `TransferCompleted` event to `outbox_events` **in the same transaction** as the ledger
  postings, so money never moves without its event and no event exists for a rolled-back transfer.
- A relay polls pending rows with `FOR UPDATE SKIP LOCKED` (safe with several instances), publishes them to
  `paywallet.transfers.completed` in creation order and marks them published. If Kafka is down, rows wait and
  are retried; `attempts` and `last_error` show why. Published rows are deleted after 7 days.
- Messages are keyed by payer, so each user's events stay ordered within a partition.
- Delivery is **at least once**, so every consumer is idempotent: the feed relies on its unique index and
  notifications on a Redis marker per `event-id` header.
- Each consumer has its own group (`feed`, `notifications`). A record that keeps failing is retried 3 times and
  then parked in `paywallet.transfers.completed-dlt`; malformed payloads go there immediately.

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
6. One Postgres transaction: lock both accounts, debit and credit, write postings and the outbox event.
7. After commit: evict the balance cache. The relay then publishes the event to Kafka, where the feed and
   notification consumers pick it up.
8. Any failure in steps 5 and 6 releases the limit reservation.

## Pix

Talking to the central bank directly (SPI settlement and DICT directory, over the RSFN private network with
ICP-Brasil certificates) requires being a Pix participant or going through a PSP. That boundary is the
`PixGateway` interface; everything else is implemented here. Locally, `SimulatedPixGateway` stands in for the PSP:
keys starting with `unknown` are not found and payments to keys starting with `reject` are refused.

**Keys.** CPF/CNPJ keys must be the holder's own document and email keys the account email. Phone keys are
accepted without the SMS confirmation a production system needs. Random keys (EVP) are generated. Limits follow
the BCB rules: 5 keys for individuals, 20 for merchants. Before paying, `GET /pix/keys/lookup` shows the
receiver's name, CPF masked as `***.456.789-**` and institution; it is limited to 30 lookups per user per
minute to prevent harvesting the directory.

**Sending.** Every Pix gets a BCB end-to-end id (`E` + ISPB + timestamp + 11 characters) and uses the same
idempotency, daily limit and authorizer as transfers.

- Key of a user of this institution: settled immediately in the ledger (`PIX_INTERNAL`), response `201 COMPLETED`.
- Key at another institution: the payer is debited against the `SYSTEM_PIX_SETTLEMENT` account (`PIX_OUT`) and the
  payment is `202 PENDING`. A worker submits pending payments to the gateway with `SKIP LOCKED`. Accepted payments
  become `COMPLETED`; rejected ones are reversed in the ledger (`PIX_OUT_REVERSAL`), become `FAILED` with the reason
  and release the daily limit. If the network is unreachable, the payment stays pending and is retried.

**Receiving.** The PSP calls `POST /pix/webhooks/incoming`, authenticated by
`X-Pix-Signature = hex(HMAC-SHA256(secret, X-Pix-Timestamp + "." + body))` instead of a user token. Timestamps
older than 5 minutes are refused, the comparison is constant-time, and the end-to-end id makes redelivery
harmless. The credit (`PIX_IN`) and a `PixReceived` outbox event, which notifies the payee through Kafka, are
written in one transaction.

**QR codes.** `POST /pix/qr-codes` returns a static BR Code ("Pix copy and paste"): an EMV payload with the key,
optional amount, description and txid, closed by a CRC16. Payers can pay with the BR Code instead of the key;
when the code carries an amount, it is enforced.

## Merchants

Merchants (CNPJ users) create **charges** under `/merchant/**`, an area restricted to them through the
`user_type` token claim. A charge has an amount, optional description, an optional order `reference` (repeating
it returns the existing charge instead of creating another) and a validity of 30 minutes by default, 24 hours at
most. The response carries:

- a **payment link** (`/pay/{token}`) with an unguessable token. Anyone can open it to see the merchant, amount and
  status; a logged-in user pays it with their wallet balance;
- a **BR Code** with the fixed amount and the charge `txid`, when the merchant has a Pix key. A Pix carrying that
  txid, from a user of this institution or from another bank through the webhook, settles the charge.

**MDR.** The payer pays the full amount; one ledger movement with three legs credits the merchant's net amount
and the platform fee to `SYSTEM_FEES`. Defaults are 1.99% for wallet balance and 0.99% for Pix
(`app.merchant.*-fee-bps`), rounded half up to the cent, and the rate applied is stored on each charge for auditing.

**Guarantees.** A charge is paid at most once: its row is locked during payment and its id is the ledger
idempotency key, so concurrent payers produce exactly one payment. The same payer retrying gets the original
receipt; anyone else gets 409. Expired and cancelled charges are refused; a job marks overdue charges as expired
every minute. A Pix from another bank that carries the txid of a charge that can no longer be paid is still
credited (the money has already moved), as a plain Pix without fee.

**Dashboard.** `GET /merchant/dashboard?from=&to=` returns count, gross, fees and net for paid charges, split by
payment method and by day in the `America/Sao_Paulo` time zone. Merchants are notified of each payment through
the outbox and Kafka.

## Bill payments

Users pay boletos with their wallet balance. The code can be typed in any form: the 47-digit digitable line of
bank boletos, the 48-digit line of utility and tax bills (starting with 8) or the 44-digit barcode. Every check
digit is validated (modulo 10 per field or block, modulo 11 for the general digit), so typos are caught locally.
Bank boletos carry their due date as a day factor, which restarted at 1000 on 2025-02-22; the parser picks the
reading closest to today.

Beneficiary data, the amount due with interest, fines or discounts, the allowed amount range and the payment
deadline come from the clearing registry (CIP/NPC), which requires a banking partner. That boundary is the
`BillGateway` interface; `SimulatedBillGateway` stands in locally (payments whose amount ends in 99 cents are
refused, to exercise reversals).

- `POST /bills/lookup` shows what will be paid, and whether the bill is still payable.
- `POST /bills/payments` debits the wallet against `SYSTEM_BILL_SETTLEMENT` (`BILL_PAYMENT`) and returns
  `202 PENDING`, with the same idempotency, daily limit and authorizer as other outgoing payments. Open-amount bills
  take a `value` within the registry range; otherwise the amount due is paid.
- A worker submits pending payments to the partner with `SKIP LOCKED`. Accepted payments are `CONFIRMED` with the
  bank authentication code for the receipt; rejected ones are reversed (`BILL_PAYMENT_REVERSAL`), marked `FAILED`
  and release the daily limit. When the partner is unreachable, the payment stays pending and is retried.
- A partial unique index allows only one pending or confirmed payment per barcode, so a bill is never paid twice
  on this platform; a failed attempt does not block paying it again.

## Running

```bash
docker compose up --build
```

Swagger UI: http://localhost:8080/swagger-ui.html (use **Authorize** with the access token).
Local S3 (LocalStack): http://localhost:4566.

Infrastructure only, with the application running locally (JDK 21 and Maven):

```bash
docker compose up -d postgres redis mongo s3 kafka
mvn spring-boot:run
```

Integration tests use **Testcontainers** (Postgres, Redis, MongoDB, Kafka and S3 via LocalStack), so Docker must be running:

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
| POST | `/pix/keys` | user | Register a Pix key (`CPF`, `CNPJ`, `EMAIL`, `PHONE`, `EVP`) |
| GET | `/pix/keys` | user | List own keys |
| DELETE | `/pix/keys/{keyId}` | user | Delete an own key |
| GET | `/pix/keys/lookup?key=` | user | Receiver name (masked document) before paying. Rate limited |
| POST | `/pix/qr-codes` | user | Static BR Code for an own key |
| POST | `/pix/payments` | user | Send a Pix by key or BR Code. Requires `Idempotency-Key`. 201 settled, 202 pending |
| GET | `/pix/payments/{endToEndId}` | payer or payee | Pix status |
| POST | `/pix/webhooks/incoming` | HMAC | Incoming Pix notification from the PSP |
| POST | `/merchant/charges` | merchant | Create a charge. 201 new, 200 when the `reference` already exists |
| GET | `/merchant/charges?status=` | merchant | List own charges |
| GET | `/merchant/charges/{id}` | merchant | Charge details, including fee and net once paid |
| POST | `/merchant/charges/{id}/cancel` | merchant | Cancel a pending charge |
| GET | `/merchant/dashboard?from=&to=` | merchant | Sales totals by payment method and by day |
| GET | `/pay/{token}` | public | What the payment link shows |
| POST | `/pay/{token}` | user | Pay the charge with wallet balance. 201 paid, 200 replay, 409 paid by someone else |
| POST | `/bills/lookup` | user | Decode a boleto and fetch beneficiary, amount due and deadline |
| POST | `/bills/payments` | user | Pay a boleto with wallet balance. Requires `Idempotency-Key`. 202 pending |
| GET | `/bills/payments` | user | Own bill payments |
| GET | `/bills/payments/{id}` | user | Payment status and bank authentication code |
| POST | `/users/{id}/deposit` | admin | Manual cash-in for testing. Requires `Idempotency-Key`. Owner allowed when `ALLOW_SELF_DEPOSIT=true` (dev) |
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
- **`docker compose up --build` hangs with no output**: Compose delegates the build to `docker buildx bake`, which
  has been seen to stall intermittently on Docker Desktop before printing anything. Stop it and run the command
  again; if it keeps hanging, restart Docker Desktop, or build directly and start without building:
  `docker build -t paywallet-app .` then `docker compose up -d --no-build`.
- **`minio/minio` image not found**: MinIO stopped publishing images to Docker Hub, which is why local S3 uses LocalStack.
- **Flyway checksum mismatch** after pulling changes to existing migrations: this project is pre-release and
  migrations may still be edited. Reset local data with `docker compose down -v`.
- LocalStack validates presigned URL signatures but does not block anonymous reads of the bucket. On real S3,
  Block Public Access is on by default and must stay on.

## Known limitations and next steps

- Records in the DLT are not replayed automatically; they need inspection and a manual re-publish.
- The outbox is polled; at very high volume, change data capture (e.g. Debezium) on `outbox_events` avoids polling.
- Access tokens cannot be revoked before they expire (15 min); refresh tokens can.
- Login throttling is per email only, so an attacker can temporarily lock out a victim. Add per-IP limits at the gateway.
- The cached balance may be up to 30 s stale in a rare read/write race; it never affects decisions.
- The daily limit lives only in Redis; if Redis loses data, the day's counter resets.
- CPF/CNPJ check digits are not validated.
- Merchants still missing: MDR negotiated per merchant, refunds of paid charges, webhooks notifying the merchant's
  own systems (e-commerce), receivables settlement schedules and anticipation, card acquiring and POS terminals.
  Charge QR codes are static BR Codes with a txid; true dynamic Pix QR codes point to a signed payload hosted by
  the PSP.
- Bill payments still missing: a real banking partner adapter, scheduling payments for a future date, the
  clearing cut-off time (payments after it settle on the next business day), installment payment with a credit
  card, and PDF receipts.
- Pix still missing: a real PSP adapter for `PixGateway`, SMS confirmation of phone keys, dynamic QR codes
  (charges with expiry), the lower nighttime Pix limit (8 p.m. to 6 a.m.), refunds and the BCB special refund
  mechanism (MED) for fraud, and key portability and claims between institutions.
