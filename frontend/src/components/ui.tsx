import { Check, Copy, Loader2, X } from 'lucide-react'
import QRCode from 'qrcode'
import {
  type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type Ref,
  useEffect, useId, useRef, useState,
} from 'react'
import { cx } from '../lib/cx'
import { money } from '@paywallet/core'


type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
  size?: 'sm' | 'md' | 'lg'
  loading?: boolean
}

export function Button({ variant = 'primary', size = 'md', loading, disabled, className, children, ...props }: ButtonProps) {
  return (
    <button
      disabled={disabled || loading}
      className={cx(
        'inline-flex items-center justify-center gap-2 rounded-xl font-semibold transition disabled:cursor-not-allowed disabled:opacity-50',
        size === 'sm' && 'h-9 px-3 text-sm',
        size === 'md' && 'h-11 px-4 text-sm',
        size === 'lg' && 'h-12 px-6 text-base',
        variant === 'primary' && 'bg-brand-600 text-white hover:bg-brand-700',
        variant === 'secondary' && 'border border-line bg-white text-ink hover:bg-slate-50',
        variant === 'ghost' && 'text-brand-700 hover:bg-brand-50',
        variant === 'danger' && 'bg-red-600 text-white hover:bg-red-700',
        className,
      )}
      {...props}
    >
      {loading && <Loader2 className="size-4 animate-spin" aria-hidden />}
      {children}
    </button>
  )
}

type FieldProps = { label: string; error?: string; hint?: ReactNode; ref?: Ref<HTMLInputElement> }

export function Input({ label, error, hint, className, ref, ...props }: FieldProps & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId()
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-slate-700">{label}</label>
      <input
        id={id}
        ref={ref}
        aria-invalid={!!error}
        aria-describedby={error || hint ? `${id}-note` : undefined}
        className={cx(
          'h-11 w-full rounded-xl border bg-white px-3.5 text-sm shadow-xs outline-none transition placeholder:text-slate-400',
          'focus:border-brand-500 focus:ring-4 focus:ring-brand-100',
          error ? 'border-red-400' : 'border-line',
        )}
        {...props}
      />
      {(error || hint) && (
        <p id={`${id}-note`} className={cx('mt-1.5 text-xs', error ? 'text-red-600' : 'text-muted')}>{error ?? hint}</p>
      )}
    </div>
  )
}

export function Select({ label, error, className, children, ...props }: Omit<FieldProps, 'ref'> & SelectHTMLAttributes<HTMLSelectElement>) {
  const id = useId()
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-slate-700">{label}</label>
      <select
        id={id}
        className="h-11 w-full rounded-xl border border-line bg-white px-3 text-sm outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-100"
        {...props}
      >
        {children}
      </select>
      {error && <p className="mt-1.5 text-xs text-red-600">{error}</p>}
    </div>
  )
}

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return <section className={cx('rounded-2xl border border-line bg-white p-5 shadow-xs', className)}>{children}</section>
}

export function SectionTitle({ title, action }: { title: string; action?: ReactNode }) {
  return (
    <div className="mb-3 flex items-center justify-between gap-3">
      <h2 className="text-base font-semibold">{title}</h2>
      {action}
    </div>
  )
}

export function PageHeader({ title, subtitle, action }: { title: string; subtitle?: string; action?: ReactNode }) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-muted">{subtitle}</p>}
      </div>
      {action}
    </header>
  )
}

type Tone = 'green' | 'amber' | 'red' | 'slate' | 'blue'

export function Badge({ tone = 'slate', children }: { tone?: Tone; children: ReactNode }) {
  return (
    <span
      className={cx(
        'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold',
        tone === 'green' && 'bg-brand-50 text-brand-700',
        tone === 'amber' && 'bg-amber-50 text-amber-700',
        tone === 'red' && 'bg-red-50 text-red-700',
        tone === 'slate' && 'bg-slate-100 text-slate-700',
        tone === 'blue' && 'bg-blue-50 text-blue-700',
      )}
    >
      {children}
    </span>
  )
}

const statusTones: Record<string, Tone> = {
  COMPLETED: 'green', CONFIRMED: 'green', PAID: 'green', ACTIVE: 'green', APPROVED: 'green', CLEARED: 'green',
  WON: 'green', ACCEPTED: 'green', PAID_OFF: 'blue',
  PENDING: 'amber', OPEN: 'amber', BLOCKED: 'amber', PENDING_REVIEW: 'amber', CARRIED: 'amber', FINANCED: 'blue',
  FAILED: 'red', DECLINED: 'red', CANCELLED: 'red', LOST: 'red', REJECTED: 'red', EXPIRED: 'slate', REVERSED: 'slate',
}

