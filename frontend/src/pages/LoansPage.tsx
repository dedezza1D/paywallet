import { HandCoins } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { Link } from 'react-router'
import {
  ApiError, date, errorMessage, money, newIdempotencyKey, parseAmount, percent, simulateLoan, useContractLoan,
  useCreditAnalysis, useLoans,
} from '@paywallet/core'
import type { LoanQuote } from '@paywallet/core'
import { useToast } from '../components/feedback'
import { Alert, Badge, Button, Card, EmptyState, Input, Modal, PageHeader, Row, SectionTitle, Spinner, StatusBadge } from '../components/ui'

export default function LoansPage() {
  const analysis = useCreditAnalysis()
  const loans = useLoans()

  return (
    <>
      <PageHeader title="Loans" subtitle="Personal loans with the rate and total cost shown upfront." />
      <div className="grid gap-6 lg:grid-cols-[1fr_1.2fr]">
        <Card>
          <SectionTitle title="Your credit" />
          {analysis.isPending ? <Spinner /> : analysis.error ? (
            <Alert tone={analysis.error instanceof ApiError && analysis.error.status === 422 ? 'info' : 'error'}>{errorMessage(analysis.error)}</Alert>
          ) : (
            <>
              <div className="flex items-center gap-4">
                <div className="grid size-16 place-items-center rounded-full border-4 border-brand-500 text-lg font-bold">{analysis.data.score}</div>
                <div>
                  <p className="font-semibold">{analysis.data.approved ? 'Pre-approved' : 'Not approved right now'}</p>
                  {analysis.data.riskBand && <p className="text-sm text-muted">Risk band {analysis.data.riskBand}</p>}
                </div>
              </div>
              <dl className="mt-4 divide-y divide-line">
                <Row label="Credit limit">{money(analysis.data.creditLimit)}</Row>
                <Row label="Available">{money(analysis.data.available)}</Row>
                <Row label="Monthly rate">{percent(analysis.data.monthlyRate)}</Row>
                <Row label="Valid until">{date(analysis.data.expiresAt)}</Row>
              </dl>
              {analysis.data.reasons.length > 0 && (
                <ul className="mt-3 space-y-1 text-xs text-muted">
                  {analysis.data.reasons.map((reason) => <li key={reason}>• {reason}</li>)}
                </ul>
              )}
            </>
          )}
        </Card>
        {analysis.data?.approved && <Simulator available={analysis.data.available} />}
      </div>

      <h2 className="mb-3 mt-8 text-base font-semibold">Your loans</h2>
      {loans.isPending ? <Spinner /> : loans.data?.length ? (
        <div className="grid gap-4 sm:grid-cols-2">
          {loans.data.map((loan) => {
            const paid = loan.schedule.filter((e) => e.status === 'PAID').length
            return (
              <Link key={loan.id} to={`/loans/${loan.id}`}>
                <Card className="transition hover:border-brand-200">
                  <div className="flex items-center justify-between">
                    <p className="text-lg font-bold tabular-nums">{money(loan.value)}</p>
                    <StatusBadge status={loan.status} />
                  </div>
                  <p className="mt-1 text-sm text-muted">{paid} of {loan.installments} installments paid · since {date(loan.createdAt)}</p>
                  <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-slate-100">
                    <div className="h-full bg-brand-500" style={{ width: `${(paid / loan.installments) * 100}%` }} />
                  </div>
                </Card>
              </Link>
            )
          })}
        </div>
      ) : (
        <Card>
          <EmptyState icon={<HandCoins className="size-6" />} title="No loans">
            {analysis.data?.approved ? 'Simulate one above; nothing is contracted until you confirm.' : 'Loans become available once your credit is approved.'}
          </EmptyState>
        </Card>
      )}
    </>
  )
}

function Simulator({ available }: { available: number }) {
  const contract = useContractLoan()
  const toast = useToast()
  const [amount, setAmount] = useState('')
  const [installments, setInstallments] = useState(12)
  const [quote, setQuote] = useState<LoanQuote>()
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const value = parseAmount(amount)

  const simulate = async (event: FormEvent) => {
    event.preventDefault()
    if (!value) return
    setError(undefined)
    setBusy(true)
    try {
      setQuote(await simulateLoan({ value, installments }))
    } catch (e) {
      setError(errorMessage(e))
      setQuote(undefined)
    } finally {
      setBusy(false)
    }
  }

  const confirm = async () => {
    if (!quote) return
    await contract.mutateAsync({ idempotencyKey: newIdempotencyKey(), value: quote.value, installments: quote.installments })
    toast(`${money(quote.value)} credited to your balance`)
    setConfirming(false)
    setQuote(undefined)
    setAmount('')
  }

  return (
    <Card>
      <SectionTitle title="Simulate a loan" action={<Badge tone="green">Up to {money(available)}</Badge>} />
      <form onSubmit={simulate} className="space-y-4">
        <Input label="How much do you need?" inputMode="decimal" placeholder="0,00" value={amount} onChange={(e) => { setAmount(e.target.value); setQuote(undefined) }} />
        <div>
          <label htmlFor="installments" className="mb-1.5 flex justify-between text-sm font-medium text-slate-700">
            Installments <span className="text-ink">{installments}×</span>
          </label>
          <input
            id="installments"
            type="range"
            min={3}
            max={24}
            value={installments}
            onChange={(e) => { setInstallments(Number(e.target.value)); setQuote(undefined) }}
            className="w-full accent-brand-600"
          />
        </div>
        {error && <Alert>{error}</Alert>}
        <Button type="submit" variant="secondary" className="w-full" loading={busy} disabled={!value}>Simulate</Button>
      </form>
      {quote && (
        <div className="mt-5 rounded-xl bg-brand-50 p-4">
          <p className="text-sm text-brand-900">{quote.installments} installments of</p>
          <p className="text-3xl font-bold tabular-nums text-brand-900">{money(quote.installmentAmount)}</p>
          <dl className="mt-3 divide-y divide-brand-100">
            <Row label="You receive">{money(quote.value)}</Row>
            <Row label="IOF (tax)">{money(quote.iof)}</Row>
            <Row label="Interest">{percent(quote.monthlyRate)} a month</Row>
            <Row label="Total cost (CET)">{percent(quote.cetMonthly)} a month · {percent(quote.cetAnnual)} a year</Row>
            <Row label="Total to pay">{money(quote.totalPayable)}</Row>
            <Row label="First installment">{date(quote.schedule[0]?.dueDate)}</Row>
          </dl>
          <Button className="mt-4 w-full" onClick={() => setConfirming(true)}>Get this loan</Button>
        </div>
      )}
      <Modal open={confirming} title="Confirm loan" onClose={() => setConfirming(false)}>
        {quote && (
          <div className="space-y-4">
            <p className="text-sm text-muted">
              {money(quote.value)} will be credited to your balance now. You will pay {quote.installments} monthly installments of{' '}
              {money(quote.installmentAmount)}, debited from your balance on each due date. You can pay it off early with an interest discount.
            </p>
            {contract.error && <Alert>{errorMessage(contract.error)}</Alert>}
            <Button className="w-full" loading={contract.isPending} onClick={() => void confirm().catch(() => undefined)}>Confirm loan</Button>
          </div>
        )}
      </Modal>
    </Card>
  )
}
