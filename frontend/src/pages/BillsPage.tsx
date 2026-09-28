import { FileText } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import {
  date, dateTime, errorMessage, lookupBill, money, newIdempotencyKey, onlyDigits, parseAmount, useBillPayments,
  usePayBill,
} from '@paywallet/core'
import type { BillQuote } from '@paywallet/core'
import { actionError, usePin, useToast } from '../components/feedback'
import { Alert, Button, Card, EmptyState, Input, PageHeader, Row, SectionTitle, Spinner, StatusBadge } from '../components/ui'

export default function BillsPage() {
  const [code, setCode] = useState('')
  const [quote, setQuote] = useState<BillQuote>()
  const [amount, setAmount] = useState('')
  const [error, setError] = useState<string>()
  const [looking, setLooking] = useState(false)
  const pay = usePayBill()
  const withPin = usePin()
  const toast = useToast()
  const digits = onlyDigits(code)
  const openAmount = !!quote && !quote.valueDue
  const value = openAmount ? parseAmount(amount) : quote?.valueDue ?? null

  const lookup = async (event: FormEvent) => {
    event.preventDefault()
    setError(undefined)
    setLooking(true)
    try {
      setQuote(await lookupBill(digits))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setLooking(false)
    }
  }

  const confirm = async () => {
    if (!quote || !value) return
    setError(undefined)
    const idempotencyKey = newIdempotencyKey()
    try {
      await withPin({
        title: 'Confirm bill payment',
        summary: <p className="text-sm text-muted">Pay <strong className="text-ink">{money(value)}</strong> to {quote.beneficiaryName}.</p>,
        run: (pin) => pay.mutateAsync({ pin, idempotencyKey, code: digits, value: openAmount ? value : undefined }),
      })
      toast('Payment sent to the bank')
      setQuote(undefined)
      setCode('')
      setAmount('')
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <>
      <PageHeader title="Pay bills" subtitle="Boletos and utility bills, confirmed by the bank in seconds." />
      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          {!quote ? (
            <form onSubmit={lookup} className="space-y-4">
              <Input
                label="Barcode number"
                inputMode="numeric"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder="47 or 48 digits"
                hint={digits ? `${digits.length} digits` : 'Type or paste the digitable line printed on the bill.'}
              />
              {error && <Alert>{error}</Alert>}
              <Button type="submit" className="w-full" size="lg" loading={looking} disabled={digits.length < 44}>Look up bill</Button>
            </form>
          ) : (
            <div className="space-y-4">
              <SectionTitle title={quote.beneficiaryName} />
              <dl className="divide-y divide-line">
                {quote.dueDate && <Row label="Due date">{date(quote.dueDate)}</Row>}
                {quote.nominalValue != null && <Row label="Bill amount">{money(quote.nominalValue)}</Row>}
                {quote.valueDue != null && quote.nominalValue != null && quote.valueDue !== quote.nominalValue && (
                  <Row label="Fees and discounts">{money(quote.valueDue - quote.nominalValue)}</Row>
                )}
                {quote.valueDue != null && <Row label="Total to pay"><span className="text-base font-bold">{money(quote.valueDue)}</span></Row>}
                {quote.paymentDeadline && <Row label="Pay until">{date(quote.paymentDeadline)}</Row>}
              </dl>
              {!quote.payable && <Alert>{quote.notPayableReason ?? 'This bill cannot be paid.'}</Alert>}
              {quote.payable && openAmount && (
                <Input
                  label="Amount"
                  inputMode="decimal"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  hint={quote.minValue || quote.maxValue ? `Between ${money(quote.minValue)} and ${money(quote.maxValue)}` : 'Any amount'}
                />
              )}
              {error && <Alert>{error}</Alert>}
              <div className="flex gap-3">
                <Button variant="secondary" className="flex-1" onClick={() => { setQuote(undefined); setError(undefined) }}>Back</Button>
                {quote.payable && <Button className="flex-1" onClick={confirm} disabled={!value}>Pay {value ? money(value) : ''}</Button>}
              </div>
            </div>
          )}
        </Card>
        <BillHistory />
      </div>
    </>
  )
}

function BillHistory() {
  const payments = useBillPayments()
  return (
    <Card>
      <SectionTitle title="Recent payments" />
      {payments.isPending ? <Spinner /> : payments.data?.content.length ? (
        <ul className="divide-y divide-line">
          {payments.data.content.map((b) => (
            <li key={b.id} className="py-3">
              <div className="flex items-center justify-between gap-3">
                <p className="truncate text-sm font-semibold">{b.beneficiaryName}</p>
                <span className="text-sm font-semibold tabular-nums">{money(b.value)}</span>
              </div>
              <div className="mt-1 flex items-center justify-between gap-3 text-xs text-muted">
                <span>{dateTime(b.createdAt)}</span>
                <StatusBadge status={b.status} />
              </div>
              {b.failureReason && <p className="mt-1 text-xs text-red-600">{b.failureReason} · refunded</p>}
              {b.authenticationCode && <p className="mt-1 break-all font-mono text-[11px] text-muted">Auth {b.authenticationCode}</p>}
            </li>
          ))}
        </ul>
      ) : (
        <EmptyState icon={<FileText className="size-6" />} title="No bills paid yet" />
      )}
    </Card>
  )
}
