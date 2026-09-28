import { useQueryClient } from '@tanstack/react-query'
import { Download, FileUp, KeyRound, LockKeyhole, ShieldCheck, Smartphone, UserX } from 'lucide-react'
import { type FormEvent, type ReactNode, useState } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router'
import {
  closeAccount, date, dateTime, errorMessage, kycDownloadUrl, maskDocument, onlyDigits, passwordProblem, request,
  useClosureCheck, useKycDocuments, useUploadKyc, useUser,
} from '@paywallet/core'
import type { KycType } from '@paywallet/core'
import { useToast } from '../components/feedback'
import {
  Alert, Badge, Button, Card, CopyButton, EmptyState, Input, PageHeader, QrCode, Row, SectionTitle, Select, Spinner,
  StatusBadge,
} from '../components/ui'
import { cx } from '../lib/cx'

const sections = [
  { id: 'profile', label: 'Profile' },
  { id: 'security', label: 'Security' },
  { id: 'documents', label: 'Documents' },
]

export default function SettingsPage() {
  const { section = 'profile' } = useParams()
  return (
    <>
      <PageHeader title="Settings" />
      <nav className="mb-6 flex gap-2" aria-label="Settings">
        {sections.map((s) => (
          <NavLink
            key={s.id}
            to={`/settings/${s.id}`}
            className={({ isActive }) => cx(
              'rounded-xl px-4 py-2 text-sm font-semibold',
              isActive ? 'bg-brand-50 text-brand-700' : 'text-muted hover:bg-slate-100 hover:text-ink',
            )}
          >
            {s.label}
          </NavLink>
        ))}
      </nav>
      {section === 'security' ? <Security /> : section === 'documents' ? <Documents /> : <Profile />}
    </>
  )
}

function Profile() {
  const user = useUser()
  return (
    <Card className="max-w-xl">
      <dl className="divide-y divide-line">
        <Row label="Name">{user.fullName}</Row>
        <Row label="CPF">{maskDocument(user.document)}</Row>
        <Row label="Email">{user.email} {user.emailVerified && <Badge tone="green">Verified</Badge>}</Row>
        <Row label="PayWallet ID"><span className="inline-flex items-center gap-2">#{user.id} <CopyButton value={String(user.id)} /></span></Row>
        <Row label="Member since">{date(user.createdAt)}</Row>
      </dl>
    </Card>
  )
}

function Security() {
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <PinCard />
      <TwoFactorCard />
      <PasswordCard />
      <CloseAccountCard />
    </div>
  )
}

function useRefreshUser() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: ['me'] })
}

function SecurityCard({ icon, title, status, children }: { icon: ReactNode; title: string; status?: ReactNode; children: ReactNode }) {
  return (
    <Card>
      <div className="mb-3 flex items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-base font-semibold"><span className="text-brand-600">{icon}</span>{title}</h2>
        {status}
      </div>
      {children}
    </Card>
  )
}

function PinCard() {
  const user = useUser()
  const refreshUser = useRefreshUser()
  const toast = useToast()
  const [password, setPassword] = useState('')
  const [pin, setPin] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const mismatch = confirm.length === 6 && confirm !== pin

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await request('/auth/pin', { method: 'POST', body: { password, pin } })
      await refreshUser()
      toast(user.transactionPinSet ? 'PIN changed' : 'PIN created')
      setPassword('')
      setPin('')
      setConfirm('')
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <SecurityCard
      icon={<LockKeyhole className="size-5" />}
      title="Transaction PIN"
      status={user.transactionPinSet ? <Badge tone="green">Active</Badge> : <Badge tone="amber">Not set</Badge>}
    >
      <p className="-mt-2 mb-4 text-sm text-muted">Six digits that confirm every payment. A stolen password alone cannot move your money.</p>
      <form onSubmit={submit} className="space-y-3">
        <Input label="Account password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <div className="grid grid-cols-2 gap-3">
          <Input label="New PIN" type="password" inputMode="numeric" maxLength={6} value={pin} onChange={(e) => setPin(onlyDigits(e.target.value))} />
          <Input label="Repeat PIN" type="password" inputMode="numeric" maxLength={6} value={confirm} onChange={(e) => setConfirm(onlyDigits(e.target.value))} error={mismatch ? 'PINs do not match.' : undefined} />
        </div>
        <p className="text-xs text-muted">Avoid sequences and repeated digits such as 123456 or 000000.</p>
        {error && <Alert>{error}</Alert>}
        <Button type="submit" loading={busy} disabled={!password || pin.length !== 6 || confirm !== pin}>
          {user.transactionPinSet ? 'Change PIN' : 'Create PIN'}
        </Button>
      </form>
    </SecurityCard>
  )
}

