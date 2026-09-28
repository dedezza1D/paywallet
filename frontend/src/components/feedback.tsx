import { CheckCircle2, LockKeyhole } from 'lucide-react'
import { createContext, type ReactNode, use, useCallback, useRef, useState } from 'react'
import { Link } from 'react-router'
import { ApiError, errorMessage } from '@paywallet/core'
import { Alert, Button, Modal } from './ui'

/** Thrown when the user closes the PIN dialog; callers ignore it instead of showing an error. */
export class PinCancelled extends Error {
  constructor() {
    super('Cancelled')
    this.name = 'PinCancelled'
  }
}

export type PinTask<T> = { title: string; summary?: ReactNode; run: (pin: string) => Promise<T> }
type PinRunner = <T>(task: PinTask<T>) => Promise<T>

const PinContext = createContext<PinRunner | null>(null)
const ToastContext = createContext<((message: string) => void) | null>(null)

export function usePin(): PinRunner {
  const context = use(PinContext)
  if (!context) throw new Error('usePin outside FeedbackProvider')
  return context
}

export function useToast() {
  const context = use(ToastContext)
  if (!context) throw new Error('useToast outside FeedbackProvider')
  return context
}

type Pending = {
  task: PinTask<unknown>
  resolve: (value: unknown) => void
  reject: (error: unknown) => void
}

export function FeedbackProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null)
  const [pin, setPin] = useState('')
  const [error, setError] = useState<string>()
  const [needsPin, setNeedsPin] = useState(false)
  const [busy, setBusy] = useState(false)
  const [toasts, setToasts] = useState<{ id: number; message: string }[]>([])
  const nextToast = useRef(0)

  const runWithPin = useCallback<PinRunner>((task) => new Promise((resolve, reject) => {
    setPin('')
    setError(undefined)
    setNeedsPin(false)
    setPending({ task: task as PinTask<unknown>, resolve: resolve as (value: unknown) => void, reject })
  }), [])

  const toast = useCallback((message: string) => {
    const id = nextToast.current++
    setToasts((current) => [...current, { id, message }])
    setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), 4000)
  }, [])

  const cancel = () => {
    pending?.reject(new PinCancelled())
    setPending(null)
  }

  const submit = async () => {
    if (!pending || pin.length !== 6) return
    setBusy(true)
    setError(undefined)
    try {
      const result = await pending.task.run(pin)
      pending.resolve(result)
      setPending(null)
    } catch (e) {
      if (e instanceof ApiError && e.isWrongPin) {
        setError('Wrong PIN. After 5 wrong attempts, payments are locked for 30 minutes.')
        setPin('')
      } else if (e instanceof ApiError && e.status === 428) {
        setNeedsPin(true)
      } else {
        pending.reject(e)
        setPending(null)
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <PinContext value={runWithPin}>
      <ToastContext value={toast}>
        {children}
        <Modal open={!!pending} title={pending?.task.title ?? ''} onClose={cancel}>
          {needsPin ? (
            <div className="space-y-4">
              <Alert tone="warning">Set up your transaction PIN before moving money.</Alert>
              <Link to="/settings/security" onClick={cancel} className="block">
                <Button className="w-full">Set up PIN</Button>
              </Link>
            </div>
          ) : (
            <form
              className="space-y-4"
              onSubmit={(event) => {
                event.preventDefault()
                void submit()
              }}
            >
              {pending?.task.summary}
              <div>
                <label htmlFor="pin" className="mb-2 flex items-center gap-2 text-sm font-medium text-slate-700">
                  <LockKeyhole className="size-4" /> Transaction PIN
                </label>
                <input
                  id="pin"
                  autoFocus
                  inputMode="numeric"
                  autoComplete="off"
                  type="password"
                  maxLength={6}
                  value={pin}
                  onChange={(event) => setPin(event.target.value.replace(/\D/g, ''))}
                  className="h-14 w-full rounded-xl border border-line text-center text-2xl tracking-[0.6em] outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-100"
                  aria-describedby={error ? 'pin-error' : undefined}
                />
              </div>
              {error && <p id="pin-error" className="text-sm text-red-600">{error}</p>}
              <Button type="submit" className="w-full" loading={busy} disabled={pin.length !== 6}>Confirm</Button>
            </form>
          )}
        </Modal>
        <div className="pointer-events-none fixed inset-x-0 bottom-20 z-50 flex flex-col items-center gap-2 px-4 md:bottom-6" aria-live="polite">
          {toasts.map((t) => (
            <div key={t.id} className="flex items-center gap-2 rounded-xl bg-ink px-4 py-3 text-sm text-white shadow-lg">
              <CheckCircle2 className="size-4 text-brand-500" /> {t.message}
            </div>
          ))}
        </div>
      </ToastContext>
    </PinContext>
  )
}

/** Error message for a failed action, or nothing when the user cancelled it. */
export function actionError(error: unknown): string | undefined {
  return error instanceof PinCancelled ? undefined : errorMessage(error)
}
