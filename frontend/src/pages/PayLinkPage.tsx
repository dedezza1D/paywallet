import { CheckCircle2, Store } from 'lucide-react'
import { useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { dateTime, errorMessage, money, useAuth, usePayCharge, usePublicCharge } from '@paywallet/core'
import type { PaymentReceipt } from '@paywallet/core'
import { actionError, usePin } from '../components/feedback'
import { Alert, Button, Card, CopyButton, QrCode, Row, Spinner, StatusBadge } from '../components/ui'
import { Logo } from '../layout/AppLayout'

/** The page behind a merchant's payment link: anyone can see it; paying with the wallet needs a signed-in user. */
export default function PayLinkPage() {
  const { token = '' } = useParams()
  const location = useLocation()
  const { status, user } = useAuth()
  const charge = usePublicCharge(token)
  const pay = usePayCharge(token)
  const withPin = usePin()
  const [receipt, setReceipt] = useState<PaymentReceipt>()
  const [error, setError] = useState<string>()

  const payWithWallet = async () => {
    setError(undefined)
    try {
      setReceipt(await withPin({
        title: `Pay ${charge.data?.merchantName}`,
        summary: <p className="text-sm text-muted">Pay <strong className="text-ink">{money(charge.data?.value)}</strong> from your balance.</p>,
        run: (pin) => pay.mutateAsync({ pin }),
      }))
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <div className="flex min-h-screen flex-col items-center bg-canvas px-4 py-10">
      <Link to="/" className="mb-8"><Logo /></Link>
      <Card className="w-full max-w-md">
        {charge.isPending ? <Spinner /> : charge.error ? (
          <Alert>{errorMessage(charge.error)}</Alert>
        ) : receipt ? (
          <div className="text-center">
            <CheckCircle2 className="mx-auto size-14 text-brand-500" />
            <h1 className="mt-3 text-xl font-bold">Payment done</h1>
            <p className="mt-1 text-3xl font-bold tabular-nums">{money(receipt.value)}</p>
            <dl className="mt-6 divide-y divide-line text-left">
              <Row label="To">{receipt.merchantName}</Row>
              <Row label="When">{dateTime(receipt.paidAt)}</Row>
              <Row label="Transaction"><span className="font-mono text-xs">{receipt.transactionId}</span></Row>
            </dl>
            <Link to="/"><Button variant="secondary" className="mt-6 w-full">Go to my account</Button></Link>
          </div>
        ) : (
          <div className="space-y-5 text-center">
            <div className="mx-auto grid size-14 place-items-center rounded-2xl bg-brand-50 text-brand-600"><Store className="size-7" /></div>
            <div>
              <p className="text-sm text-muted">{charge.data.merchantName} is requesting</p>
              <p className="text-4xl font-bold tabular-nums">{money(charge.data.value)}</p>
              {charge.data.description && <p className="mt-1 text-sm">{charge.data.description}</p>}
              <div className="mt-2"><StatusBadge status={charge.data.status} /></div>
            </div>
            {charge.data.status === 'PENDING' ? (
              <>
                {status === 'signedIn' && user?.type === 'COMMON' ? (
                  <Button size="lg" className="w-full" onClick={payWithWallet} loading={pay.isPending}>Pay with PayWallet</Button>
                ) : (
                  <Link to="/login" state={{ from: location.pathname }}>
                    <Button size="lg" className="w-full">Sign in to pay with PayWallet</Button>
                  </Link>
                )}
                {error && <Alert>{error}</Alert>}
                {charge.data.brCode && (
                  <div className="space-y-3 border-t border-line pt-5">
                    <p className="text-sm text-muted">Or pay with Pix from any bank</p>
                    <div className="flex justify-center"><QrCode value={charge.data.brCode} size={180} /></div>
                    <CopyButton value={charge.data.brCode} label="Copy Pix code" />
                  </div>
                )}
                <p className="text-xs text-muted">Expires {dateTime(charge.data.expiresAt)}</p>
              </>
            ) : (
              <Alert tone="info">This payment link is no longer open.</Alert>
            )}
          </div>
        )}
      </Card>
    </div>
  )
}
