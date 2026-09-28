// Contracts of the PayWallet API. Amounts are decimal numbers in BRL; instants are ISO-8601 strings.

export type Page<T> = {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  first: boolean
  last: boolean
}

export type TokenResponse = {
  accessToken: string
  tokenType: string
  expiresIn: number
  refreshToken: string
  refreshExpiresIn: number
}

export type MfaChallenge = { mfaRequired: true; mfaToken: string; expiresIn: number }

export type UserType = 'COMMON' | 'MERCHANT'

export type User = {
  id: number
  fullName: string
  document: string
  email: string
  emailVerified: boolean
  type: UserType
  transactionPinSet: boolean
  twoFactorEnabled: boolean
  createdAt: string
}

/** blockers: what must be settled before the account can be closed. */
export type ClosureCheck = { closable: boolean; blockers: string[] }

export type Balance = { userId: number; accountId: string; balance: number }
export type Limits = { dailyLimit: number; usedToday: number; remainingToday: number }

export type LedgerType =
  | 'P2P_TRANSFER' | 'CASH_IN' | 'PIX_INTERNAL' | 'PIX_OUT' | 'PIX_OUT_REVERSAL' | 'PIX_IN' | 'CHARGE_PAYMENT'
  | 'BILL_PAYMENT' | 'BILL_PAYMENT_REVERSAL' | 'YIELD_CREDIT' | 'LOAN_DISBURSEMENT' | 'LOAN_INSTALLMENT_PAYMENT'
  | 'CARD_HOLD' | 'CARD_HOLD_RELEASE' | 'CARD_DEBIT_CLEARING' | 'CARD_CREDIT_CLEARING' | 'CARD_REVOLVING_INTEREST'
  | 'CARD_STATEMENT_PAYMENT' | 'MARKETPLACE_PURCHASE' | 'MARKETPLACE_REFUND' | 'MARKETPLACE_COMMISSION'
  | 'CASHBACK_CREDIT' | 'PIX_RETURN' | 'PIX_RETURN_REVERSAL' | 'PIX_MED_BLOCK' | 'PIX_MED_RELEASE' | 'PIX_MED_RETURN'
  | 'CHARGE_REFUND' | 'CARD_REFUND' | 'CARD_CHARGEBACK' | 'LOAN_PREPAYMENT' | 'CARD_STATEMENT_FINANCING'

export type StatementEntry = {
  transactionId: string
  type: LedgerType
  direction: 'DEBIT' | 'CREDIT'
  value: number
  balanceAfter: number
  createdAt: string
}

export type Visibility = 'PUBLIC' | 'PRIVATE'

export type TransferResponse = { transactionId: string; payer: number; payee: number; value: number; createdAt: string }

export type FeedItem = {
  id: string
  transactionId: string
  payerId: number
  payerName: string
  payeeId: number
  payeeName: string
  value: number
  message: string | null
  visibility: Visibility
  createdAt: string
}

export type PublicFeedItem = { payerName: string; payeeName: string; message: string | null; createdAt: string }

export type PixKeyType = 'CPF' | 'CNPJ' | 'EMAIL' | 'PHONE' | 'EVP'
export type PixKey = { id: string; type: PixKeyType; value: string; createdAt: string }
export type KeyOwner = { keyType: PixKeyType; name: string; document: string; institution: string }

export type PixStatus = 'PENDING' | 'COMPLETED' | 'FAILED'
export type PixPayment = {
  endToEndId: string
  direction: 'IN' | 'OUT'
  scope: 'INTERNAL' | 'EXTERNAL'
  status: PixStatus
  value: number
  counterpartyName: string | null
  counterpartyDocument: string | null
  counterpartyIspb: string | null
  description: string | null
  failureReason: string | null
  createdAt: string
  settledAt: string | null
}

export type PixReturnReason = 'BE08' | 'FR01' | 'MD06' | 'SL02'
export type PixReturn = {
  returnId: string
  originalEndToEndId: string
  value: number
  reason: PixReturnReason
  status: PixStatus
  failureReason: string | null
  createdAt: string
  settledAt: string | null
}

export type FraudClaim = {
  id: string
  endToEndId: string
  claimantUserId: number
  receiverUserId: number
  description: string
  blocked: number
  returned: number
  status: 'OPEN' | 'ACCEPTED' | 'REJECTED'
  createdAt: string
  resolvedAt: string | null
}

export type BillKind = 'BANK' | 'UTILITY'
export type BillQuote = {
  kind: BillKind
  barcode: string
  digitableLine: string
  bankCode: string | null
  beneficiaryName: string
  beneficiaryDocument: string | null
  dueDate: string | null
  nominalValue: number | null
  valueDue: number | null
  minValue: number | null
  maxValue: number | null
  paymentDeadline: string | null
  payable: boolean
  notPayableReason: string | null
}

