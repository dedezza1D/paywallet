import { ShieldCheck, Sparkles, Zap } from 'lucide-react'
import { type FormEvent, type ReactNode, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import {
  ApiError, errorMessage, formatCpf, isEmail, isValidCpf, onlyDigits, passwordProblem, request, useAuth,
} from '@paywallet/core'
import type { MfaChallenge } from '@paywallet/core'
import { Alert, Button, Input } from '../components/ui'
import { Logo } from '../layout/AppLayout'

function AuthShell({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children: ReactNode }) {
  return (
    <div className="grid min-h-screen lg:grid-cols-2">
      <div className="hidden flex-col justify-between bg-brand-900 p-12 text-white lg:flex">
        <div className="[&_span]:text-white"><Logo /></div>
        <div className="space-y-8">
          <h2 className="max-w-md text-4xl font-bold leading-tight">Your money, instant and in one place.</h2>
          <ul className="space-y-4 text-brand-100">
            <li className="flex items-center gap-3"><Zap className="size-5" /> Pix, transfers and bills in seconds</li>
            <li className="flex items-center gap-3"><Sparkles className="size-5" /> Earn 100% of the CDI on your balance</li>
            <li className="flex items-center gap-3"><ShieldCheck className="size-5" /> Transaction PIN and two-factor sign-in</li>
          </ul>
        </div>
        <p className="text-sm text-brand-200">PayWallet — demo environment, no real money.</p>
      </div>
      <div className="flex items-center justify-center px-4 py-12">
        <div className="w-full max-w-sm">
          <div className="mb-8 lg:hidden"><Logo /></div>
          <h1 className="text-2xl font-bold tracking-tight">{title}</h1>
          {subtitle && <p className="mt-2 text-sm text-muted">{subtitle}</p>}
          <div className="mt-8">{children}</div>
        </div>
      </div>
    </div>
  )
}

export function LoginPage() {
  const { login, completeMfa } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [challenge, setChallenge] = useState<MfaChallenge>()
  const [code, setCode] = useState('')
  const [error, setError] = useState<ReactNode>()
  const [busy, setBusy] = useState(false)
  const state = location.state as { from?: string; verified?: boolean; passwordReset?: boolean; accountClosed?: boolean } | null
  const destination = state?.from ?? '/'
  const notice = state?.verified
    ? 'Email confirmed. Sign in to continue.'
    : state?.passwordReset
      ? 'Password changed. Sign in with your new password.'
      : state?.accountClosed ? 'Your account is closed. Thank you for using PayWallet.' : undefined

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      if (challenge) {
        await completeMfa(challenge.mfaToken, code.trim())
      } else {
        const mfa = await login(email.trim(), password)
        if (mfa) {
          setChallenge(mfa)
          return
        }
      }
      navigate(destination, { replace: true })
    } catch (e) {
      if (e instanceof ApiError && e.status === 403 && /verif/i.test(e.message)) {
        setError(
          <>
            Confirm your email first.{' '}
            <Link className="font-semibold underline" to={`/verify-email?email=${encodeURIComponent(email.trim())}`}>
              Enter the code
            </Link>
          </>,
        )
      } else if (challenge && e instanceof ApiError && e.status === 401) {
        setError('Invalid code. After 5 wrong codes, sign in again.')
      } else {
        setError(errorMessage(e))
      }
    } finally {
      setBusy(false)
    }
  }

  if (challenge) {
    return (
      <AuthShell title="Two-factor authentication" subtitle="Enter the 6-digit code from your authenticator app, or one of your recovery codes.">
        <form onSubmit={submit} className="space-y-4">
          <Input
            label="Code"
            autoFocus
            autoComplete="one-time-code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="123456"
          />
          {error && <Alert>{error}</Alert>}
          <Button type="submit" className="w-full" loading={busy} disabled={!code.trim()}>Verify</Button>
          <button type="button" className="w-full text-sm text-muted hover:text-ink" onClick={() => setChallenge(undefined)}>
            Use another account
          </button>
        </form>
      </AuthShell>
    )
  }

  return (
    <AuthShell title="Welcome back" subtitle={<>New here? <Link to="/signup" className="font-semibold text-brand-700">Create an account</Link></>}>
      <form onSubmit={submit} className="space-y-4">
        {notice && !error && <Alert tone="success">{notice}</Alert>}
        <Input label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Input label="Password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        <div className="text-right">
          <Link to="/forgot-password" className="text-sm font-medium text-brand-700">Forgot your password?</Link>
        </div>
        {error && <Alert>{error}</Alert>}
        <Button type="submit" className="w-full" size="lg" loading={busy}>Sign in</Button>
      </form>
    </AuthShell>
  )
}

