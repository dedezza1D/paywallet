import { Link } from 'react-router'
import { Button } from '../components/ui'
import { Logo } from '../layout/AppLayout'

export default function NotFoundPage() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-6 px-4 text-center">
      <Logo />
      <div>
        <p className="text-5xl font-bold text-brand-600">404</p>
        <p className="mt-2 text-muted">This page does not exist.</p>
      </div>
      <Link to="/"><Button>Go home</Button></Link>
    </div>
  )
}