export function StatusBadge({ status }: { status: string }) {
  const label = status.replace(/_/g, ' ').toLowerCase()
  return <Badge tone={statusTones[status] ?? 'slate'}>{label.charAt(0).toUpperCase() + label.slice(1)}</Badge>
}

export function Alert({ tone = 'error', children }: { tone?: 'error' | 'info' | 'success' | 'warning'; children: ReactNode }) {
  return (
    <div
      role={tone === 'error' ? 'alert' : 'status'}
      className={cx(
        'rounded-xl border px-4 py-3 text-sm',
        tone === 'error' && 'border-red-200 bg-red-50 text-red-800',
        tone === 'info' && 'border-blue-200 bg-blue-50 text-blue-800',
        tone === 'success' && 'border-brand-200 bg-brand-50 text-brand-900',
        tone === 'warning' && 'border-amber-200 bg-amber-50 text-amber-900',
      )}
    >
      {children}
    </div>
  )
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 py-10 text-sm text-muted" role="status">
      <Loader2 className="size-5 animate-spin" aria-hidden /> {label}…
    </div>
  )
}

export function EmptyState({ icon, title, children }: { icon?: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
      {icon && <div className="mb-1 rounded-2xl bg-brand-50 p-3 text-brand-600">{icon}</div>}
      <p className="font-semibold">{title}</p>
      {children && <div className="max-w-sm text-sm text-muted">{children}</div>}
    </div>
  )
}

export function Amount({ value, direction, className }: { value: number; direction?: 'in' | 'out'; className?: string }) {
  return (
    <span className={cx('font-semibold tabular-nums', direction === 'in' && 'text-brand-700', className)}>
      {direction === 'in' ? '+ ' : direction === 'out' ? '− ' : ''}
      {money(value)}
    </span>
  )
}

export function Modal({ open, title, onClose, children, wide }: {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  wide?: boolean
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (open && !element.open) {
      element.showModal()
      // showModal focuses the first focusable element, the close button; start on the first field instead.
      element.querySelector<HTMLElement>('input, textarea, select')?.focus()
    }
    if (!open && element.open) element.close()
  }, [open])
  return (
    <dialog
      ref={dialog}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
      className={cx(
        'm-auto w-[calc(100%-2rem)] rounded-2xl border border-line p-0 shadow-2xl backdrop:bg-slate-900/40',
        wide ? 'max-w-2xl' : 'max-w-md',
      )}
    >
      {open && (
        <div className="p-6">
          <div className="mb-4 flex items-start justify-between gap-4">
            <h2 className="text-lg font-semibold">{title}</h2>
            <button onClick={onClose} className="rounded-lg p-1 text-muted hover:bg-slate-100" aria-label="Close">
              <X className="size-5" />
            </button>
          </div>
          {children}
        </div>
      )}
    </dialog>
  )
}

export function CopyButton({ value, label = 'Copy' }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <Button
      type="button"
      variant="secondary"
      size="sm"
      onClick={async () => {
        await navigator.clipboard.writeText(value)
        setCopied(true)
        setTimeout(() => setCopied(false), 1500)
      }}
    >
      {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
      {copied ? 'Copied' : label}
    </Button>
  )
}

export function QrCode({ value, size = 220, alt = 'Pix QR code' }: { value: string; size?: number; alt?: string }) {
  const [src, setSrc] = useState<string>()
  useEffect(() => {
    let active = true
    QRCode.toDataURL(value, { width: size, margin: 1 }).then((url) => active && setSrc(url))
    return () => {
      active = false
    }
  }, [value, size])
  return src ? <img src={src} width={size} height={size} alt={alt} className="rounded-xl border border-line" /> : null
}

export function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 py-2 text-sm">
      <dt className="text-muted">{label}</dt>
      <dd className="text-right font-medium">{children}</dd>
    </div>
  )
}

export function Tabs<T extends string>({ value, onChange, tabs }: {
  value: T
  onChange: (value: T) => void
  tabs: { value: T; label: string }[]
}) {
  return (
    <div role="tablist" className="mb-5 inline-flex rounded-xl bg-slate-100 p-1">
      {tabs.map((tab) => (
        <button
          key={tab.value}
          role="tab"
          aria-selected={value === tab.value}
          onClick={() => onChange(tab.value)}
          className={cx(
            'rounded-lg px-4 py-1.5 text-sm font-semibold transition',
            value === tab.value ? 'bg-white text-ink shadow-xs' : 'text-muted hover:text-ink',
          )}
        >
          {tab.label}
        </button>
      ))}
    </div>
  )
}