export type BillPayment = {
  id: string
  status: PixStatus
  kind: BillKind
  digitableLine: string
  beneficiaryName: string
  beneficiaryDocument: string | null
  dueDate: string | null
  value: number
  authenticationCode: string | null
  failureReason: string | null
  createdAt: string
  settledAt: string | null
}

export type CardType = 'DEBIT' | 'CREDIT'
export type Card = {
  id: string
  type: CardType
  status: 'ACTIVE' | 'BLOCKED' | 'CANCELLED'
  brand: string
  last4: string
  expMonth: number
  expYear: number
  creditLimit: number | null
  availableLimit: number | null
  closingDay: number | null
  createdAt: string
}

export type CardTransaction = {
  id: string
  cardId: string
  amount: number
  merchantName: string
  mcc: string | null
  installments: number
  status: 'APPROVED' | 'DECLINED' | 'CLEARED' | 'REVERSED'
  responseCode: string
  declineReason: string | null
  clearedAmount: number | null
  createdAt: string
  updatedAt: string
}

export type StatementCharge = {
  description: string
  amount: number
  installment: number
  installments: number
  billingDate: string
}

export type CardStatement = {
  id: string
  cardId: string
  closingDate: string
  dueDate: string
  total: number
  minimumPayment: number
  paid: number
  remaining: number
  status: 'OPEN' | 'PAID' | 'CARRIED' | 'FINANCED'
  charges: StatementCharge[]
}

export type InstallmentOption = { installments: number; installmentAmount: number; total: number; monthlyRate: number }

export type DisputeReason = 'NOT_RECOGNIZED' | 'NOT_RECEIVED' | 'DUPLICATE' | 'WRONG_AMOUNT' | 'CANCELLED'
export type Dispute = {
  id: string
  authorizationId: string
  reason: DisputeReason
  description: string | null
  amount: number
  status: 'OPEN' | 'WON' | 'LOST'
  createdAt: string
  resolvedAt: string | null
}

export type CreditAnalysis = {
  id: string
  approved: boolean
  score: number
  riskBand: string
  creditLimit: number
  available: number
  monthlyRate: number
  reasons: string[]
  createdAt: string
  expiresAt: string
}

export type ScheduleEntry = {
  number: number
  dueDate: string
  amount: number
  principal: number
  interest: number
  status: string
  lateCharges: number | null
  discount: number | null
  paidAt: string | null
}

export type LoanQuote = {
  value: number
  installments: number
  monthlyRate: number
  iof: number
  financed: number
  installmentAmount: number
  totalPayable: number
  cetMonthly: number
  cetAnnual: number
  schedule: ScheduleEntry[]
}

export type Loan = {
  id: string
  status: 'ACTIVE' | 'PAID_OFF'
  value: number
  iof: number
  financed: number
  monthlyRate: number
  cetAnnual: number
  installments: number
  outstandingPrincipal: number
  createdAt: string
  paidOffAt: string | null
  schedule: ScheduleEntry[]
}

export type InstallmentPayment = {
  loanId: string
  number: number
  amount: number
  lateCharges: number
  total: number
  paidAt: string
  loanStatus: Loan['status']
}

export type PrepaymentQuote = {
  loanId: string
  installments: number[]
  nominal: number
  discount: number
  lateCharges: number
  total: number
}

export type ProductCategory = 'GIFT_CARD' | 'MOBILE_RECHARGE'
export type Product = {
  id: string
  category: ProductCategory
  brand: string
  name: string
  values: number[]
  cashbackPercent: number
}

export type Order = {
  id: string
  productId: string
  productName: string
  category: ProductCategory
  value: number
  cashback: number
  phoneNumber: string | null
  status: PixStatus
  voucherCode: string | null
  failureReason: string | null
  createdAt: string
  completedAt: string | null
}

export type Cashback = { total: number; thisMonth: number }

export type DailyYield = {
  date: string
  endOfDayBalance: number
  gross: number
  incomeTax: number
  iof: number
  credited: number
}

export type YieldSummary = {
  eligible: boolean
  cdiPercentage: number
  cdiDailyRate: number | null
  annualRate: number | null
  totalCredited: number
  totalWithheld: number
  last30Days: number
  history: DailyYield[]
}

export type KycType = 'ID_FRONT' | 'ID_BACK' | 'DRIVER_LICENSE' | 'SELFIE' | 'PROOF_OF_ADDRESS'
export type KycDocument = {
  id: string
  type: KycType
  contentType: string
  sizeBytes: number
  status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'
  createdAt: string
}

export type PublicCharge = {
  merchantName: string
  value: number
  description: string | null
  status: 'PENDING' | 'PAID' | 'EXPIRED' | 'CANCELLED'
  expiresAt: string
  brCode: string | null
}

export type PaymentReceipt = {
  chargeId: string
  status: PublicCharge['status']
  value: number
  merchantName: string
  transactionId: string
  paidAt: string
}
