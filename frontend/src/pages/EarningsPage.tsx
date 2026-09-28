import { TrendingUp } from 'lucide-react'
import { useYield } from '../api/queries'
import { Alert, Card, EmptyState, PageHeader, SectionTitle, Spinner } from '../components/ui'
import { date, money, percent } from '../lib/format'

export default function EarningsPage() {
  const earnings = useYield()
  if (earnings.isPending) return <Spinner />
  if (earnings.error) return <Alert>{earnings.error.message}</Alert>
  const y = earnings.data
  const history = [...y.history].reverse()
  const max = Math.max(...y.history.map((d) => d.credited), 0.01)

  return (
    <>
      <PageHeader title="Earnings" subtitle={`Your balance earns ${y.cdiPercentage}% of the CDI every business day, with no lock-in.`} />
      {!y.eligible && <div className="mb-6"><Alert tone="info">Earnings start once your balance stays in the account at the end of a business day.</Alert></div>}
      <div className="grid gap-4 sm:grid-cols-3">
        <Card>
          <p className="text-sm text-muted">Last 30 days</p>
          <p className="mt-1 text-2xl font-bold tabular-nums">{money(y.last30Days)}</p>
        </Card>
        <Card>
          <p className="text-sm text-muted">Total earned</p>
          <p className="mt-1 text-2xl font-bold tabular-nums">{money(y.totalCredited)}</p>
          <p className="text-xs text-muted">{money(y.totalWithheld)} withheld in income tax and IOF</p>
        </Card>
        <Card>
          <p className="text-sm text-muted">Current rate</p>
          <p className="mt-1 text-2xl font-bold tabular-nums">{percent(y.annualRate)}</p>
          <p className="text-xs text-muted">a year, following the CDI published by the Central Bank</p>
        </Card>
      </div>
      <Card className="mt-6">
        <SectionTitle title="Daily earnings" />
        {history.length ? (
          <>
            <div className="mb-4 flex h-32 items-end gap-1" aria-hidden>
              {y.history.slice(-30).map((d) => (
                <div key={d.date} className="flex-1 rounded-t bg-brand-500/80" style={{ height: `${Math.max(4, (d.credited / max) * 100)}%` }} title={`${date(d.date)}: ${money(d.credited)}`} />
              ))}
            </div>
            <table className="w-full text-sm">
              <thead className="text-left text-xs uppercase tracking-wide text-muted">
                <tr><th className="py-2">Day</th><th className="py-2 text-right">Balance</th><th className="py-2 text-right">Gross</th><th className="py-2 text-right">Taxes</th><th className="py-2 text-right">Credited</th></tr>
              </thead>
              <tbody className="divide-y divide-line">
                {history.slice(0, 15).map((d) => (
                  <tr key={d.date}>
                    <td className="py-2">{date(d.date)}</td>
                    <td className="py-2 text-right tabular-nums">{money(d.endOfDayBalance)}</td>
                    <td className="py-2 text-right tabular-nums">{money(d.gross)}</td>
                    <td className="py-2 text-right tabular-nums">{money(d.incomeTax + d.iof)}</td>
                    <td className="py-2 text-right font-semibold tabular-nums text-brand-700">{money(d.credited)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        ) : (
          <EmptyState icon={<TrendingUp className="size-6" />} title="No earnings yet">They are credited every business day on the balance at the end of the previous one.</EmptyState>
        )}
      </Card>
    </>
  )
}
