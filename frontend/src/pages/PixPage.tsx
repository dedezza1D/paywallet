import { ArrowDownLeft, ArrowUpRight, KeyRound, Plus, QrCode, Trash2 } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import {
  createQrCode, dateTime, errorMessage, lookupPixKey, maskDocument, money, newIdempotencyKey, parseAmount,
  useDeletePixKey, usePixKeys, usePixPayments, useRegisterPixKey, useSendPix, useUser,
} from '@paywallet/core'
import type { KeyOwner, PixKeyType } from '@paywallet/core'
import { actionError, usePin, useToast } from '../components/feedback'
import {
  Alert, Badge, Button, Card, CopyButton, EmptyState, Input, Modal, PageHeader, QrCode as QrImage, Select, Spinner,
  StatusBadge, Tabs,
} from '../components/ui'

type Tab = 'send' | 'receive' | 'history' | 'keys'

export default function PixPage() {
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab) ?? 'send'
  return (
    <>
      <PageHeader title="Pix" subtitle="Instant payments, any day, any time, to any bank." />
      <Tabs
        value={tab}
        onChange={(value) => setParams({ tab: value }, { replace: true })}
        tabs={[
          { value: 'send', label: 'Send' },
          { value: 'receive', label: 'Receive' },
          { value: 'history', label: 'History' },
          { value: 'keys', label: 'My keys' },
        ]}
      />
      {tab === 'send' && <SendPix />}
      {tab === 'receive' && <ReceivePix />}
      {tab === 'history' && <PixHistory />}
      {tab === 'keys' && <PixKeys />}
    </>
  )
}

