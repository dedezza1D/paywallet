import { useAuth } from '@paywallet/core'
import { Redirect, Stack } from 'expo-router'
import { Loading } from '../../src/ui'

export default function AuthLayout() {
  const { status } = useAuth()
  if (status === 'loading') return <Loading />
  if (status === 'signedIn') return <Redirect href="/" />
  return <Stack screenOptions={{ headerShadowVisible: false, headerTitle: '', headerBackTitle: 'Back' }} />
}
