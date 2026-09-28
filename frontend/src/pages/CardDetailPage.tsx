import { ArrowLeft, Lock, ShieldAlert, Unlock, XCircle } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { Link, useParams } from 'react-router'
import {
  date, dateTime, errorMessage, money, newIdempotencyKey, parseAmount, percent, useCard, useCardAction,
  useCardStatements, useCardTransactions, useDisputes, useFinanceStatement, useInstallmentOptions, useOpenDispute,
  usePayStatement, useUser,
} from '@paywallet/core'
import type { CardStatement, CardTransaction, DisputeReason } from '@paywallet/core'
import { actionError, usePin, useToast } from '../components/feedback'
import { Alert, Button, Card, EmptyState, Input, Modal, Row, Select, Spinner, StatusBadge, Tabs } from '../components/ui'
import { CardFace } from './CardsPage'

type Tab = 'purchases' | 'bills' | 'disputes'

export default function CardDetailPage() {
  const { id = '' } = useParams()
  const user = useUser()
  const card = useCard(id)
  const action = useCardAction(id)
  const toast = useToast()
  const [tab, setTab] = useState<Tab>('purchases')
  const [cancelling, setCancelling] = useState(false)

  if (card.isPending) return <Spinner />
  if (card.error) return <Alert>{errorMessage(card.error)}</Alert>
  const c = card.data
  const run = async (kind: 'block' | 'unblock' | 'cancel', message: string) => {
    await action.mutateAsync(kind)
    toast(message)
  }

  return (
    <div className="space-y-6">
      <Link to="/cards" className="inline-flex items-center gap-1 text-sm font-semibold text-brand-700"><ArrowLeft className="size-4" /> Cards</Link>
      <div className="grid gap-6 lg:grid-cols-[minmax(0,24rem)_1fr]">
        <div className="space-y-4">
          <CardFace card={c} holder={user.fullName} />
          <div className="flex flex-wrap gap-2">
            {c.status === 'ACTIVE' && <Button variant="secondary" size="sm" onClick={() => run('block', 'Card blocked')} loading={action.isPending}><Lock className="size-4" /> Block</Button>}
            {c.status === 'BLOCKED' && <Button variant="secondary" size="sm" onClick={() => run('unblock', 'Card unblocked')} loading={action.isPending}><Unlock className="size-4" /> Unblock</Button>}
            {c.status !== 'CANCELLED' && <Button variant="ghost" size="sm" className="text-red-600 hover:bg-red-50" onClick={() => setCancelling(true)}><XCircle className="size-4" /> Cancel card</Button>}
          </div>
          {action.error && <Alert>{errorMessage(action.error)}</Alert>}
        </div>
        <Card>
          <dl className="divide-y divide-line">
            <Row label="Status"><StatusBadge status={c.status} /></Row>
            {c.type === 'CREDIT' && (
              <>
                <Row label="Credit limit">{money(c.creditLimit)}</Row>
                <Row label="Available">{money(c.availableLimit)}</Row>
                <Row label="Bill closes on">Day {c.closingDay}</Row>
              </>
            )}
            {c.type === 'DEBIT' && <Row label="Pays from">Your PayWallet balance</Row>}
            <Row label="Created">{date(c.createdAt)}</Row>
          </dl>
        </Card>
      </div>

      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: 'purchases', label: 'Purchases' },
          ...(c.type === 'CREDIT' ? [{ value: 'bills' as const, label: 'Bills' }] : []),
          { value: 'disputes', label: 'Disputes' },
        ]}
      />
      {tab === 'purchases' && <Purchases cardId={id} />}
      {tab === 'bills' && <Statements cardId={id} />}
      {tab === 'disputes' && <Disputes cardId={id} />}

      <Modal open={cancelling} title="Cancel this card?" onClose={() => setCancelling(false)}>
        <p className="mb-4 text-sm text-muted">A cancelled card cannot be reactivated. Purchases already made stay on your bill.</p>
        <Button
          variant="danger"
          className="w-full"
          loading={action.isPending}
          onClick={async () => {
            await run('cancel', 'Card cancelled').catch(() => undefined)
            setCancelling(false)
          }}
        >
          Cancel card
        </Button>
      </Modal>
    </div>
  )
}

