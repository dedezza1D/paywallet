import { ApiError, errorMessage, type MfaChallenge, useAuth } from '@paywallet/core'
import { Link, router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'
import { Text, View } from 'react-native'
import { Button, colors, Field, Notice, Screen, Title } from '../../src/ui'

export default function Login() {
  const { login, completeMfa } = useAuth()
  const params = useLocalSearchParams<{ notice?: string }>()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [challenge, setChallenge] = useState<MfaChallenge>()
  const [code, setCode] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true)
    setError(undefined)
    try {
      if (challenge) {
        await completeMfa(challenge.mfaToken, code.trim())
      } else {
        const mfa = await login(email.trim(), password)
        if (mfa) {
          setChallenge(mfa)
          return
        }
      }
      router.replace('/')
    } catch (e) {
      if (e instanceof ApiError && e.status === 403 && /verif/i.test(e.message)) {
        router.push({ pathname: '/verify', params: { email: email.trim() } })
      } else if (challenge && e instanceof ApiError && e.status === 401) {
        setError('Invalid code. After 5 wrong codes, sign in again.')
      } else {
        setError(errorMessage(e))
      }
    } finally {
      setBusy(false)
    }
  }

  if (challenge) {
    return (
      <Screen>
        <Title subtitle="Enter the 6-digit code from your authenticator app, or a recovery code.">Two-factor authentication</Title>
        <Field label="Code" value={code} onChangeText={setCode} autoFocus autoCapitalize="characters" textContentType="oneTimeCode" />
        {error && <Notice>{error}</Notice>}
        <Button title="Verify" onPress={submit} loading={busy} disabled={!code.trim()} />
        <Button title="Use another account" variant="ghost" onPress={() => setChallenge(undefined)} style={{ marginTop: 8 }} />
      </Screen>
    )
  }

  return (
    <Screen>
      <View style={{ marginTop: 48 }}>
        <Text style={{ fontSize: 34, fontWeight: '800', color: colors.brand, marginBottom: 32 }}>PayWallet</Text>
        <Title>Welcome back</Title>
      </View>
      {params.notice && <Notice tone="success">{params.notice}</Notice>}
      <Field label="Email" value={email} onChangeText={setEmail} keyboardType="email-address" autoCapitalize="none" autoComplete="email" textContentType="username" />
      <Field label="Password" value={password} onChangeText={setPassword} secureTextEntry autoComplete="current-password" textContentType="password" />
      {error && <Notice>{error}</Notice>}
      <Button title="Sign in" onPress={submit} loading={busy} disabled={!email || !password} />
      <View style={{ marginTop: 20, gap: 14, alignItems: 'center' }}>
        <Link href="/forgot" style={{ color: colors.brand, fontWeight: '600' }}>Forgot your password?</Link>
        <Link href="/signup" style={{ color: colors.brand, fontWeight: '600' }}>Create an account</Link>
      </View>
    </Screen>
  )
}
