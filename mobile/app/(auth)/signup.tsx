import { errorMessage, formatCpf, isEmail, isValidCpf, onlyDigits, passwordProblem, request } from '@paywallet/core'
import { router } from 'expo-router'
import { useState } from 'react'
import { Pressable, Text, View } from 'react-native'
import { LegalLink } from '../../src/legal'
import { Button, colors, Field, Notice, Screen, Title } from '../../src/ui'

export default function Signup() {
  const [form, setForm] = useState({ fullName: '', document: '', email: '', password: '' })
  const [acceptedTerms, setAcceptedTerms] = useState(false)
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const set = (field: keyof typeof form) => (value: string) => setForm({ ...form, [field]: value })

  const problems = {
    fullName: form.fullName.trim().split(/\s+/).length < 2 ? 'Enter your full name.' : undefined,
    document: isValidCpf(form.document) ? undefined : 'Enter a valid CPF.',
    email: isEmail(form.email) ? undefined : 'Enter a valid email.',
    password: passwordProblem(form.password),
    terms: acceptedTerms ? undefined : 'Accept the terms to continue.',
  }
  const shown = (field: keyof typeof problems) => (touched ? problems[field] : undefined)

  const submit = async () => {
    setTouched(true)
    if (Object.values(problems).some(Boolean)) return
    setBusy(true)
    setError(undefined)
    try {
      await request('/users', {
        method: 'POST',
        auth: false,
        body: {
          fullName: form.fullName.trim(),
          document: onlyDigits(form.document),
          email: form.email.trim(),
          password: form.password,
          type: 'COMMON',
          acceptedTerms,
        },
      })
      router.replace({ pathname: '/verify', params: { email: form.email.trim(), created: '1' } })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen>
      <Title subtitle="It takes a minute. You need your CPF.">Create your account</Title>
      <Field label="Full name" value={form.fullName} onChangeText={set('fullName')} autoComplete="name" error={shown('fullName')} />
      <Field
        label="CPF"
        value={form.document}
        onChangeText={(v) => setForm({ ...form, document: formatCpf(v) })}
        keyboardType="number-pad"
        placeholder="000.000.000-00"
        error={shown('document')}
      />
      <Field
        label="Email"
        value={form.email}
        onChangeText={set('email')}
        keyboardType="email-address"
        autoCapitalize="none"
        autoComplete="email"
        error={shown('email')}
      />
      <Field
        label="Password"
        value={form.password}
        onChangeText={set('password')}
        secureTextEntry
        autoComplete="new-password"
        textContentType="newPassword"
        error={shown('password')}
        hint="At least 12 characters. A passphrase works well."
      />
      <Pressable
        accessibilityRole="checkbox"
        accessibilityLabel="I accept the terms of use and the privacy policy"
        accessibilityState={{ checked: acceptedTerms }}
        onPress={() => setAcceptedTerms(!acceptedTerms)}
        style={{ flexDirection: 'row', gap: 12, marginBottom: 6 }}
      >
        <View
          style={{
            width: 22, height: 22, borderRadius: 6, borderWidth: 2, borderColor: colors.brand, alignItems: 'center',
            justifyContent: 'center', backgroundColor: acceptedTerms ? colors.brand : colors.white,
          }}
        >
          {acceptedTerms && <Text style={{ color: colors.white, fontWeight: '800' }}>✓</Text>}
        </View>
        <Text style={{ flex: 1, color: '#334155', lineHeight: 20 }}>
          I have read and accept the <LegalLink document="terms">terms of use</LegalLink> and the{' '}
          <LegalLink document="privacy">privacy policy</LegalLink>.
        </Text>
      </Pressable>
      {shown('terms') && <Text style={{ color: colors.danger, fontSize: 12, marginBottom: 10 }}>{shown('terms')}</Text>}
      <View style={{ height: 10 }} />
      {error && <Notice>{error}</Notice>}
      <Button title="Create account" onPress={submit} loading={busy} />
    </Screen>
  )
}