function Purchases({ cardId }: { cardId: string }) {
  const transactions = useCardTransactions(cardId)
  const [disputing, setDisputing] = useState<CardTransaction>()
  if (transactions.isPending) return <Spinner />
  const items = transactions.data?.content ?? []
  if (!items.length) return <Card><EmptyState title="No purchases yet">Use the card number at any online store.</EmptyState></Card>
  return (
    <Card>
      <ul className="divide-y divide-line">
        {items.map((t) => (
          <li key={t.id} className="flex items-center gap-3 py-3">
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold">{t.merchantName}</p>
              <p className="text-xs text-muted">
                {dateTime(t.createdAt)}{t.installments > 1 ? ` · ${t.installments} installments` : ''}
                {t.declineReason ? ` · ${t.declineReason}` : ''}
              </p>
            </div>
            <div className="flex flex-col items-end gap-1">
              <span className="text-sm font-semibold tabular-nums">{money(t.clearedAmount ?? t.amount)}</span>
              <StatusBadge status={t.status} />
            </div>
            {t.status === 'CLEARED' && (
              <Button variant="ghost" size="sm" onClick={() => setDisputing(t)} aria-label={`Dispute ${t.merchantName}`}>
                <ShieldAlert className="size-4" />
              </Button>
            )}
          </li>
        ))}
      </ul>
      {disputing && <DisputeDialog cardId={cardId} transaction={disputing} onClose={() => setDisputing(undefined)} />}
    </Card>
  )
}

const disputeReasons: Record<DisputeReason, string> = {
  NOT_RECOGNIZED: "I don't recognize this purchase",
  NOT_RECEIVED: 'I did not receive the product',
  DUPLICATE: 'Charged twice',
  WRONG_AMOUNT: 'Wrong amount',
  CANCELLED: 'I cancelled the purchase',
}

function DisputeDialog({ cardId, transaction, onClose }: { cardId: string; transaction: CardTransaction; onClose: () => void }) {
  const open = useOpenDispute(cardId)
  const toast = useToast()
  const [reason, setReason] = useState<DisputeReason>('NOT_RECOGNIZED')
  const [description, setDescription] = useState('')

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    await open.mutateAsync({ authorizationId: transaction.id, reason, description: description.trim() || undefined })
    toast('Dispute opened')
    onClose()
  }

  return (
    <Modal open title={`Dispute ${transaction.merchantName}`} onClose={onClose}>
      <form onSubmit={(e) => void submit(e).catch(() => undefined)} className="space-y-4">
        <Select label="Reason" value={reason} onChange={(e) => setReason(e.target.value as DisputeReason)}>
          {Object.entries(disputeReasons).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
        </Select>
        <Input label="Details (optional)" maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />
        {open.error && <Alert>{errorMessage(open.error)}</Alert>}
        <Button type="submit" className="w-full" loading={open.isPending}>Open dispute</Button>
      </form>
    </Modal>
  )
}

function Statements({ cardId }: { cardId: string }) {
  const statements = useCardStatements(cardId)
  if (statements.isPending) return <Spinner />
  if (!statements.data?.length) return <Card><EmptyState title="No bills yet">Your first bill closes on the closing day.</EmptyState></Card>
  return (
    <div className="space-y-4">
      {statements.data.map((s) => <StatementCard key={s.id} cardId={cardId} statement={s} />)}
    </div>
  )
}

