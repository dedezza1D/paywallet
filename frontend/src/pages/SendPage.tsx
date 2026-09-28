import { CheckCircle2, Info } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { newIdempotencyKey } from '../api/client'
import { useLimits, useTransfer } from '../api/queries'
import type { TransferResponse, Visibility } from '../api/types'
import { actionError, usePin } from '../components/feedback'
import { Alert, Button, Card, Input, PageHeader, Row, Select } from '../components/ui'
import { dateTime, money, parseAmount } from '../lib/format'

export default function SendPage() {
  const [params] = useSearchParams()
  const transfer = useTransfer()
  const limits = useLimits()
  const withPin = usePin()
  const [payee, setPayee] = useState(params.get('to') ?? '')
  const [amount, setAmount] = useState('')
  const [message, setMessage] = useState('')
  const [visibility, setVisibility] = useState<Visibility>('PRIVATE')
  const [error, setError] = useState<string>()
  const [receipt, setReceipt] = useState<TransferResponse>()
  const value = parseAmount(amount)
  const payeeId = Number(payee)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!value || !payeeId) return
    setError(undefined)
    // One key per confirmed transfer: retrying after a network error cannot send the money twice.
    const idempotencyKey = newIdempotencyKey()
    try {
      const result = await withPin({
        title: 'Confirm transfer',
        summary: <p className="text-sm text-muted">Send <strong className="text-ink">{money(value)}</strong> to user #{payeeId}.</p>,
        run: (pin) => transfer.mutateAsync({ pin, idempotencyKey, value, payee: payeeId, message: message.trim() || undefined, visibility }),
      })
      setReceipt(result)
    } catch (e) {
      setError(actionError(e))
    }
  }

  if (receipt) {
    return (
      <div className="mx-auto max-w-md">
        <Card className="text-center">
          <CheckCircle2 className="mx-auto size-14 text-brand-500" />
          <h1 className="mt-3 text-xl font-bold">Transfer sent</h1>
          <p className="mt-1 text-3xl font-bold tabular-nums">{money(receipt.value)}</p>
          <dl className="mt-6 divide-y divide-line text-left">
            <Row label="To">User #{receipt.payee}</Row>
            <Row label="When">{dateTime(receipt.createdAt)}</Row>
            <Row label="Transaction"><span className="font-mono text-xs">{receipt.transactionId}</span></Row>
          </dl>
          <Button className="mt-6 w-full" onClick={() => { setReceipt(undefined); setAmount(''); setMessage('') }}>New transfer</Button>
        </Card>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-xl">
      <PageHeader title="Transfer" subtitle="Send money instantly to another PayWallet user." />
      <Card>
        <form onSubmit={submit} className="space-y-4">
          <Alert tone="info">
            <span className="flex gap-2"><Info className="size-4 shrink-0" />
              Know their email or phone? <Link to="/pix" className="font-semibold underline">Use Pix</Link> — it works with any bank.
            </span>
          </Alert>
          <Input label="Recipient's PayWallet ID" inputMode="numeric" value={payee} onChange={(e) => setPayee(e.target.value.replace(/\D/g, ''))} placeholder="e.g. 42" />
          <Input
            label="Amount"
            inputMode="decimal"
            placeholder="0,00"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            error={amount && !value ? 'Enter an amount like 25,90.' : undefined}
            hint={limits.data ? `${money(limits.data.remainingToday)} available today` : undefined}
          />
          <Input label="Message (optional)" maxLength={140} value={message} onChange={(e) => setMessage(e.target.value)} placeholder="Pizza night 🍕" />
          <Select label="Who can see it" value={visibility} onChange={(e) => setVisibility(e.target.value as Visibility)}>
            <option value="PRIVATE">Only you and the recipient</option>
            <option value="PUBLIC">Public feed (without the amount)</option>
          </Select>
          {error && <Alert>{error}</Alert>}
          <Button type="submit" size="lg" className="w-full" disabled={!value || !payeeId}>Continue</Button>
        </form>
      </Card>
    </div>
  )
}
