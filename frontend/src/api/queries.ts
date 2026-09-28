import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './client'
import * as session from './session'
import type {
  Balance, BillPayment, BillQuote, Card, CardStatement, CardTransaction, Cashback, CreditAnalysis, Dispute,
  DisputeReason, FeedItem, FraudClaim, InstallmentOption, InstallmentPayment, KeyOwner, KycDocument, KycType, Limits,
  Loan, LoanQuote, Order, Page, PaymentReceipt, PixKey, PixKeyType, PixPayment, PixReturn, PixReturnReason, PrepaymentQuote,
  Product, PublicCharge, PublicFeedItem, StatementEntry, TransferResponse, Visibility, YieldSummary,
} from './types'

const me = () => session.claims()!.userId

/** An outflow: the PIN confirms it and the key makes retries safe (the same key never moves money twice). */
export type Confirmed = { pin: string; idempotencyKey: string }

const PENDING_POLL_MS = 1500
const pollWhilePending = <T extends { status: string }>(data: T | undefined) =>
  data?.status === 'PENDING' ? PENDING_POLL_MS : false

/** Everything that shows money moving; refreshed after any payment. */
const MONEY_KEYS = [['balance'], ['statement'], ['limits'], ['feed'], ['pix-payments'], ['cashback']]

export function useInvalidateMoney() {
  const queryClient = useQueryClient()
  return () => Promise.all(MONEY_KEYS.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
}

// ---------- Wallet ----------

export const useBalance = () =>
  useQuery({ queryKey: ['balance'], queryFn: () => request<Balance>(`/users/${me()}/balance`) })

export const useLimits = () =>
  useQuery({ queryKey: ['limits'], queryFn: () => request<Limits>(`/users/${me()}/limits`) })

export const useStatement = (page: number, size = 20) =>
  useQuery({
    queryKey: ['statement', page, size],
    queryFn: () => request<Page<StatementEntry>>(`/users/${me()}/statement?page=${page}&size=${size}`),
    placeholderData: keepPreviousData,
  })

export function useTransfer() {
  const invalidate = useInvalidateMoney()
  return useMutation({
    mutationFn: ({ pin, idempotencyKey, ...body }: Confirmed & { value: number; payee: number; message?: string; visibility: Visibility }) =>
      request<TransferResponse>('/transfer', { method: 'POST', body, pin, idempotencyKey }),
    onSuccess: invalidate,
  })
}

export function useDeposit() {
  const invalidate = useInvalidateMoney()
  return useMutation({
    mutationFn: (value: number) =>
      request(`/users/${me()}/deposit`, { method: 'POST', body: { value }, idempotencyKey: crypto.randomUUID() }),
    onSuccess: invalidate,
  })
}

export const useFeed = () =>
  useQuery({ queryKey: ['feed', 'mine'], queryFn: () => request<Page<FeedItem>>(`/users/${me()}/feed?size=30`) })

export const usePublicFeed = () =>
  useQuery({ queryKey: ['feed', 'public'], queryFn: () => request<Page<PublicFeedItem>>('/feed?size=30', { auth: false }) })

// ---------- Pix ----------

export const usePixKeys = () => useQuery({ queryKey: ['pix-keys'], queryFn: () => request<PixKey[]>('/pix/keys') })

export function useRegisterPixKey() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { type: PixKeyType; value?: string }) => request<PixKey>('/pix/keys', { method: 'POST', body }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pix-keys'] }),
  })
}

