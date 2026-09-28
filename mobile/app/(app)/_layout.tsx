import { useAuth } from '@paywallet/core'
import { Redirect, Stack } from 'expo-router'
import { useEffect } from 'react'
import { Text, View } from 'react-native'
import { useAppLock } from '../../src/lock'
import { Button, colors, Loading, Screen, Title } from '../../src/ui'

export default function SignedInLayout() {
  const { status, user, logout } = useAuth()
  const { locked, unlock } = useAppLock(status === 'signedIn')

  useEffect(() => {
    if (locked) void unlock()
    // Prompt once when the lock engages; the button below retries.
  }, [locked])

  if (status === 'loading' || (status === 'signedIn' && (!user || locked === null))) return <Loading />
  if (status === 'signedOut') return <Redirect href="/login" />
  if (user!.type === 'MERCHANT') {
    return (
      <Screen>
        <Title subtitle="This app is for personal accounts. The merchant portal is on its way.">Merchant account</Title>
        <Button title="Sign out" variant="secondary" onPress={() => void logout()} />
      </Screen>
    )
  }
  if (locked) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32, backgroundColor: colors.canvas, gap: 16 }}>
        <Text style={{ fontSize: 30, fontWeight: '800', color: colors.brand }}>PayWallet</Text>
        <Text style={{ color: colors.muted, textAlign: 'center' }}>Unlock with Face ID or your fingerprint to continue.</Text>
        <Button title="Unlock" onPress={() => void unlock()} style={{ alignSelf: 'stretch' }} />
        <Button title="Sign out" variant="ghost" onPress={() => void logout()} />
      </View>
    )
  }
  return (
    <Stack screenOptions={{ headerShadowVisible: false, headerBackTitle: 'Back', headerTintColor: colors.brand }}>
      <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
    </Stack>
  )
}