function StatementCard({ cardId, statement }: { cardId: string; statement: CardStatement }) {
  const pay = usePayStatement(cardId)
  const finance = useFinanceStatement(cardId)
  const withPin = usePin()
  const toast = useToast()
  const [amount, setAmount] = useState('')
  const [planning, setPlanning] = useState(false)
  const [error, setError] = useState<string>()
  const payable = statement.status === 'OPEN' && statement.remaining > 0
  const partial = parseAmount(amount)

  const payNow = async (value?: number) => {
    setError(undefined)
    const idempotencyKey = newIdempotencyKey()
    try {
      await withPin({
        title: 'Pay card bill',
        summary: <p className="text-sm text-muted">Pay <strong className="text-ink">{money(value ?? statement.remaining)}</strong> from your balance.</p>,
        run: (pin) => pay.mutateAsync({ pin, idempotencyKey, statementId: statement.id, value }),
      })
      toast('Bill payment received')
      setAmount('')
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <Card>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="font-semibold">Bill closed on {date(statement.closingDate)}</p>
          <p className="text-sm text-muted">Due {date(statement.dueDate)}</p>
        </div>
        <StatusBadge status={statement.status} />
      </div>
      <dl className="mt-3 grid grid-cols-2 gap-x-6 sm:grid-cols-4">
        <Row label="Total">{money(statement.total)}</Row>
        <Row label="Minimum">{money(statement.minimumPayment)}</Row>
        <Row label="Paid">{money(statement.paid)}</Row>
        <Row label="Remaining">{money(statement.remaining)}</Row>
      </dl>
      <details className="mt-2 text-sm">
        <summary className="cursor-pointer font-medium text-brand-700">{statement.charges.length} {statement.charges.length === 1 ? 'charge' : 'charges'}</summary>
        <ul className="mt-2 divide-y divide-line">
          {statement.charges.map((charge, i) => (
            <li key={i} className="flex justify-between py-1.5">
              <span>{charge.description}{charge.installments > 1 ? ` (${charge.installment}/${charge.installments})` : ''}</span>
              <span className="tabular-nums">{money(charge.amount)}</span>
            </li>
          ))}
        </ul>
      </details>
      {payable && (
        <div className="mt-4 flex flex-wrap items-end gap-3 border-t border-line pt-4">
          <Button onClick={() => payNow()}>Pay {money(statement.remaining)}</Button>
          <Input label="Or pay part of it" inputMode="decimal" placeholder="0,00" value={amount} onChange={(e) => setAmount(e.target.value)} className="w-40" />
          <Button variant="secondary" disabled={!partial} onClick={() => payNow(partial!)}>Pay part</Button>
          <Button variant="ghost" onClick={() => setPlanning(true)}>Split into installments</Button>
        </div>
      )}
      {error && <div className="mt-3"><Alert>{error}</Alert></div>}
      {planning && (
        <InstallmentPlan
          cardId={cardId}
          statementId={statement.id}
          busy={finance.isPending}
          error={finance.error ? errorMessage(finance.error) : undefined}
          onChoose={async (installments) => {
            await finance.mutateAsync({ statementId: statement.id, installments })
            toast(`Bill split into ${installments} installments`)
            setPlanning(false)
          }}
          onClose={() => setPlanning(false)}
        />
      )}
    </Card>
  )
}

function InstallmentPlan({ cardId, statementId, busy, error, onChoose, onClose }: {
  cardId: string
  statementId: string
  busy: boolean
  error?: string
  onChoose: (installments: number) => Promise<void>
  onClose: () => void
}) {
  const options = useInstallmentOptions(cardId, statementId)
  return (
    <Modal open title="Split the bill" onClose={onClose}>
      {options.isPending ? <Spinner /> : (
        <ul className="max-h-80 divide-y divide-line overflow-y-auto">
          {options.data?.map((o) => (
            <li key={o.installments} className="flex items-center justify-between gap-3 py-2.5">
              <div>
                <p className="text-sm font-semibold">{o.installments}× {money(o.installmentAmount)}</p>
                <p className="text-xs text-muted">Total {money(o.total)} · {percent(o.monthlyRate)} a month</p>
              </div>
              <Button size="sm" variant="secondary" loading={busy} onClick={() => void onChoose(o.installments).catch(() => undefined)}>Choose</Button>
            </li>
          ))}
        </ul>
      )}
      {(error || options.error) && <div className="mt-3"><Alert>{error ?? errorMessage(options.error)}</Alert></div>}
    </Modal>
  )
}

function Disputes({ cardId }: { cardId: string }) {
  const disputes = useDisputes(cardId)
  if (disputes.isPending) return <Spinner />
  if (!disputes.data?.length) return <Card><EmptyState title="No disputes">Dispute a purchase from the Purchases tab.</EmptyState></Card>
  return (
    <Card>
      <ul className="divide-y divide-line">
        {disputes.data.map((d) => (
          <li key={d.id} className="flex items-center justify-between gap-3 py-3">
            <div>
              <p className="text-sm font-semibold">{disputeReasons[d.reason]}</p>
              <p className="text-xs text-muted">{dateTime(d.createdAt)}</p>
            </div>
            <div className="flex flex-col items-end gap-1">
              <span className="text-sm font-semibold tabular-nums">{money(d.amount)}</span>
              <StatusBadge status={d.status} />
            </div>
          </li>
        ))}
      </ul>
    </Card>
  )
}
