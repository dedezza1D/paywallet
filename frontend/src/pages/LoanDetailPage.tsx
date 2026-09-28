import { ArrowLeft } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { errorMessage, newIdempotencyKey } from '../api/client'
import { prepaymentQuote, useLoan, usePayInstallment, usePrepay } from '../api/queries'
import type { PrepaymentQuote } from '../api/types'
import { actionError, usePin, useToast } from '../components/feedback'
import { Alert, Button, Card, Modal, PageHeader, Row, Select, Spinner, StatusBadge } from '../components/ui'
import { date, money, percent } from '../lib/format'

export default function LoanDetailPage() {
  const { id = '' } = useParams()
  const loan = useLoan(id)
  const payInstallment = usePayInstallment(id)
  const withPin = usePin()
  const toast = useToast()
  const [error, setError] = useState<string>()
  const [prepaying, setPrepaying] = useState(false)

  if (loan.isPending) return <Spinner />
  if (loan.error) return <Alert>{errorMessage(loan.error)}</Alert>
  const l = loan.data
  const next = l.schedule.find((e) => e.status !== 'PAID')
  const remaining = l.schedule.filter((e) => e.status !== 'PAID').length

  const payNext = async () => {
    if (!next) return
    setError(undefined)
    try {
      const result = await withPin({
        title: `Pay installment ${next.number}`,
        summary: <p className="text-sm text-muted">Pay <strong className="text-ink">{money(next.amount)}</strong> plus any late charges.</p>,
        run: (pin) => payInstallment.mutateAsync({ pin, number: next.number }),
      })
      toast(`Installment ${result.number} paid: ${money(result.total)}`)
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <div className="space-y-6">
      <Link to="/loans" className="inline-flex items-center gap-1 text-sm font-semibold text-brand-700"><ArrowLeft className="size-4" /> Loans</Link>
      <PageHeader title={`Loan of ${money(l.value)}`} subtitle={`Contracted on ${date(l.createdAt)}`} action={<StatusBadge status={l.status} />} />
      <div className="grid gap-6 lg:grid-cols-[1fr_1.5fr]">
        <Card>
          <dl className="divide-y divide-line">
            <Row label="Amount received">{money(l.value)}</Row>
            <Row label="IOF">{money(l.iof)}</Row>
            <Row label="Financed">{money(l.financed)}</Row>
            <Row label="Rate">{percent(l.monthlyRate)} a month</Row>
            <Row label="CET">{percent(l.cetAnnual)} a year</Row>
            <Row label="Outstanding principal">{money(l.outstandingPrincipal)}</Row>
          </dl>
          {l.status === 'ACTIVE' && next && (
            <div className="mt-4 space-y-3 border-t border-line pt-4">
              <Button className="w-full" onClick={payNext} loading={payInstallment.isPending}>Pay installment {next.number} now</Button>
              <Button variant="secondary" className="w-full" onClick={() => setPrepaying(true)}>Pay off early</Button>
              {error && <Alert>{error}</Alert>}
            </div>
          )}
        </Card>
        <Card className="overflow-x-auto p-0">
          <table className="w-full text-sm">
            <thead className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
              <tr>
                <th className="px-4 py-3">#</th>
                <th className="px-4 py-3">Due</th>
                <th className="px-4 py-3 text-right">Amount</th>
                <th className="px-4 py-3 text-right">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {l.schedule.map((e) => (
                <tr key={e.number}>
                  <td className="px-4 py-2.5 font-medium">{e.number}</td>
                  <td className="px-4 py-2.5">{date(e.dueDate)}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums">
                    {money(e.amount)}
                    {!!e.discount && <span className="block text-xs text-brand-700">− {money(e.discount)} discount</span>}
                    {!!e.lateCharges && <span className="block text-xs text-red-600">+ {money(e.lateCharges)} late</span>}
                  </td>
                  <td className="px-4 py-2.5 text-right"><StatusBadge status={e.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </div>
      {prepaying && <PrepayDialog loanId={id} remaining={remaining} onClose={() => setPrepaying(false)} />}
    </div>
  )
}

function PrepayDialog({ loanId, remaining, onClose }: { loanId: string; remaining: number; onClose: () => void }) {
  const prepay = usePrepay(loanId)
  const withPin = usePin()
  const toast = useToast()
  const [count, setCount] = useState(remaining)
  const [quote, setQuote] = useState<PrepaymentQuote>()
  const [error, setError] = useState<string>()
  const [loading, setLoading] = useState(false)

  const load = async (installments: number) => {
    setCount(installments)
    setQuote(undefined)
    setError(undefined)
    setLoading(true)
    try {
      setQuote(await prepaymentQuote(loanId, installments === remaining ? undefined : installments))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setLoading(false)
    }
  }

  const confirm = async () => {
    if (!quote) return
    const idempotencyKey = newIdempotencyKey()
    try {
      await withPin({
        title: 'Confirm early payment',
        summary: <p className="text-sm text-muted">Pay <strong className="text-ink">{money(quote.total)}</strong> now.</p>,
        run: (pin) => prepay.mutateAsync({ pin, idempotencyKey, installments: count === remaining ? undefined : count }),
      })
      toast(count === remaining ? 'Loan paid off' : `${count} installments paid in advance`)
      onClose()
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <Modal open title="Pay off early" onClose={onClose}>
      <div className="space-y-4">
        <Select label="Installments to pay" value={count} onChange={(e) => void load(Number(e.target.value))}>
          {Array.from({ length: remaining }, (_, i) => i + 1).map((n) => (
            <option key={n} value={n}>{n === remaining ? `All ${n} (pay off the loan)` : `Next ${n}`}</option>
          ))}
        </Select>
        {!quote && !loading && <Button variant="secondary" className="w-full" onClick={() => void load(count)}>See the discount</Button>}
        {loading && <Spinner label="Calculating" />}
        {quote && (
          <dl className="divide-y divide-line rounded-xl bg-slate-50 px-4">
            <Row label="Installments">{quote.installments.join(', ')}</Row>
            <Row label="Face value">{money(quote.nominal)}</Row>
            <Row label="Interest discount"><span className="text-brand-700">− {money(quote.discount)}</span></Row>
            {quote.lateCharges > 0 && <Row label="Late charges">{money(quote.lateCharges)}</Row>}
            <Row label="Pay now"><span className="text-base font-bold">{money(quote.total)}</span></Row>
          </dl>
        )}
        {error && <Alert>{error}</Alert>}
        {quote && <Button className="w-full" onClick={confirm} loading={prepay.isPending}>Pay {money(quote.total)}</Button>}
      </div>
    </Modal>
  )
}