export function SignupPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState({ fullName: '', document: '', email: '', password: '', confirm: '' })
  const [acceptedTerms, setAcceptedTerms] = useState(false)
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const set = (field: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [field]: e.target.value })

  const problems = {
    fullName: form.fullName.trim().split(/\s+/).length < 2 ? 'Enter your full name.' : undefined,
    document: isValidCpf(form.document) ? undefined : 'Enter a valid CPF.',
    email: isEmail(form.email) ? undefined : 'Enter a valid email.',
    password: passwordProblem(form.password),
    confirm: form.confirm === form.password ? undefined : 'Passwords do not match.',
    terms: acceptedTerms ? undefined : 'Accept the terms to continue.',
  }
  const shown = (field: keyof typeof problems) => (touched ? problems[field] : undefined)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setTouched(true)
    if (Object.values(problems).some(Boolean)) return
    setBusy(true)
    setError(undefined)
    try {
      await request('/users', {
        method: 'POST',
        auth: false,
        body: {
          fullName: form.fullName.trim(),
          document: onlyDigits(form.document),
          email: form.email.trim(),
          password: form.password,
          type: 'COMMON',
          acceptedTerms,
        },
      })
      navigate(`/verify-email?email=${encodeURIComponent(form.email.trim())}&new=1`)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthShell title="Create your account" subtitle={<>Already have one? <Link to="/login" className="font-semibold text-brand-700">Sign in</Link></>}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <Input label="Full name" autoComplete="name" value={form.fullName} onChange={set('fullName')} error={shown('fullName')} />
        <Input
          label="CPF"
          inputMode="numeric"
          value={form.document}
          onChange={(e) => setForm({ ...form, document: formatCpf(e.target.value) })}
          error={shown('document')}
          placeholder="000.000.000-00"
        />
        <Input label="Email" type="email" autoComplete="email" value={form.email} onChange={set('email')} error={shown('email')} />
        <Input
          label="Password"
          type="password"
          autoComplete="new-password"
          value={form.password}
          onChange={set('password')}
          error={shown('password')}
          hint="At least 12 characters. A passphrase works well."
        />
        <Input label="Confirm password" type="password" autoComplete="new-password" value={form.confirm} onChange={set('confirm')} error={shown('confirm')} />
        <div>
          <label className="flex items-start gap-3 text-sm text-slate-700">
            <input
              type="checkbox"
              checked={acceptedTerms}
              onChange={(e) => setAcceptedTerms(e.target.checked)}
              className="mt-0.5 size-4 accent-brand-600"
            />
            <span>
              I have read and accept the{' '}
              <Link to="/legal/terms" target="_blank" className="font-semibold text-brand-700">terms of use</Link> and the{' '}
              <Link to="/legal/privacy" target="_blank" className="font-semibold text-brand-700">privacy policy</Link>.
            </span>
          </label>
          {shown('terms') && <p className="mt-1.5 text-xs text-red-600">{shown('terms')}</p>}
        </div>
        {error && <Alert>{error}</Alert>}
        <Button type="submit" className="w-full" size="lg" loading={busy}>Create account</Button>
      </form>
    </AuthShell>
  )
}

export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [email, setEmail] = useState(params.get('email') ?? '')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState<string | undefined>(
    params.get('new') ? 'Account created. We sent a 6-digit code to your email.' : undefined,
  )
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await request('/auth/email/verify', { method: 'POST', auth: false, body: { email: email.trim(), code: code.trim() } })
      navigate('/login', { replace: true, state: { verified: true } })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const resend = async () => {
    setError(undefined)
    try {
      await request('/auth/email/verification-code', { method: 'POST', auth: false, body: { email: email.trim() } })
      setNotice('If the email is registered and not confirmed yet, a new code is on its way.')
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <AuthShell title="Confirm your email" subtitle="Enter the 6-digit code we sent you. It expires in 15 minutes.">
      <form onSubmit={submit} className="space-y-4">
        {notice && <Alert tone="success">{notice}</Alert>}
        <Input label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        <Input
          label="Code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          value={code}
          onChange={(e) => setCode(onlyDigits(e.target.value))}
          placeholder="123456"
        />
        {error && <Alert>{error}</Alert>}
        <Button type="submit" className="w-full" size="lg" loading={busy} disabled={code.length !== 6 || !email}>Confirm email</Button>
        <Button type="button" variant="ghost" className="w-full" onClick={resend} disabled={!isEmail(email)}>Send a new code</Button>
      </form>
    </AuthShell>
  )
}

export function ForgotPasswordPage() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await request('/auth/password/forgot', { method: 'POST', auth: false, body: { email: email.trim() } })
      navigate(`/reset-password?email=${encodeURIComponent(email.trim())}`)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthShell title="Reset your password" subtitle="We will email you a 6-digit code to set a new password.">
      <form onSubmit={submit} className="space-y-4">
        <Input label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        {error && <Alert>{error}</Alert>}
        <Button type="submit" className="w-full" size="lg" loading={busy} disabled={!isEmail(email)}>Send code</Button>
        <Link to="/login" className="block text-center text-sm font-medium text-brand-700">Back to sign in</Link>
      </form>
    </AuthShell>
  )
}

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [email, setEmail] = useState(params.get('email') ?? '')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const problem = password ? passwordProblem(password) : undefined

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await request('/auth/password/reset', {
        method: 'POST', auth: false, body: { email: email.trim(), code: code.trim(), newPassword: password },
      })
      navigate('/login', { replace: true, state: { passwordReset: true } })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthShell title="Choose a new password" subtitle="If the email is registered, the code is in your inbox. Every signed-in device will be signed out.">
      <form onSubmit={submit} className="space-y-4">
        <Input label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        <Input label="Code" inputMode="numeric" maxLength={6} value={code} onChange={(e) => setCode(onlyDigits(e.target.value))} />
        <Input label="New password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} error={problem} />
        {error && <Alert>{error}</Alert>}
        <Button type="submit" className="w-full" size="lg" loading={busy} disabled={code.length !== 6 || !password || !!problem}>
          Set new password
        </Button>
      </form>
    </AuthShell>
  )
}
