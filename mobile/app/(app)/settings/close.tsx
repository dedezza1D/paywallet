import { closeAccount, errorMessage, useClosureCheck, useUser } from '@paywallet/core'
import { router, Stack } from 'expo-router'
import { useState } from 'react'
import { Text } from 'react-native'
import { disableBiometricPin } from '../../../src/pin'
import { setAppLock } from '../../../src/lock'
import { Button, colors, Field, Loading, Notice, Screen } from '../../../src/ui'

export default function CloseAccount() {
  const user = useUser()
  const check = useClosureCheck()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true)
    setError(undefined)
    try {
      await closeAccount({ password, code: code.trim() || undefined })
      await disableBiometricPin().catch(() => undefined)
      await setAppLock(false).catch(() => undefined)
      router.replace({ pathname: '/login', params: { notice: 'Your account is closed. Thank you for using PayWallet.' } })
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  return (
    <Screen>
      <Stack.Screen options={{ title: 'Close account' }} />
      <Text style={{ color: colors.muted, marginBottom: 16, lineHeight: 20 }}>
        Closing is permanent: you will not be able to sign in again. Your transaction history is kept for the periods the
        law requires.
      </Text>
      {check.isPending ? <Loading /> : check.data && !check.data.closable ? (
        <Notice tone="info">{`Before closing:\n${check.data.blockers.map((b) => `• ${b}`).join('\n')}`}</Notice>
      ) : (
        <>
          <Field label="Account password" value={password} onChangeText={setPassword} secureTextEntry textContentType="password" />
          {user.twoFactorEnabled && (
            <Field label="Code from the app or a recovery code" value={code} onChangeText={setCode} autoCapitalize="characters" />
          )}
          {error && <Notice>{error}</Notice>}
          <Button
            title="Close permanently"
            variant="danger"
            onPress={submit}
            loading={busy}
            disabled={!password || (user.twoFactorEnabled && !code.trim())}
          />
        </>
      )}
    </Screen>
  )
}