function SendPix() {
  const [mode, setMode] = useState<'key' | 'code'>('key')
  const [key, setKey] = useState('')
  const [brCode, setBrCode] = useState('')
  const [owner, setOwner] = useState<KeyOwner>()
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string>()
  const [looking, setLooking] = useState(false)
  const send = useSendPix()
  const withPin = usePin()
  const navigate = useNavigate()
  const value = parseAmount(amount)

  const lookup = async (event: FormEvent) => {
    event.preventDefault()
    setError(undefined)
    setLooking(true)
    try {
      setOwner(await lookupPixKey(key.trim()))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setLooking(false)
    }
  }

  const pay = async (event: FormEvent) => {
    event.preventDefault()
    setError(undefined)
    const idempotencyKey = newIdempotencyKey()
    const byCode = mode === 'code'
    try {
      const payment = await withPin({
        title: 'Confirm Pix',
        summary: (
          <p className="text-sm text-muted">
            {byCode
              ? <>Pay the Pix code{value ? <> of <strong className="text-ink">{money(value)}</strong></> : ''}.</>
              : <>Send <strong className="text-ink">{money(value)}</strong> to <strong className="text-ink">{owner?.name}</strong>.</>}
          </p>
        ),
        run: (pin) => send.mutateAsync(byCode
          ? { pin, idempotencyKey, brCode: brCode.trim(), value: value ?? undefined }
          : { pin, idempotencyKey, key: key.trim(), value: value!, description: description.trim() || undefined }),
      })
      navigate(`/pix/payments/${payment.endToEndId}`)
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <div className="max-w-xl">
      <Card>
        <div className="mb-5 flex gap-2">
          <Button variant={mode === 'key' ? 'primary' : 'secondary'} size="sm" onClick={() => { setMode('key'); setError(undefined) }}>
            <KeyRound className="size-4" /> Pix key
          </Button>
          <Button variant={mode === 'code' ? 'primary' : 'secondary'} size="sm" onClick={() => { setMode('code'); setError(undefined) }}>
            <QrCode className="size-4" /> Pix code (copy and paste)
          </Button>
        </div>

        {mode === 'code' ? (
          <form onSubmit={pay} className="space-y-4">
            <div>
              <label htmlFor="brcode" className="mb-1.5 block text-sm font-medium text-slate-700">Pix code</label>
              <textarea
                id="brcode"
                rows={4}
                value={brCode}
                onChange={(e) => setBrCode(e.target.value)}
                placeholder="00020126..."
                className="w-full rounded-xl border border-line p-3 font-mono text-xs outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-100"
              />
            </div>
            <Input label="Amount (only if the code has none)" inputMode="decimal" placeholder="0,00" value={amount} onChange={(e) => setAmount(e.target.value)} />
            {error && <Alert>{error}</Alert>}
            <Button type="submit" size="lg" className="w-full" disabled={!brCode.trim()}>Continue</Button>
          </form>
        ) : !owner ? (
          <form onSubmit={lookup} className="space-y-4">
            <Input
              label="Pix key"
              autoFocus
              value={key}
              onChange={(e) => setKey(e.target.value)}
              placeholder="CPF, email, phone (+55...) or random key"
            />
            {error && <Alert>{error}</Alert>}
            <Button type="submit" size="lg" className="w-full" loading={looking} disabled={!key.trim()}>Continue</Button>
          </form>
        ) : (
          <form onSubmit={pay} className="space-y-4">
            <div className="rounded-xl bg-slate-50 p-4">
              <p className="text-xs font-medium uppercase tracking-wide text-muted">Sending to</p>
              <p className="mt-1 font-semibold">{owner.name}</p>
              <p className="text-sm text-muted">{owner.document} · {owner.institution}</p>
              <button type="button" className="mt-2 text-sm font-semibold text-brand-700" onClick={() => setOwner(undefined)}>Change key</button>
            </div>
            <Input label="Amount" inputMode="decimal" autoFocus placeholder="0,00" value={amount} onChange={(e) => setAmount(e.target.value)} />
            <Input label="Description (optional)" maxLength={140} value={description} onChange={(e) => setDescription(e.target.value)} />
            {error && <Alert>{error}</Alert>}
            <Button type="submit" size="lg" className="w-full" disabled={!value}>Send {value ? money(value) : ''}</Button>
          </form>
        )}
      </Card>
    </div>
  )
}

function ReceivePix() {
  const user = useUser()
  const keys = usePixKeys()
  const [key, setKey] = useState('')
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [code, setCode] = useState<string>()
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const selected = key || keys.data?.[0]?.value || ''

  if (keys.isPending) return <Spinner />
  if (!keys.data?.length) {
    return (
      <Card>
        <EmptyState icon={<KeyRound className="size-6" />} title="Register a Pix key first">
          Keys let people pay you without your account details. <Link to="/pix?tab=keys" className="font-semibold text-brand-700">Add a key</Link>
        </EmptyState>
      </Card>
    )
  }

  const generate = async (event: FormEvent) => {
    event.preventDefault()
    setError(undefined)
    setBusy(true)
    try {
      const value = amount ? parseAmount(amount) : undefined
      const result = await createQrCode({ key: selected, value: value ?? undefined, description: description.trim() || undefined })
      setCode(result.brCode)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card>
        <form onSubmit={generate} className="space-y-4">
          <Select label="Key" value={selected} onChange={(e) => { setKey(e.target.value); setCode(undefined) }}>
            {keys.data.map((k) => <option key={k.id} value={k.value}>{k.type} · {k.value}</option>)}
          </Select>
          <Input label="Amount (optional)" inputMode="decimal" placeholder="Any amount" value={amount} onChange={(e) => { setAmount(e.target.value); setCode(undefined) }} />
          <Input label="Description (optional)" maxLength={50} value={description} onChange={(e) => { setDescription(e.target.value); setCode(undefined) }} />
          {error && <Alert>{error}</Alert>}
          <Button type="submit" className="w-full" loading={busy}>Generate QR code</Button>
        </form>
        <p className="mt-4 border-t border-line pt-4 text-sm text-muted">
          PayWallet users can also transfer to your ID: <strong className="text-ink">#{user.id}</strong>
        </p>
      </Card>
      <Card className="flex flex-col items-center justify-center gap-4 text-center">
        {code ? (
          <>
            <QrImage value={code} />
            <p className="max-w-full break-all font-mono text-[11px] text-muted">{code}</p>
            <CopyButton value={code} label="Copy Pix code" />
          </>
        ) : (
          <EmptyState icon={<QrCode className="size-6" />} title="Your QR code shows up here">Anyone can scan it with their bank app.</EmptyState>
        )}
      </Card>
    </div>
  )
}

function PixHistory() {
  const [page, setPage] = useState(0)
  const payments = usePixPayments(page)
  if (payments.isPending) return <Spinner />
  const data = payments.data
  if (!data?.content.length) return <Card><EmptyState title="No Pix yet" /></Card>
  return (
    <Card>
      <ul className="divide-y divide-line">
        {data.content.map((p) => (
          <li key={p.endToEndId}>
            <Link to={`/pix/payments/${p.endToEndId}`} className="-mx-2 flex items-center gap-3 rounded-xl px-2 py-3 hover:bg-slate-50">
              <div className={`grid size-10 place-items-center rounded-full ${p.direction === 'IN' ? 'bg-brand-50 text-brand-700' : 'bg-slate-100 text-slate-600'}`}>
                {p.direction === 'IN' ? <ArrowDownLeft className="size-5" /> : <ArrowUpRight className="size-5" />}
              </div>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-semibold">{p.counterpartyName ?? 'Pix'}</p>
                <p className="text-xs text-muted">{dateTime(p.createdAt)}</p>
              </div>
              <div className="flex flex-col items-end gap-1">
                <span className={`text-sm font-semibold tabular-nums ${p.direction === 'IN' ? 'text-brand-700' : ''}`}>
                  {p.direction === 'IN' ? '+ ' : '− '}{money(p.value)}
                </span>
                {p.status !== 'COMPLETED' && <StatusBadge status={p.status} />}
              </div>
            </Link>
          </li>
        ))}
      </ul>
      {data.totalPages > 1 && (
        <div className="mt-4 flex justify-between border-t border-line pt-4">
          <Button variant="secondary" size="sm" disabled={data.first} onClick={() => setPage(page - 1)}>Newer</Button>
          <Button variant="secondary" size="sm" disabled={data.last} onClick={() => setPage(page + 1)}>Older</Button>
        </div>
      )}
    </Card>
  )
}

const keyLabels: Record<PixKeyType, string> = { CPF: 'CPF', CNPJ: 'CNPJ', EMAIL: 'Email', PHONE: 'Phone', EVP: 'Random key' }

function PixKeys() {
  const user = useUser()
  const keys = usePixKeys()
  const register = useRegisterPixKey()
  const remove = useDeletePixKey()
  const toast = useToast()
  const [adding, setAdding] = useState(false)
  const [type, setType] = useState<PixKeyType>('EMAIL')
  const [phone, setPhone] = useState('+55')
  const [removing, setRemoving] = useState<string>()

  const value = type === 'EMAIL' ? user.email : type === 'CPF' ? user.document : type === 'PHONE' ? phone : undefined

  const add = async (event: FormEvent) => {
    event.preventDefault()
    await register.mutateAsync({ type, value })
    toast(`${keyLabels[type]} key registered`)
    setAdding(false)
  }

  return (
    <Card>
      <div className="mb-4 flex items-center justify-between">
        <p className="text-sm text-muted">Up to 5 keys. Each key points to this account.</p>
        <Button size="sm" onClick={() => { register.reset(); setAdding(true) }}><Plus className="size-4" /> Add key</Button>
      </div>
      {keys.isPending ? <Spinner /> : keys.data?.length ? (
        <ul className="divide-y divide-line">
          {keys.data.map((k) => (
            <li key={k.id} className="flex items-center gap-3 py-3">
              <Badge tone="green">{keyLabels[k.type]}</Badge>
              <span className="min-w-0 flex-1 truncate font-mono text-sm">{k.type === 'CPF' ? maskDocument(k.value) : k.value}</span>
              <CopyButton value={k.value} />
              <Button
                variant="ghost"
                size="sm"
                aria-label={`Delete ${keyLabels[k.type]} key`}
                loading={removing === k.id}
                onClick={async () => {
                  setRemoving(k.id)
                  try {
                    await remove.mutateAsync(k.id)
                    toast('Key deleted')
                  } finally {
                    setRemoving(undefined)
                  }
                }}
              >
                <Trash2 className="size-4 text-red-600" />
              </Button>
            </li>
          ))}
        </ul>
      ) : (
        <EmptyState icon={<KeyRound className="size-6" />} title="No keys yet">Add your email or CPF so people can pay you with Pix.</EmptyState>
      )}
      {remove.error && <div className="mt-3"><Alert>{errorMessage(remove.error)}</Alert></div>}

      <Modal open={adding} title="Add a Pix key" onClose={() => setAdding(false)}>
        <form onSubmit={(e) => void add(e).catch(() => undefined)} className="space-y-4">
          <Select label="Key type" value={type} onChange={(e) => setType(e.target.value as PixKeyType)}>
            <option value="EMAIL">Email · {user.email}</option>
            <option value="CPF">CPF · {maskDocument(user.document)}</option>
            <option value="PHONE">Phone number</option>
            <option value="EVP">Random key</option>
          </Select>
          {type === 'PHONE' && <Input label="Phone" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} hint="With country and area code, e.g. +5511987654321" />}
          {register.error && <Alert>{errorMessage(register.error)}</Alert>}
          <Button type="submit" className="w-full" loading={register.isPending}>Register key</Button>
        </form>
      </Modal>
    </Card>
  )
}