function TwoFactorCard() {
  const user = useUser()
  const refreshUser = useRefreshUser()
  const toast = useToast()
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string }>()
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>()
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const run = async (action: () => Promise<void>) => {
    setBusy(true)
    setError(undefined)
    try {
      await action()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const start = () => run(async () => setSetup(await request('/auth/mfa/setup', { method: 'POST' })))

  const enable = (event: FormEvent) => {
    event.preventDefault()
    void run(async () => {
      const result = await request<{ recoveryCodes: string[] }>('/auth/mfa/enable', { method: 'POST', body: { code } })
      setRecoveryCodes(result.recoveryCodes)
      setSetup(undefined)
      setCode('')
      await refreshUser()
    })
  }

  const disable = (event: FormEvent) => {
    event.preventDefault()
    void run(async () => {
      await request('/auth/mfa/disable', { method: 'POST', body: { password, code: code.trim() } })
      setPassword('')
      setCode('')
      await refreshUser()
      toast('Two-factor authentication turned off')
    })
  }

  return (
    <SecurityCard
      icon={<Smartphone className="size-5" />}
      title="Two-factor authentication"
      status={user.twoFactorEnabled ? <Badge tone="green">On</Badge> : <Badge>Off</Badge>}
    >
      <p className="-mt-2 mb-4 text-sm text-muted">Sign in with a code from an authenticator app (Google Authenticator, 1Password, Authy…).</p>
      {recoveryCodes ? (
        <div className="space-y-3">
          <Alert tone="success">Two-factor authentication is on. Save these recovery codes: each one signs you in once if you lose your phone. They are shown only now.</Alert>
          <ul className="grid grid-cols-2 gap-2 rounded-xl bg-slate-50 p-4 font-mono text-sm">
            {recoveryCodes.map((c) => <li key={c}>{c}</li>)}
          </ul>
          <div className="flex gap-2">
            <CopyButton value={recoveryCodes.join('\n')} label="Copy codes" />
            <Button size="sm" variant="ghost" onClick={() => setRecoveryCodes(undefined)}>I saved them</Button>
          </div>
        </div>
      ) : user.twoFactorEnabled ? (
        <form onSubmit={disable} className="space-y-3">
          <Input label="Account password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <Input label="Code from the app or a recovery code" value={code} onChange={(e) => setCode(e.target.value)} />
          {error && <Alert>{error}</Alert>}
          <Button type="submit" variant="secondary" loading={busy} disabled={!password || !code.trim()}>Turn off</Button>
        </form>
      ) : setup ? (
        <form onSubmit={enable} className="space-y-4">
          <div className="flex flex-col items-center gap-3 sm:flex-row sm:items-start">
            <QrCode value={setup.otpauthUri} size={160} alt="Authenticator app QR code" />
            <div className="min-w-0 text-sm">
              <p className="text-muted">Scan with your authenticator app, or type this key:</p>
              <p className="mt-2 break-all font-mono text-xs">{setup.secret}</p>
              <div className="mt-2"><CopyButton value={setup.secret} label="Copy key" /></div>
            </div>
          </div>
          <Input label="6-digit code from the app" inputMode="numeric" maxLength={6} value={code} onChange={(e) => setCode(onlyDigits(e.target.value))} />
          {error && <Alert>{error}</Alert>}
          <Button type="submit" loading={busy} disabled={code.length !== 6}>Turn on</Button>
        </form>
      ) : (
        <>
          {error && <div className="mb-3"><Alert>{error}</Alert></div>}
          <Button onClick={start} loading={busy}><ShieldCheck className="size-4" /> Set up</Button>
        </>
      )}
    </SecurityCard>
  )
}

function PasswordCard() {
  const toast = useToast()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const problem = next ? passwordProblem(next) : undefined

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await request('/auth/password/change', { method: 'POST', body: { currentPassword: current, newPassword: next } })
      toast('Password changed')
      setCurrent('')
      setNext('')
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <SecurityCard icon={<KeyRound className="size-5" />} title="Password">
      <form onSubmit={submit} className="space-y-3">
        <Input label="Current password" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        <Input label="New password" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} error={problem} />
        {error && <Alert>{error}</Alert>}
        <Button type="submit" loading={busy} disabled={!current || !next || !!problem}>Change password</Button>
      </form>
    </SecurityCard>
  )
}

