import { errorMessage, isEmail, onlyDigits, request } from '@paywallet/core'
import { router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'
import { Button, Field, Notice, Screen, Title } from '../../src/ui'

export default function VerifyEmail() {
  const params = useLocalSearchParams<{ email?: string; created?: string }>()
  const [email, setEmail] = useState(params.email ?? '')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState(params.created ? 'Account created. We sent a 6-digit code to your email.' : undefined)
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true)
    setError(undefined)
    try {
      await request('/auth/email/verify', { method: 'POST', auth: false, body: { email: email.trim(), code } })
      router.dismissTo({ pathname: '/login', params: { notice: 'Email confirmed. Sign in to continue.' } })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const resend = async () => {
    setError(undefined)
    try {
      await request('/auth/email/verification-code', { method: 'POST', auth: false, body: { email: email.trim() } })
      setNotice('If the email is registered and not confirmed yet, a new code is on its way.')
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <Screen>
      <Title subtitle="Enter the 6-digit code we sent you. It expires in 15 minutes.">Confirm your email</Title>
      {notice && <Notice tone="success">{notice}</Notice>}
      <Field label="Email" value={email} onChangeText={setEmail} keyboardType="email-address" autoCapitalize="none" />
      <Field
        label="Code"
        value={code}
        onChangeText={(v) => setCode(onlyDigits(v).slice(0, 6))}
        keyboardType="number-pad"
        textContentType="oneTimeCode"
      />
      {error && <Notice>{error}</Notice>}
      <Button title="Confirm email" onPress={submit} loading={busy} disabled={code.length !== 6 || !isEmail(email)} />
      <Button title="Send a new code" variant="ghost" onPress={resend} disabled={!isEmail(email)} style={{ marginTop: 8 }} />
    </Screen>
  )
}
