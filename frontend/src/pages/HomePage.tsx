import { ArrowLeftRight, FileText, Gift, History, QrCode, TrendingUp } from 'lucide-react'
import { Link } from 'react-router'
import { money, percent, useCashback, useLimits, useStatement, useUser, useYield } from '@paywallet/core'
import { ActivityList, BalanceCard } from '../components/wallet'
import { Alert, Card, EmptyState, SectionTitle, Spinner } from '../components/ui'

const actions = [
  { to: '/pix', label: 'Pix', icon: QrCode },
  { to: '/send', label: 'Transfer', icon: ArrowLeftRight },
  { to: '/bills', label: 'Pay a bill', icon: FileText },
  { to: '/store', label: 'Gift cards', icon: Gift },
]

export default function HomePage() {
  const user = useUser()
  const statement = useStatement(0, 6)
  const limits = useLimits()
  const earnings = useYield()
  const cashback = useCashback()
  const firstName = user.fullName.split(' ')[0]
  const used = limits.data ? Math.min(100, (limits.data.usedToday / limits.data.dailyLimit) * 100) : 0

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold tracking-tight">Hi, {firstName}</h1>
      {!user.transactionPinSet && (
        <Alert tone="warning">
          Create your transaction PIN to send money.{' '}
          <Link to="/settings/security" className="font-semibold underline">Create PIN</Link>
        </Alert>
      )}
      <div className="grid gap-6 lg:grid-cols-[1.4fr_1fr]">
        <BalanceCard />
        <div className="grid grid-cols-4 gap-3 self-center lg:grid-cols-2">
          {actions.map(({ to, label, icon: Icon }) => (
            <Link key={to} to={to} className="flex flex-col items-center gap-2 rounded-2xl border border-line bg-white p-4 text-center text-xs font-semibold shadow-xs transition hover:border-brand-200 hover:bg-brand-50 sm:text-sm">
              <Icon className="size-6 text-brand-600" /> {label}
            </Link>
          ))}
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1.4fr_1fr]">
        <Card>
          <SectionTitle title="Recent activity" action={<Link to="/activity" className="text-sm font-semibold text-brand-700">See all</Link>} />
          {statement.isPending ? <Spinner /> : statement.data?.content.length ? (
            <ActivityList entries={statement.data.content} />
          ) : (
            <EmptyState icon={<History className="size-6" />} title="No activity yet">Add money or receive a Pix to get started.</EmptyState>
          )}
        </Card>
        <div className="space-y-6">
          <Card>
            <SectionTitle title="Earnings" action={<Link to="/earnings" className="text-sm font-semibold text-brand-700">Details</Link>} />
            <div className="flex items-center gap-3">
              <div className="rounded-xl bg-brand-50 p-2.5 text-brand-600"><TrendingUp className="size-5" /></div>
              <div>
                <p className="text-lg font-bold tabular-nums">{money(earnings.data?.last30Days)}</p>
                <p className="text-xs text-muted">Last 30 days · {percent(earnings.data?.annualRate)} a year</p>
              </div>
            </div>
          </Card>
          <Card>
            <SectionTitle title="Daily transfer limit" />
            <div className="h-2 overflow-hidden rounded-full bg-slate-100" role="progressbar" aria-valuenow={Math.round(used)} aria-valuemin={0} aria-valuemax={100}>
              <div className="h-full rounded-full bg-brand-500 transition-all" style={{ width: `${used}%` }} />
            </div>
            <p className="mt-2 text-xs text-muted">
              {money(limits.data?.remainingToday)} left today of {money(limits.data?.dailyLimit)}
            </p>
          </Card>
          <Card>
            <SectionTitle title="Cashback" />
            <p className="text-lg font-bold tabular-nums">{money(cashback.data?.total)}</p>
            <p className="text-xs text-muted">{money(cashback.data?.thisMonth)} this month from the store</p>
          </Card>
        </div>
      </div>
    </div>
  )
}