function CloseAccountCard() {
  const user = useUser()
  const check = useClosureCheck()
  const navigate = useNavigate()
  const [confirming, setConfirming] = useState(false)
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await closeAccount({ password, code: code.trim() || undefined })
      navigate('/login', { replace: true, state: { accountClosed: true } })
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  return (
    <SecurityCard icon={<UserX className="size-5" />} title="Close account">
      <p className="-mt-2 mb-4 text-sm text-muted">
        Closing is permanent: you will not be able to sign in again. Your transaction history is kept for the periods
        the law requires.
      </p>
      {check.isPending ? <Spinner /> : check.data && !check.data.closable ? (
        <Alert tone="info">
          Before closing:
          <ul className="mt-1 list-disc pl-5">{check.data.blockers.map((b) => <li key={b}>{b}</li>)}</ul>
        </Alert>
      ) : !confirming ? (
        <Button variant="danger" onClick={() => setConfirming(true)}>Close my account</Button>
      ) : (
        <form onSubmit={submit} className="space-y-3">
          <Input label="Account password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          {user.twoFactorEnabled && (
            <Input label="Code from the app or a recovery code" value={code} onChange={(e) => setCode(e.target.value)} />
          )}
          {error && <Alert>{error}</Alert>}
          <div className="flex gap-3">
            <Button type="button" variant="secondary" onClick={() => setConfirming(false)}>Keep my account</Button>
            <Button type="submit" variant="danger" loading={busy} disabled={!password || (user.twoFactorEnabled && !code.trim())}>
              Close permanently
            </Button>
          </div>
        </form>
      )}
    </SecurityCard>
  )
}

const documentTypes: Record<KycType, string> = {
  ID_FRONT: 'ID card (front)',
  ID_BACK: 'ID card (back)',
  DRIVER_LICENSE: "Driver's license",
  SELFIE: 'Selfie',
  PROOF_OF_ADDRESS: 'Proof of address',
}

function Documents() {
  const documents = useKycDocuments()
  const upload = useUploadKyc()
  const toast = useToast()
  const [type, setType] = useState<KycType>('ID_FRONT')
  const [file, setFile] = useState<File>()
  const [error, setError] = useState<string>()
  const tooBig = !!file && file.size > 10 * 1024 * 1024

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!file) return
    setError(undefined)
    try {
      await upload.mutateAsync({ type, file })
      toast('Document sent for review')
      setFile(undefined)
      ;(event.target as HTMLFormElement).reset()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  const open = async (id: string) => {
    try {
      window.open((await kycDownloadUrl(id)).url, '_blank', 'noopener')
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card>
        <SectionTitle title="Send a document" />
        <form onSubmit={submit} className="space-y-4">
          <Select label="Document" value={type} onChange={(e) => setType(e.target.value as KycType)}>
            {Object.entries(documentTypes).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </Select>
          <div>
            <label htmlFor="kyc-file" className="mb-1.5 block text-sm font-medium text-slate-700">File</label>
            <input
              id="kyc-file"
              type="file"
              accept="image/jpeg,image/png,application/pdf"
              onChange={(e) => setFile(e.target.files?.[0])}
              className="block w-full text-sm file:mr-3 file:rounded-lg file:border-0 file:bg-brand-50 file:px-3 file:py-2 file:font-semibold file:text-brand-700"
            />
            <p className={cx('mt-1.5 text-xs', tooBig ? 'text-red-600' : 'text-muted')}>JPEG, PNG or PDF, up to 10 MB. Stored encrypted.</p>
          </div>
          {error && <Alert>{error}</Alert>}
          <Button type="submit" loading={upload.isPending} disabled={!file || tooBig}><FileUp className="size-4" /> Upload</Button>
        </form>
      </Card>
      <Card>
        <SectionTitle title="Your documents" />
        {documents.isPending ? <Spinner /> : documents.data?.length ? (
          <ul className="divide-y divide-line">
            {documents.data.map((d) => (
              <li key={d.id} className="flex items-center gap-3 py-3">
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-semibold">{documentTypes[d.type]}</p>
                  <p className="text-xs text-muted">{dateTime(d.createdAt)} · {Math.ceil(d.sizeBytes / 1024)} KB</p>
                </div>
                <StatusBadge status={d.status} />
                <Button variant="ghost" size="sm" onClick={() => open(d.id)} aria-label={`Download ${documentTypes[d.type]}`}>
                  <Download className="size-4" />
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState title="No documents yet">Verify your identity to unlock higher limits.</EmptyState>
        )}
      </Card>
    </div>
  )
}
