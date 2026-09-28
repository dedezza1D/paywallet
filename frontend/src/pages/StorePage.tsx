import { Gift, Smartphone } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import {
  dateTime, money, newIdempotencyKey, onlyDigits, useCashback, useOrder, useOrders, useProducts, usePurchase,
} from '@paywallet/core'
import type { Product } from '@paywallet/core'
import { actionError, usePin } from '../components/feedback'
import {
  Alert, Badge, Button, Card, CopyButton, EmptyState, Input, Modal, PageHeader, SectionTitle, Spinner, StatusBadge,
} from '../components/ui'
import { cx } from '../lib/cx'

export default function StorePage() {
  const products = useProducts()
  const cashback = useCashback()
  const [buying, setBuying] = useState<Product>()
  const [orderId, setOrderId] = useState<string>()

  const byCategory = (category: Product['category']) => products.data?.filter((p) => p.category === category) ?? []

  return (
    <>
      <PageHeader
        title="Store"
        subtitle="Gift cards and phone top-ups, paid from your balance, with cashback."
        action={<Badge tone="green">{money(cashback.data?.total)} cashback earned</Badge>}
      />
      {products.isPending ? <Spinner /> : (
        <div className="space-y-8">
          {([['GIFT_CARD', 'Gift cards', Gift], ['MOBILE_RECHARGE', 'Phone top-up', Smartphone]] as const).map(([category, title, Icon]) => (
            <section key={category}>
              <h2 className="mb-3 text-base font-semibold">{title}</h2>
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
                {byCategory(category).map((product) => (
                  <button
                    key={product.id}
                    onClick={() => setBuying(product)}
                    className="flex flex-col items-start gap-3 rounded-2xl border border-line bg-white p-4 text-left shadow-xs transition hover:-translate-y-0.5 hover:border-brand-200 hover:shadow-md"
                  >
                    <div className="rounded-xl bg-brand-50 p-2.5 text-brand-600"><Icon className="size-5" /></div>
                    <div>
                      <p className="font-semibold">{product.brand}</p>
                      <p className="text-xs text-muted">{product.name}</p>
                    </div>
                    {product.cashbackPercent > 0 && <Badge tone="green">{product.cashbackPercent}% back</Badge>}
                  </button>
                ))}
              </div>
            </section>
          ))}
          <Orders onOpen={setOrderId} />
        </div>
      )}
      {buying && <BuyDialog product={buying} onClose={() => setBuying(undefined)} onOrdered={(id) => { setBuying(undefined); setOrderId(id) }} />}
      {orderId && <OrderDialog id={orderId} onClose={() => setOrderId(undefined)} />}
    </>
  )
}

function BuyDialog({ product, onClose, onOrdered }: { product: Product; onClose: () => void; onOrdered: (id: string) => void }) {
  const purchase = usePurchase()
  const withPin = usePin()
  const [value, setValue] = useState(product.values[0])
  const [phone, setPhone] = useState('')
  const [error, setError] = useState<string>()
  const recharge = product.category === 'MOBILE_RECHARGE'
  const phoneDigits = onlyDigits(phone)
  const phoneOk = /^\d{2}9\d{8}$/.test(phoneDigits)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError(undefined)
    const idempotencyKey = newIdempotencyKey()
    try {
      const order = await withPin({
        title: `Buy ${product.brand}`,
        summary: <p className="text-sm text-muted">Pay <strong className="text-ink">{money(value)}</strong> from your balance.</p>,
        run: (pin) => purchase.mutateAsync({ pin, idempotencyKey, productId: product.id, value, phoneNumber: recharge ? phoneDigits : undefined }),
      })
      onOrdered(order.id)
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <Modal open title={`${product.brand} · ${product.name}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <fieldset>
          <legend className="mb-2 text-sm font-medium text-slate-700">Value</legend>
          <div className="grid grid-cols-3 gap-2">
            {product.values.map((v) => (
              <button
                type="button"
                key={v}
                onClick={() => setValue(v)}
                aria-pressed={value === v}
                className={cx(
                  'rounded-xl border py-2.5 text-sm font-semibold transition',
                  value === v ? 'border-brand-500 bg-brand-50 text-brand-700' : 'border-line hover:border-brand-200',
                )}
              >
                {money(v)}
              </button>
            ))}
          </div>
        </fieldset>
        {recharge && (
          <Input
            label="Phone number"
            inputMode="tel"
            placeholder="11 98765 4321"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            error={phone && !phoneOk ? 'Area code followed by a 9-digit mobile number.' : undefined}
          />
        )}
        {product.cashbackPercent > 0 && (
          <p className="text-sm text-brand-700">You get {money((value * product.cashbackPercent) / 100)} back.</p>
        )}
        {error && <Alert>{error}</Alert>}
        <Button type="submit" className="w-full" disabled={recharge && !phoneOk}>Buy for {money(value)}</Button>
      </form>
    </Modal>
  )
}

function OrderDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const order = useOrder(id)
  const o = order.data
  return (
    <Modal open title="Your order" onClose={onClose}>
      {!o ? <Spinner /> : (
        <div className="space-y-4 text-center">
          <p className="text-sm text-muted">{o.productName}</p>
          <p className="text-2xl font-bold tabular-nums">{money(o.value)}</p>
          <StatusBadge status={o.status} />
          {o.status === 'PENDING' && <p className="text-sm text-muted">Waiting for the provider…</p>}
          {o.voucherCode && (
            <div className="rounded-xl bg-brand-50 p-4">
              <p className="text-xs uppercase tracking-wide text-brand-700">Your code</p>
              <p className="my-2 font-mono text-lg font-bold tracking-wider">{o.voucherCode}</p>
              <CopyButton value={o.voucherCode} label="Copy code" />
            </div>
          )}
          {o.status === 'COMPLETED' && o.cashback > 0 && <Alert tone="success">{money(o.cashback)} cashback added to your balance.</Alert>}
          {o.failureReason && <Alert>{o.failureReason}. You were refunded.</Alert>}
        </div>
      )}
    </Modal>
  )
}

function Orders({ onOpen }: { onOpen: (id: string) => void }) {
  const orders = useOrders()
  return (
    <Card>
      <SectionTitle title="Your orders" />
      {orders.isPending ? <Spinner /> : orders.data?.content.length ? (
        <ul className="divide-y divide-line">
          {orders.data.content.map((o) => (
            <li key={o.id}>
              <button onClick={() => onOpen(o.id)} className="-mx-2 flex w-[calc(100%+1rem)] items-center gap-3 rounded-xl px-2 py-3 text-left hover:bg-slate-50">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold">{o.productName}</p>
                  <p className="text-xs text-muted">{dateTime(o.createdAt)}{o.phoneNumber ? ` · ${o.phoneNumber}` : ''}</p>
                </div>
                <span className="text-sm font-semibold tabular-nums">{money(o.value)}</span>
                <StatusBadge status={o.status} />
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <EmptyState title="No orders yet" />
      )}
    </Card>
  )
}
