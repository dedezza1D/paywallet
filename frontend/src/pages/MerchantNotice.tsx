import { Store } from 'lucide-react'
import { useAuth } from '../auth/AuthContext'
import { Button, Card } from '../components/ui'
import { Logo } from '../layout/AppLayout'

export default function MerchantNotice() {
  const { logout } = useAuth()
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-8 px-4">
      <Logo />
      <Card className="max-w-md text-center">
        <Store className="mx-auto size-10 text-brand-600" />
        <h1 className="mt-3 text-xl font-bold">Merchant account</h1>
        <p className="mt-2 text-sm text-muted">
          This app is for personal accounts. The merchant portal (charges, payment links and sales dashboard) is on its
          way; meanwhile, use the API.
        </p>
        <Button variant="secondary" className="mt-6" onClick={() => void logout()}>Sign out</Button>
      </Card>
    </div>
  )
}
