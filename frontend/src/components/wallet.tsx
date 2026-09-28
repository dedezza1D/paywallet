import { ArrowDownLeft, ArrowUpRight, Eye, EyeOff, Plus } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { errorMessage } from '../api/client'
import { useBalance, useDeposit } from '../api/queries'
import type { StatementEntry } from '../api/types'
import { dateTime, ledgerLabel, money, parseAmount } from '../lib/format'
import { useHideBalance } from '../lib/prefs'
import { useToast } from './feedback'
import { Alert, Button, Input, Modal } from './ui'

export function BalanceCard() {
  const balance = useBalance()
  const [hidden, toggle] = useHideBalance()
  const [depositing, setDepositing] = useState(false)
  return (
    <section className="relative overflow-hidden rounded-3xl bg-linear-to-br from-brand-600 to-brand-900 p-6 text-white shadow-lg">
      <div className="absolute -right-10 -top-10 size-40 rounded-full bg-white/10" aria-hidden />
      <div className="relative flex items-center gap-2 text-sm text-brand-100">
        Available balance
        <button onClick={toggle} className="rounded-md p-1 hover:bg-white/10" aria-label={hidden ? 'Show balance' : 'Hide balance'}>
          {hidden ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
        </button>
      </div>
      <p className="relative mt-2 text-4xl font-bold tracking-tight tabular-nums" data-testid="balance">
        {balance.isPending ? '…' : hidden ? 'R$ ••••••' : money(balance.data?.balance)}
      </p>
      <p className="relative mt-1 text-sm text-brand-100">Earning 100% of the CDI, every business day</p>
      <Button variant="secondary" size="sm" className="relative mt-5 border-0 bg-white/15 text-white hover:bg-white/25" onClick={() => setDepositing(true)}>
        <Plus className="size-4" /> Add money
      </Button>
      <DepositDialog open={depositing} onClose={() => setDepositing(false)} />
    </section>
  )
}

function DepositDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const deposit = useDeposit()
  const toast = useToast()
  const [amount, setAmount] = useState('')
  const value = parseAmount(amount)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!value) return
    await deposit.mutateAsync(value)
    toast(`${money(value)} added to your balance`)
    setAmount('')
    onClose()
  }

  return (
    <Modal open={open} title="Add money" onClose={onClose}>
      <form onSubmit={(e) => void submit(e).catch(() => undefined)} className="space-y-4">
        <Alert tone="info">Test environment: this simulates a deposit. In production money arrives by Pix or boleto.</Alert>
        <Input label="Amount" inputMode="decimal" placeholder="0,00" autoFocus value={amount} onChange={(e) => setAmount(e.target.value)} />
        {deposit.error && <Alert>{errorMessage(deposit.error)}</Alert>}
        <Button type="submit" className="w-full" loading={deposit.isPending} disabled={!value}>Add {value ? money(value) : ''}</Button>
      </form>
    </Modal>
  )
}

export function ActivityList({ entries }: { entries: StatementEntry[] }) {
  return (
    <ul className="divide-y divide-line">
      {entries.map((entry) => {
        const incoming = entry.direction === 'CREDIT'
        return (
          <li key={`${entry.transactionId}-${entry.direction}`} className="flex items-center gap-3 py-3">
            <div className={`grid size-10 shrink-0 place-items-center rounded-full ${incoming ? 'bg-brand-50 text-brand-700' : 'bg-slate-100 text-slate-600'}`}>
              {incoming ? <ArrowDownLeft className="size-5" /> : <ArrowUpRight className="size-5" />}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold">{ledgerLabel(entry.type)}</p>
              <p className="text-xs text-muted">{dateTime(entry.createdAt)}</p>
            </div>
            <div className="text-right">
              <p className={`text-sm font-semibold tabular-nums ${incoming ? 'text-brand-700' : ''}`}>
                {incoming ? '+ ' : '− '}{money(entry.value)}
              </p>
              <p className="text-xs text-muted tabular-nums">Balance {money(entry.balanceAfter)}</p>
            </div>
          </li>
        )
      })}
    </ul>
  )
}
