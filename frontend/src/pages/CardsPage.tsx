import { CreditCard, Plus } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { Link } from 'react-router'
import { errorMessage, money, useCards, useIssueCard, useUser } from '@paywallet/core'
import type { Card as CardModel, CardType } from '@paywallet/core'
import { useToast } from '../components/feedback'
import { Alert, Button, Card, EmptyState, Modal, PageHeader, Select, Spinner, StatusBadge } from '../components/ui'
import { cx } from '../lib/cx'

export function CardFace({ card, holder }: { card: CardModel; holder: string }) {
  return (
    <div
      className={cx(
        'relative aspect-[1.586] w-full max-w-sm overflow-hidden rounded-2xl p-5 text-white shadow-lg',
        card.type === 'CREDIT' ? 'bg-linear-to-br from-slate-800 to-slate-950' : 'bg-linear-to-br from-brand-600 to-brand-900',
        card.status !== 'ACTIVE' && 'opacity-60 grayscale',
      )}
    >
      <div className="absolute -right-8 -top-12 size-40 rounded-full bg-white/10" aria-hidden />
      <div className="relative flex h-full flex-col justify-between">
        <div className="flex items-start justify-between">
          <span className="text-sm font-semibold uppercase tracking-wider">{card.type === 'CREDIT' ? 'Credit' : 'Debit'}</span>
          <span className="text-sm font-bold italic">{card.brand}</span>
        </div>
        <div>
          <p className="font-mono text-lg tracking-[0.2em]">•••• •••• •••• {card.last4}</p>
          <div className="mt-2 flex justify-between text-xs uppercase text-white/80">
            <span className="truncate">{holder}</span>
            <span>{String(card.expMonth).padStart(2, '0')}/{String(card.expYear).slice(-2)}</span>
          </div>
        </div>
      </div>
    </div>
  )
}

export default function CardsPage() {
  const user = useUser()
  const cards = useCards()
  const [issuing, setIssuing] = useState(false)

  return (
    <>
      <PageHeader
        title="Cards"
        subtitle="Virtual cards for online purchases. The debit card uses your balance."
        action={<Button onClick={() => setIssuing(true)}><Plus className="size-4" /> New card</Button>}
      />
      {cards.isPending ? <Spinner /> : cards.data?.length ? (
        <div className="grid gap-6 sm:grid-cols-2">
          {cards.data.map((card) => (
            <Link key={card.id} to={`/cards/${card.id}`} className="group space-y-3">
              <CardFace card={card} holder={user.fullName} />
              <div className="flex max-w-sm items-center justify-between text-sm">
                <StatusBadge status={card.status} />
                {card.type === 'CREDIT' && <span className="text-muted">Available {money(card.availableLimit)} of {money(card.creditLimit)}</span>}
              </div>
            </Link>
          ))}
        </div>
      ) : (
        <Card><EmptyState icon={<CreditCard className="size-6" />} title="No cards yet">Create a virtual debit card in a second, or apply for a credit card.</EmptyState></Card>
      )}
      <IssueDialog open={issuing} onClose={() => setIssuing(false)} />
    </>
  )
}

function IssueDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const issue = useIssueCard()
  const toast = useToast()
  const [type, setType] = useState<CardType>('DEBIT')
  const [closingDay, setClosingDay] = useState(5)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    await issue.mutateAsync(type === 'CREDIT' ? { type, closingDay } : { type })
    toast(type === 'CREDIT' ? 'Credit card approved' : 'Debit card created')
    onClose()
  }

  return (
    <Modal open={open} title="New virtual card" onClose={onClose}>
      <form onSubmit={(e) => void submit(e).catch(() => undefined)} className="space-y-4">
        <Select label="Card type" value={type} onChange={(e) => setType(e.target.value as CardType)}>
          <option value="DEBIT">Debit — pays from your balance</option>
          <option value="CREDIT">Credit — monthly bill, limit from your credit analysis</option>
        </Select>
        {type === 'CREDIT' && (
          <Select label="Bill closing day" value={closingDay} onChange={(e) => setClosingDay(Number(e.target.value))}>
            {Array.from({ length: 28 }, (_, i) => i + 1).map((day) => <option key={day} value={day}>Day {day} (due 10 days later)</option>)}
          </Select>
        )}
        {issue.error && <Alert>{errorMessage(issue.error)}</Alert>}
        <Button type="submit" className="w-full" loading={issue.isPending}>Create card</Button>
      </form>
    </Modal>
  )
}
