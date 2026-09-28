import { ArrowLeft, CheckCircle2, Clock, ShieldAlert, Undo2, XCircle } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { Link, useParams } from 'react-router'
import {
  dateTime, errorMessage, money, newIdempotencyKey, parseAmount, useFileFraudClaim, useFraudClaims, usePixPayment,
  usePixReturns, useReturnPix,
} from '@paywallet/core'
import type { PixPayment, PixReturnReason } from '@paywallet/core'
import { actionError, usePin, useToast } from '../components/feedback'
import { Alert, Button, Card, Input, Modal, Row, Select, Spinner, StatusBadge } from '../components/ui'

const reasons: Record<PixReturnReason, string> = {
  MD06: 'Customer asked for a refund',
  BE08: 'Payment made by mistake',
  FR01: 'Fraud',
  SL02: 'Other',
}

export default function PixPaymentPage() {
  const { endToEndId = '' } = useParams()
  const payment = usePixPayment(endToEndId)
  const returns = usePixReturns(endToEndId)
  const claims = useFraudClaims()
  const [returning, setReturning] = useState(false)
  const [claiming, setClaiming] = useState(false)

  if (payment.isPending) return <Spinner />
  if (payment.error) return <Alert>{errorMessage(payment.error)}</Alert>
  const p = payment.data
  const incoming = p.direction === 'IN'
  const returned = returns.data?.filter((r) => r.status !== 'FAILED').reduce((sum, r) => sum + r.value, 0) ?? 0
  const claim = claims.data?.find((c) => c.endToEndId === endToEndId)

  return (
    <div className="mx-auto max-w-lg space-y-4">
      <Link to="/pix?tab=history" className="inline-flex items-center gap-1 text-sm font-semibold text-brand-700"><ArrowLeft className="size-4" /> Pix history</Link>
      <Card className="text-center">
        <StatusIcon status={p.status} />
        <p className="mt-3 text-sm text-muted">{incoming ? 'Pix received' : 'Pix sent'}</p>
        <p className="text-3xl font-bold tabular-nums">{money(p.value)}</p>
        <div className="mt-2"><StatusBadge status={p.status} /></div>
        {p.status === 'PENDING' && <p className="mt-2 text-sm text-muted">Waiting for the other bank to confirm…</p>}
        {p.failureReason && <div className="mt-4"><Alert>{p.failureReason}. The money is back in your balance.</Alert></div>}
        <dl className="mt-6 divide-y divide-line text-left">
          <Row label={incoming ? 'From' : 'To'}>{p.counterpartyName ?? '—'}</Row>
          {p.counterpartyDocument && <Row label="Document">{p.counterpartyDocument}</Row>}
          <Row label="Type">{p.scope === 'INTERNAL' ? 'PayWallet to PayWallet' : 'Another bank'}</Row>
          {p.description && <Row label="Description">{p.description}</Row>}
          <Row label="Date">{dateTime(p.createdAt)}</Row>
          <Row label="ID"><span className="break-all font-mono text-xs">{p.endToEndId}</span></Row>
        </dl>
      </Card>

      {returns.data && returns.data.length > 0 && (
        <Card>
          <h2 className="mb-2 font-semibold">Returns</h2>
          <ul className="divide-y divide-line">
            {returns.data.map((r) => (
              <li key={r.returnId} className="flex items-center justify-between py-2 text-sm">
                <span>{money(r.value)} · {reasons[r.reason]}</span>
                <StatusBadge status={r.status} />
              </li>
            ))}
          </ul>
        </Card>
      )}

      {claim && (
        <Alert tone={claim.status === 'OPEN' ? 'warning' : 'info'}>
          Fraud report {claim.status.toLowerCase()}: {money(claim.blocked)} blocked, {money(claim.returned)} returned to you.
        </Alert>
      )}

      {p.status === 'COMPLETED' && (
        <div className="flex flex-wrap gap-3">
          {incoming && returned < p.value && (
            <Button variant="secondary" onClick={() => setReturning(true)}><Undo2 className="size-4" /> Return money</Button>
          )}
          {!incoming && !claim && (
            <Button variant="secondary" onClick={() => setClaiming(true)}><ShieldAlert className="size-4" /> Report fraud</Button>
          )}
        </div>
      )}
      <ReturnDialog open={returning} onClose={() => setReturning(false)} payment={p} available={p.value - returned} />
      <ClaimDialog open={claiming} onClose={() => setClaiming(false)} endToEndId={endToEndId} />
    </div>
  )
}

function StatusIcon({ status }: { status: PixPayment['status'] }) {
  if (status === 'COMPLETED') return <CheckCircle2 className="mx-auto size-14 text-brand-500" />
  if (status === 'FAILED') return <XCircle className="mx-auto size-14 text-red-500" />
  return <Clock className="mx-auto size-14 animate-pulse text-amber-500" />
}

function ReturnDialog({ open, onClose, payment, available }: { open: boolean; onClose: () => void; payment: PixPayment; available: number }) {
  const giveBack = useReturnPix(payment.endToEndId)
  const withPin = usePin()
  const toast = useToast()
  const [amount, setAmount] = useState(available.toFixed(2).replace('.', ','))
  const [reason, setReason] = useState<PixReturnReason>('MD06')
  const [error, setError] = useState<string>()
  const value = parseAmount(amount)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!value) return
    setError(undefined)
    const idempotencyKey = newIdempotencyKey()
    try {
      await withPin({
        title: 'Confirm return',
        summary: <p className="text-sm text-muted">Return <strong className="text-ink">{money(value)}</strong> to {payment.counterpartyName}.</p>,
        run: (pin) => giveBack.mutateAsync({ pin, idempotencyKey, value, reason }),
      })
      toast('Return requested')
      onClose()
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <Modal open={open} title="Return a Pix" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <Input label="Amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} hint={`Up to ${money(available)}`} />
        <Select label="Reason" value={reason} onChange={(e) => setReason(e.target.value as PixReturnReason)}>
          {Object.entries(reasons).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
        </Select>
        {error && <Alert>{error}</Alert>}
        <Button type="submit" className="w-full" disabled={!value || value > available}>Continue</Button>
      </form>
    </Modal>
  )
}

function ClaimDialog({ open, onClose, endToEndId }: { open: boolean; onClose: () => void; endToEndId: string }) {
  const file = useFileFraudClaim(endToEndId)
  const toast = useToast()
  const [description, setDescription] = useState('')

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    await file.mutateAsync(description.trim())
    toast('Fraud report filed')
    onClose()
  }

  return (
    <Modal open={open} title="Report fraud" onClose={onClose}>
      <form onSubmit={(e) => void submit(e).catch(() => undefined)} className="space-y-4">
        <Alert tone="info">
          Through the Special Return Mechanism (MED), the money still in the receiver's account is blocked while we
          analyze your report. If fraud is confirmed, it comes back to you.
        </Alert>
        <div>
          <label htmlFor="claim" className="mb-1.5 block text-sm font-medium text-slate-700">What happened?</label>
          <textarea
            id="claim"
            rows={4}
            maxLength={500}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="w-full rounded-xl border border-line p-3 text-sm outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-100"
          />
        </div>
        {file.error && <Alert>{errorMessage(file.error)}</Alert>}
        <Button type="submit" variant="danger" className="w-full" loading={file.isPending} disabled={!description.trim()}>File report</Button>
      </form>
    </Modal>
  )
}
