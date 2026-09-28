import type { LedgerType } from '../api/types'

const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
const dateFormat = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })
const dateTimeFormat = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit',
})

export function money(value: number | null | undefined): string {
  return brl.format(value ?? 0)
}

/** Plain dates from the API ("2026-10-05") are calendar days, not instants: format them without a time zone shift. */
export function date(value: string | null | undefined): string {
  if (!value) return '—'
  const plain = /^\d{4}-\d{2}-\d{2}$/.test(value)
  return dateFormat.format(plain ? new Date(`${value}T12:00:00`) : new Date(value))
}

export function dateTime(value: string | null | undefined): string {
  return value ? dateTimeFormat.format(new Date(value)) : '—'
}

export function percent(value: number | null | undefined, digits = 2): string {
  return value == null ? '—' : `${value.toFixed(digits)}%`
}

/**
 * Parses an amount typed by the user, in Brazilian ("1.234,56") or plain ("1234.56") notation.
 * @returns the amount with at most two decimals, or null when it is not a positive amount
 */
export function parseAmount(input: string): number | null {
  const cleaned = input.replace(/[R$\s]/g, '')
  if (!cleaned) return null
  const normalized = cleaned.includes(',') ? cleaned.replace(/\./g, '').replace(',', '.') : cleaned
  if (!/^\d+(\.\d{1,2})?$/.test(normalized)) return null
  const value = Number(normalized)
  return value > 0 ? value : null
}

export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]!.toUpperCase()).join('')
}

const ledgerLabels: Record<LedgerType, string> = {
  P2P_TRANSFER: 'Transfer',
  CASH_IN: 'Deposit',
  PIX_INTERNAL: 'Pix',
  PIX_OUT: 'Pix sent',
  PIX_OUT_REVERSAL: 'Pix reversed',
  PIX_IN: 'Pix received',
  CHARGE_PAYMENT: 'Payment to merchant',
  BILL_PAYMENT: 'Bill payment',
  BILL_PAYMENT_REVERSAL: 'Bill payment reversed',
  YIELD_CREDIT: 'Earnings',
  LOAN_DISBURSEMENT: 'Loan credited',
  LOAN_INSTALLMENT_PAYMENT: 'Loan installment',
  CARD_HOLD: 'Card purchase (pending)',
  CARD_HOLD_RELEASE: 'Card hold released',
  CARD_DEBIT_CLEARING: 'Card purchase',
  CARD_CREDIT_CLEARING: 'Credit card purchase',
  CARD_REVOLVING_INTEREST: 'Card interest',
  CARD_STATEMENT_PAYMENT: 'Card bill payment',
  MARKETPLACE_PURCHASE: 'Store purchase',
  MARKETPLACE_REFUND: 'Store refund',
  MARKETPLACE_COMMISSION: 'Store commission',
  CASHBACK_CREDIT: 'Cashback',
  PIX_RETURN: 'Pix returned',
  PIX_RETURN_REVERSAL: 'Pix return reversed',
  PIX_MED_BLOCK: 'Pix blocked (fraud claim)',
  PIX_MED_RELEASE: 'Pix released (fraud claim)',
  PIX_MED_RETURN: 'Pix returned (fraud claim)',
  CHARGE_REFUND: 'Merchant refund',
  CARD_REFUND: 'Card refund',
  CARD_CHARGEBACK: 'Card chargeback',
  LOAN_PREPAYMENT: 'Loan prepayment',
  CARD_STATEMENT_FINANCING: 'Card bill financed',
}

export function ledgerLabel(type: LedgerType): string {
  return ledgerLabels[type] ?? type
}

export function maskDocument(document: string): string {
  if (document.length === 11) return `${document.slice(0, 3)}.${document.slice(3, 6)}.${document.slice(6, 9)}-${document.slice(9)}`
  if (document.length === 14) {
    return `${document.slice(0, 2)}.${document.slice(2, 5)}.${document.slice(5, 8)}/${document.slice(8, 12)}-${document.slice(12)}`
  }
  return document
}
