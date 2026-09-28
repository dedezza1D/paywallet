import {
  ArrowLeftRight, CreditCard, FileText, HandCoins, History, Home, LogOut, type LucideIcon, Menu, QrCode, Settings, ShoppingBag,
  TrendingUp, X,
} from 'lucide-react'
import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router'
import { initials, useAuth, useUser } from '@paywallet/core'
import { cx } from '../lib/cx'

type Item = { to: string; label: string; icon: LucideIcon; mobile?: boolean }

const items: Item[] = [
  { to: '/', label: 'Home', icon: Home, mobile: true },
  { to: '/pix', label: 'Pix', icon: QrCode, mobile: true },
  { to: '/send', label: 'Transfer', icon: ArrowLeftRight },
  { to: '/bills', label: 'Pay bills', icon: FileText },
  { to: '/cards', label: 'Cards', icon: CreditCard, mobile: true },
  { to: '/loans', label: 'Loans', icon: HandCoins },
  { to: '/store', label: 'Store', icon: ShoppingBag, mobile: true },
  { to: '/earnings', label: 'Earnings', icon: TrendingUp },
  { to: '/activity', label: 'Activity', icon: History },
  { to: '/settings', label: 'Settings', icon: Settings, mobile: true },
]

export function Logo() {
  return (
    <div className="flex items-center gap-2 font-bold tracking-tight">
      <img src="/favicon.svg" alt="" className="size-8" />
      <span className="text-lg">PayWallet</span>
    </div>
  )
}

export default function AppLayout() {
  const user = useUser()
  const { logout } = useAuth()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="min-h-screen md:grid md:grid-cols-[240px_1fr]">
      <aside className="sticky top-0 hidden h-screen flex-col border-r border-line bg-white px-4 py-6 md:flex">
        <div className="mb-8 px-2"><Logo /></div>
        <nav className="flex flex-1 flex-col gap-1" aria-label="Main">
          {items.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) => cx(
                'flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition',
                isActive ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-50 hover:text-ink',
              )}
            >
              <Icon className="size-5" /> {label}
            </NavLink>
          ))}
        </nav>
        <div className="flex items-center gap-3 border-t border-line pt-4">
          <div className="grid size-9 place-items-center rounded-full bg-brand-100 text-sm font-bold text-brand-700">
            {initials(user.fullName)}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold">{user.fullName}</p>
            <p className="truncate text-xs text-muted">{user.email}</p>
          </div>
          <button
            onClick={async () => {
              await logout()
              navigate('/login')
            }}
            className="rounded-lg p-2 text-muted hover:bg-slate-100 hover:text-ink"
            aria-label="Sign out"
            title="Sign out"
          >
            <LogOut className="size-4" />
          </button>
        </div>
      </aside>

      <div className="min-w-0">
        <header className="sticky top-0 z-10 flex items-center justify-between border-b border-line bg-white/90 px-4 py-3 backdrop-blur md:hidden">
          <Logo />
          <button
            onClick={() => setMenuOpen(!menuOpen)}
            className="rounded-lg p-2 text-muted"
            aria-label={menuOpen ? 'Close menu' : 'Open menu'}
            aria-expanded={menuOpen}
          >
            {menuOpen ? <X className="size-5" /> : <Menu className="size-5" />}
          </button>
        </header>
        {menuOpen && (
          <nav className="fixed inset-x-0 top-[57px] z-10 border-b border-line bg-white px-4 pb-4 shadow-lg md:hidden" aria-label="All sections">
            {items.map(({ to, label, icon: Icon }) => (
              <NavLink key={to} to={to} end={to === '/'} onClick={() => setMenuOpen(false)} className="flex items-center gap-3 border-b border-line py-3 text-sm font-medium last:border-0">
                <Icon className="size-5 text-brand-600" /> {label}
              </NavLink>
            ))}
            <button
              onClick={async () => {
                await logout()
                navigate('/login')
              }}
              className="flex w-full items-center gap-3 py-3 text-sm font-medium text-red-600"
            >
              <LogOut className="size-5" /> Sign out
            </button>
          </nav>
        )}
        <main className="mx-auto max-w-5xl px-4 pb-28 pt-6 md:px-8 md:pb-12 md:pt-10">
          <Outlet />
        </main>
      </div>

      <nav className="fixed inset-x-0 bottom-0 z-20 grid grid-cols-5 border-t border-line bg-white md:hidden" aria-label="Main">
        {items.filter((item) => item.mobile).map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            className={({ isActive }) => cx(
              'flex flex-col items-center gap-1 py-2.5 text-[11px] font-medium',
              isActive ? 'text-brand-700' : 'text-slate-500',
            )}
          >
            <Icon className="size-5" /> {label}
          </NavLink>
        ))}
      </nav>
    </div>
  )
}