export function useDeletePixKey() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => request(`/pix/keys/${id}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pix-keys'] }),
  })
}

export const lookupPixKey = (key: string) => request<KeyOwner>(`/pix/keys/lookup?key=${encodeURIComponent(key)}`)

export const createQrCode = (body: { key: string; value?: number; description?: string }) =>
  request<{ brCode: string }>('/pix/qr-codes', { method: 'POST', body })

export function useSendPix() {
  const invalidate = useInvalidateMoney()
  return useMutation({
    mutationFn: ({ pin, idempotencyKey, ...body }: Confirmed & { key?: string; brCode?: string; value?: number; description?: string }) =>
      request<PixPayment>('/pix/payments', { method: 'POST', body, pin, idempotencyKey }),
    onSuccess: invalidate,
  })
}

export const usePixPayments = (page: number) =>
  useQuery({
    queryKey: ['pix-payments', page],
    queryFn: () => request<Page<PixPayment>>(`/pix/payments?page=${page}&size=20`),
    placeholderData: keepPreviousData,
  })

export const usePixPayment = (endToEndId: string) =>
  useQuery({
    queryKey: ['pix-payments', 'one', endToEndId],
    queryFn: () => request<PixPayment>(`/pix/payments/${endToEndId}`),
    refetchInterval: (query) => pollWhilePending(query.state.data),
  })

export const usePixReturns = (endToEndId: string) =>
  useQuery({
    queryKey: ['pix-returns', endToEndId],
    queryFn: () => request<PixReturn[]>(`/pix/payments/${endToEndId}/returns`),
    refetchInterval: (query) => (query.state.data?.some((r) => r.status === 'PENDING') ? PENDING_POLL_MS : false),
  })

export function useReturnPix(endToEndId: string) {
  const invalidate = useInvalidateMoney()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ pin, idempotencyKey, ...body }: Confirmed & { value: number; reason: PixReturnReason }) =>
      request<PixReturn>(`/pix/payments/${endToEndId}/returns`, { method: 'POST', body, pin, idempotencyKey }),
    onSuccess: async () => {
      await invalidate()
      await queryClient.invalidateQueries({ queryKey: ['pix-returns', endToEndId] })
    },
  })
}

export const useFraudClaims = () =>
  useQuery({ queryKey: ['fraud-claims'], queryFn: () => request<FraudClaim[]>('/pix/fraud-claims') })

export function useFileFraudClaim(endToEndId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (description: string) =>
      request<FraudClaim>(`/pix/payments/${endToEndId}/fraud-claims`, { method: 'POST', body: { description } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fraud-claims'] }),
  })
}

// ---------- Bills ----------

export const lookupBill = (code: string) => request<BillQuote>('/bills/lookup', { method: 'POST', body: { code } })

export function usePayBill() {
  const invalidate = useInvalidateMoney()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ pin, idempotencyKey, ...body }: Confirmed & { code: string; value?: number }) =>
      request<BillPayment>('/bills/payments', { method: 'POST', body, pin, idempotencyKey }),
    onSuccess: async () => {
      await invalidate()
      await queryClient.invalidateQueries({ queryKey: ['bills'] })
    },
  })
}

export const useBillPayments = () =>
  useQuery({
    queryKey: ['bills'],
    queryFn: () => request<Page<BillPayment>>('/bills/payments?size=30'),
    refetchInterval: (query) => (query.state.data?.content.some((b) => b.status === 'PENDING') ? PENDING_POLL_MS : false),
  })

// ---------- Cards ----------

export const useCards = () => useQuery({ queryKey: ['cards'], queryFn: () => request<Card[]>('/cards') })
export const useCard = (id: string) => useQuery({ queryKey: ['cards', id], queryFn: () => request<Card>(`/cards/${id}`) })

export function useIssueCard() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { type: Card['type']; closingDay?: number }) => request<Card>('/cards', { method: 'POST', body }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['cards'] }),
  })
}

export function useCardAction(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (action: 'block' | 'unblock' | 'cancel') => request<Card>(`/cards/${id}/${action}`, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['cards'] }),
  })
}

export const useCardTransactions = (id: string) =>
  useQuery({
    queryKey: ['cards', id, 'transactions'],
    queryFn: () => request<Page<CardTransaction>>(`/cards/${id}/transactions?size=50&sort=createdAt,desc`),
  })

export const useCardStatements = (id: string) =>
  useQuery({ queryKey: ['cards', id, 'statements'], queryFn: () => request<CardStatement[]>(`/cards/${id}/statements`) })

export const useInstallmentOptions = (cardId: string, statementId: string | undefined) =>
  useQuery({
    queryKey: ['cards', cardId, 'statements', statementId, 'options'],
    queryFn: () => request<InstallmentOption[]>(`/cards/${cardId}/statements/${statementId}/installment-options`),
    enabled: !!statementId,
  })

export function usePayStatement(cardId: string) {
  const invalidate = useInvalidateMoney()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ pin, idempotencyKey, statementId, value }: Confirmed & { statementId: string; value?: number }) =>
      request<CardStatement>(`/cards/${cardId}/statements/${statementId}/payment`, {
        method: 'POST', body: value ? { value } : {}, pin, idempotencyKey,
      }),
    onSuccess: async () => {
      await invalidate()
      await queryClient.invalidateQueries({ queryKey: ['cards'] })
    },
  })
}

export function useFinanceStatement(cardId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ statementId, installments }: { statementId: string; installments: number }) =>
      request<CardStatement>(`/cards/${cardId}/statements/${statementId}/installment-plan`, {
        method: 'POST', body: { installments },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['cards'] }),
  })
}

export const useDisputes = (cardId: string) =>
  useQuery({ queryKey: ['cards', cardId, 'disputes'], queryFn: () => request<Dispute[]>(`/cards/${cardId}/disputes`) })

export function useOpenDispute(cardId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ authorizationId, reason, description }: { authorizationId: string; reason: DisputeReason; description?: string }) =>
      request<Dispute>(`/cards/${cardId}/transactions/${authorizationId}/disputes`, {
        method: 'POST', body: { reason, description },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['cards', cardId] }),
  })
}

// ---------- Credit ----------

export const useCreditAnalysis = () =>
  useQuery({ queryKey: ['credit-analysis'], queryFn: () => request<CreditAnalysis>('/credit/analysis'), retry: false })

export const simulateLoan = (body: { value: number; installments: number }) =>
  request<LoanQuote>('/loans/simulations', { method: 'POST', body })

export function useContractLoan() {
  const invalidate = useInvalidateMoney()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: { idempotencyKey: string; value: number; installments: number }) =>
      request<Loan>('/loans', { method: 'POST', body, idempotencyKey }),
    onSuccess: async () => {
      await invalidate()
      await queryClient.invalidateQueries({ queryKey: ['loans'] })
      await queryClient.invalidateQueries({ queryKey: ['credit-analysis'] })
    },
  })
}

export const useLoans = () => useQuery({ queryKey: ['loans'], queryFn: () => request<Loan[]>('/loans') })
export const useLoan = (id: string) => useQuery({ queryKey: ['loans', id], queryFn: () => request<Loan>(`/loans/${id}`) })

export function usePayInstallment(loanId: string) {
  const invalidate = useInvalidateMoney()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ pin, number }: { pin: string; number: number }) =>
      request<InstallmentPayment>(`/loans/${loanId}/installments/${number}/payment`, { method: 'POST', pin }),
    onSuccess: async () => {
      await invalidate()
      await queryClient.invalidateQueries({ queryKey: ['loans'] })
    },
  })
}

export const prepaymentQuote = (loanId: string, installments?: number) =>
  request<PrepaymentQuote>(`/loans/${loanId}/prepayment${installments ? `?installments=${installments}` : ''}`)

export function usePrepay(loanId: string) {
  const invalidate = useInvalidateMoney()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ pin, idempotencyKey, installments }: Confirmed & { installments?: number }) =>
      request<Loan>(`/loans/${loanId}/prepayment`, {
        method: 'POST', body: installments ? { installments } : {}, pin, idempotencyKey,
      }),
    onSuccess: async () => {
      await invalidate()
      await queryClient.invalidateQueries({ queryKey: ['loans'] })
      await queryClient.invalidateQueries({ queryKey: ['credit-analysis'] })
    },
  })
}

// ---------- Marketplace ----------

export const useProducts = () =>
  useQuery({ queryKey: ['products'], queryFn: () => request<Product[]>('/marketplace/products'), staleTime: 10 * 60_000 })

export function usePurchase() {
  const invalidate = useInvalidateMoney()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ pin, idempotencyKey, ...body }: Confirmed & { productId: string; value: number; phoneNumber?: string }) =>
      request<Order>('/marketplace/orders', { method: 'POST', body, pin, idempotencyKey }),
    onSuccess: async () => {
      await invalidate()
      await queryClient.invalidateQueries({ queryKey: ['orders'] })
    },
  })
}

export const useOrders = () =>
  useQuery({
    queryKey: ['orders'],
    queryFn: () => request<Page<Order>>('/marketplace/orders?size=30&sort=createdAt,desc'),
    refetchInterval: (query) => (query.state.data?.content.some((o) => o.status === 'PENDING') ? PENDING_POLL_MS : false),
  })

export const useOrder = (id: string | undefined) =>
  useQuery({
    queryKey: ['orders', id],
    queryFn: () => request<Order>(`/marketplace/orders/${id}`),
    enabled: !!id,
    refetchInterval: (query) => pollWhilePending(query.state.data),
  })

export const useCashback = () => useQuery({ queryKey: ['cashback'], queryFn: () => request<Cashback>('/cashback') })

// ---------- Earnings, documents, payment links ----------

export const useYield = () => useQuery({ queryKey: ['yield'], queryFn: () => request<YieldSummary>(`/users/${me()}/yield`) })

export const useKycDocuments = () =>
  useQuery({ queryKey: ['kyc'], queryFn: () => request<KycDocument[]>(`/users/${me()}/kyc-documents`) })

export function useUploadKyc() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ type, file }: { type: KycType; file: File }) => {
      const form = new FormData()
      form.append('type', type)
      form.append('file', file)
      return request<KycDocument>(`/users/${me()}/kyc-documents`, { method: 'POST', body: form })
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['kyc'] }),
  })
}

export const kycDownloadUrl = (id: string) =>
  request<{ url: string; expiresAt: string }>(`/users/${me()}/kyc-documents/${id}/download-url`)

export const usePublicCharge = (token: string) =>
  useQuery({ queryKey: ['charge', token], queryFn: () => request<PublicCharge>(`/pay/${token}`, { auth: false }), retry: false })

export function usePayCharge(token: string) {
  const invalidate = useInvalidateMoney()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ pin }: { pin: string }) => request<PaymentReceipt>(`/pay/${token}`, { method: 'POST', pin }),
    onSuccess: async () => {
      await invalidate()
      await queryClient.invalidateQueries({ queryKey: ['charge', token] })
    },
  })
}
