import { errorMessage, onlyDigits, request, useUser } from '@paywallet/core'
import { useQueryClient } from '@tanstack/react-query'
import { router, Stack, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'
import { biometricPinEnabled, enableBiometricPin } from '../../../src/pin'
import { Button, Field, Notice, Screen } from '../../../src/ui'

const digits6 = (value: string) => onlyDigits(value).slice(0, 6)

export default function PinSettings() {
  const { biometric } = useLocalSearchParams<{ biometric?: string }>()
  return biometric ? <SavePinForBiometrics /> : <ChangePin />
}

/** Stores the current PIN behind the device biometrics, so payments are confirmed with Face ID or a fingerprint. */
function SavePinForBiometrics() {
  const [pin, setPin] = useState('')
  const [error, setError] = useState<string>()

  return (
    <Screen>
      <Stack.Screen options={{ title: 'Confirm with biometrics' }} />
      <Notice tone="info">
        Your PIN stays on this device, in the secure storage unlocked only by your biometrics. It is never sent anywhere
        except to confirm a payment.
      </Notice>
      <Field label="Your transaction PIN" value={pin} onChangeText={(v) => setPin(digits6(v))} keyboardType="number-pad" secureTextEntry autoFocus />
      {error && <Notice>{error}</Notice>}
      <Button
        title="Turn on"
        disabled={pin.length !== 6}
        onPress={async () => {
          try {
            await enableBiometricPin(pin)
            router.back()
          } catch (e) {
            setError(errorMessage(e))
          }
        }}
      />
    </Screen>
  )
}

function ChangePin() {
  const user = useUser()
  const queryClient = useQueryClient()
  const [password, setPassword] = useState('')
  const [pin, setPin] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true)
    setError(undefined)
    try {
      await request('/auth/pin', { method: 'POST', body: { password, pin } })
      if (await biometricPinEnabled()) await enableBiometricPin(pin)
      await queryClient.invalidateQueries({ queryKey: ['me'] })
      router.back()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen>
      <Stack.Screen options={{ title: user.transactionPinSet ? 'Change PIN' : 'Create PIN' }} />
      <Notice tone="info">Six digits that confirm every payment. Avoid sequences and repeated digits such as 123456.</Notice>
      <Field label="Account password" value={password} onChangeText={setPassword} secureTextEntry textContentType="password" />
      <Field label="New PIN" value={pin} onChangeText={(v) => setPin(digits6(v))} keyboardType="number-pad" secureTextEntry />
      <Field
        label="Repeat PIN"
        value={confirm}
        onChangeText={(v) => setConfirm(digits6(v))}
        keyboardType="number-pad"
        secureTextEntry
        error={confirm.length === 6 && confirm !== pin ? 'PINs do not match.' : undefined}
      />
      {error && <Notice>{error}</Notice>}
      <Button title="Save PIN" onPress={submit} loading={busy} disabled={!password || pin.length !== 6 || confirm !== pin} />
    </Screen>
  )
}
