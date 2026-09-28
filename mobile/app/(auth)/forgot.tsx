import { errorMessage, isEmail, request } from '@paywallet/core'
import { router } from 'expo-router'
import { useState } from 'react'
import { Button, Field, Notice, Screen, Title } from '../../src/ui'

export default function ForgotPassword() {
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true)
    setError(undefined)
    try {
      await request('/auth/password/forgot', { method: 'POST', auth: false, body: { email: email.trim() } })
      router.push({ pathname: '/reset', params: { email: email.trim() } })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen>
      <Title subtitle="We will email you a 6-digit code to set a new password.">Reset your password</Title>
      <Field label="Email" value={email} onChangeText={setEmail} keyboardType="email-address" autoCapitalize="none" autoComplete="email" />
      {error && <Notice>{error}</Notice>}
      <Button title="Send code" onPress={submit} loading={busy} disabled={!isEmail(email)} />
    </Screen>
  )
}
