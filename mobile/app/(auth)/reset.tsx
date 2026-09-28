import { errorMessage, onlyDigits, passwordProblem, request } from '@paywallet/core'
import { router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'
import { Button, Field, Notice, Screen, Title } from '../../src/ui'

export default function ResetPassword() {
  const params = useLocalSearchParams<{ email?: string }>()
  const [email, setEmail] = useState(params.email ?? '')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const problem = password ? passwordProblem(password) : undefined

  const submit = async () => {
    setBusy(true)
    setError(undefined)
    try {
      await request('/auth/password/reset', {
        method: 'POST', auth: false, body: { email: email.trim(), code, newPassword: password },
      })
      router.dismissTo({ pathname: '/login', params: { notice: 'Password changed. Sign in with your new password.' } })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen>
      <Title subtitle="If the email is registered, the code is in your inbox. Every signed-in device will be signed out.">
        Choose a new password
      </Title>
      <Field label="Email" value={email} onChangeText={setEmail} keyboardType="email-address" autoCapitalize="none" />
      <Field
        label="Code"
        value={code}
        onChangeText={(v) => setCode(onlyDigits(v).slice(0, 6))}
        keyboardType="number-pad"
        textContentType="oneTimeCode"
      />
      <Field label="New password" value={password} onChangeText={setPassword} secureTextEntry textContentType="newPassword" error={problem} />
      {error && <Notice>{error}</Notice>}
      <Button title="Set new password" onPress={submit} loading={busy} disabled={code.length !== 6 || !password || !!problem} />
    </Screen>
  )
}
