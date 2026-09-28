import { ApiError, AuthProvider, session } from '@paywallet/core'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Stack } from 'expo-router'
import { StatusBar } from 'expo-status-bar'
import { SafeAreaProvider } from 'react-native-safe-area-context'
import { PinProvider } from '../src/pin'
import { installCryptoPolyfill, mobilePlatform } from '../src/platform'

installCryptoPolyfill()
session.configure(mobilePlatform)

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      // Client errors (not found, forbidden, validation) will not change on a retry.
      retry: (failures, error) => failures < 2 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
    },
  },
})

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <PinProvider>
            <StatusBar style="dark" />
            <Stack screenOptions={{ headerShown: false }} />
          </PinProvider>
        </AuthProvider>
      </QueryClientProvider>
    </SafeAreaProvider>
  )
}
