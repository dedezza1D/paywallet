import { createBrowserRouter, Navigate, Outlet, RouterProvider, useLocation } from 'react-router'
import { useAuth } from './auth/AuthContext'
import { FeedbackProvider } from './components/feedback'
import { Spinner } from './components/ui'
import AppLayout from './layout/AppLayout'
import ActivityPage from './pages/ActivityPage'
import { ForgotPasswordPage, LoginPage, ResetPasswordPage, SignupPage, VerifyEmailPage } from './pages/auth'
import BillsPage from './pages/BillsPage'
import CardDetailPage from './pages/CardDetailPage'
import CardsPage from './pages/CardsPage'
import EarningsPage from './pages/EarningsPage'
import HomePage from './pages/HomePage'
import LoanDetailPage from './pages/LoanDetailPage'
import LoansPage from './pages/LoansPage'
import MerchantNotice from './pages/MerchantNotice'
import NotFoundPage from './pages/NotFoundPage'
import PayLinkPage from './pages/PayLinkPage'
import PixPage from './pages/PixPage'
import PixPaymentPage from './pages/PixPaymentPage'
import SendPage from './pages/SendPage'
import SettingsPage from './pages/SettingsPage'
import StorePage from './pages/StorePage'

function RequireAuth() {
  const { status, user } = useAuth()
  const location = useLocation()
  if (status === 'loading' || (status === 'signedIn' && !user)) return <Spinner />
  if (status === 'signedOut') return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  if (user!.type === 'MERCHANT') return <MerchantNotice />
  return <Outlet />
}

function GuestOnly() {
  const { status } = useAuth()
  if (status === 'loading') return <Spinner />
  return status === 'signedIn' ? <Navigate to="/" replace /> : <Outlet />
}

const router = createBrowserRouter([
  {
    element: <FeedbackProvider><Outlet /></FeedbackProvider>,
    children: [
      {
        element: <GuestOnly />,
        children: [
          { path: '/login', element: <LoginPage /> },
          { path: '/signup', element: <SignupPage /> },
          { path: '/verify-email', element: <VerifyEmailPage /> },
          { path: '/forgot-password', element: <ForgotPasswordPage /> },
          { path: '/reset-password', element: <ResetPasswordPage /> },
        ],
      },
      { path: '/pay/:token', element: <PayLinkPage /> },
      {
        element: <RequireAuth />,
        children: [
          {
            element: <AppLayout />,
            children: [
              { path: '/', element: <HomePage /> },
              { path: '/activity', element: <ActivityPage /> },
              { path: '/send', element: <SendPage /> },
              { path: '/pix', element: <PixPage /> },
              { path: '/pix/payments/:endToEndId', element: <PixPaymentPage /> },
              { path: '/bills', element: <BillsPage /> },
              { path: '/cards', element: <CardsPage /> },
              { path: '/cards/:id', element: <CardDetailPage /> },
              { path: '/loans', element: <LoansPage /> },
              { path: '/loans/:id', element: <LoanDetailPage /> },
              { path: '/store', element: <StorePage /> },
              { path: '/earnings', element: <EarningsPage /> },
              { path: '/settings', element: <Navigate to="/settings/profile" replace /> },
              { path: '/settings/:section', element: <SettingsPage /> },
            ],
          },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])

export default function App() {
  return <RouterProvider router={router} />
}
