import {
  createQrCode, dateTime, errorMessage, type KeyOwner, lookupPixKey, money, newIdempotencyKey, parseAmount, usePixKeys,
  usePixPayments, useRegisterPixKey, useSendPix, useUser,
} from '@paywallet/core'
import { router } from 'expo-router'
import { useState } from 'react'
import { Pressable, Text, View } from 'react-native'
import { actionError, usePin } from '../../../src/pin'
import {
  Button, Card, colors, CopyButton, Empty, Field, Loading, Money, Notice, QrCode, Screen, StatusBadge, styles,
} from '../../../src/ui'

type Tab = 'send' | 'receive' | 'history'

export default function Pix() {
  const [tab, setTab] = useState<Tab>('send')
  return (
    <Screen>
      <View style={{ flexDirection: 'row', backgroundColor: '#e2e8f0', borderRadius: 14, padding: 4, marginBottom: 18 }} accessibilityRole="tablist">
        {(['send', 'receive', 'history'] as const).map((value) => (
          <Pressable
            key={value}
            accessibilityRole="tab"
            accessibilityState={{ selected: tab === value }}
            onPress={() => setTab(value)}
            style={{ flex: 1, paddingVertical: 9, borderRadius: 10, alignItems: 'center', backgroundColor: tab === value ? colors.white : 'transparent' }}
          >
            <Text style={{ fontWeight: '700', color: tab === value ? colors.ink : colors.muted }}>
              {value === 'send' ? 'Send' : value === 'receive' ? 'Receive' : 'History'}
            </Text>
          </Pressable>
        ))}
      </View>
      {tab === 'send' && <SendPix />}
      {tab === 'receive' && <ReceivePix />}
      {tab === 'history' && <History />}
    </Screen>
  )
}

function SendPix() {
  const [byCode, setByCode] = useState(false)
  const [key, setKey] = useState('')
  const [brCode, setBrCode] = useState('')
  const [owner, setOwner] = useState<KeyOwner>()
  const [amount, setAmount] = useState('')
  const [error, setError] = useState<string>()
  const [looking, setLooking] = useState(false)
  const send = useSendPix()
  const withPin = usePin()
  const value = parseAmount(amount)

  const lookup = async () => {
    setError(undefined)
    setLooking(true)
    try {
      setOwner(await lookupPixKey(key.trim()))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setLooking(false)
    }
  }

  const pay = async () => {
    setError(undefined)
    const idempotencyKey = newIdempotencyKey()
    try {
      const payment = await withPin({
        title: 'Confirm Pix',
        summary: byCode ? 'Pay this Pix code.' : `Send ${money(value)} to ${owner?.name}.`,
        run: (pin) => send.mutateAsync(byCode
          ? { pin, idempotencyKey, brCode: brCode.trim(), value: value ?? undefined }
          : { pin, idempotencyKey, key: key.trim(), value: value! }),
      })
      setOwner(undefined)
      setKey('')
      setAmount('')
      setBrCode('')
      router.push(`/pix/${payment.endToEndId}`)
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <Card>
      <Button title={byCode ? 'Use a Pix key instead' : 'Paste a Pix code instead'} variant="ghost" onPress={() => { setByCode(!byCode); setError(undefined) }} style={{ alignSelf: 'flex-start', marginBottom: 8 }} />
      {byCode ? (
        <>
          <Field label="Pix code (copy and paste)" value={brCode} onChangeText={setBrCode} multiline autoCapitalize="none" style={[styles.input, { minHeight: 100, paddingTop: 12 }]} />
          <Field label="Amount (only if the code has none)" value={amount} onChangeText={setAmount} keyboardType="decimal-pad" placeholder="0,00" />
          {error && <Notice>{error}</Notice>}
          <Button title="Continue" onPress={pay} disabled={!brCode.trim()} />
        </>
      ) : !owner ? (
        <>
          <Field label="Pix key" value={key} onChangeText={setKey} autoCapitalize="none" placeholder="CPF, email, phone (+55...) or random key" />
          {error && <Notice>{error}</Notice>}
          <Button title="Continue" onPress={lookup} loading={looking} disabled={!key.trim()} />
        </>
      ) : (
        <>
          <View style={{ backgroundColor: '#f8fafc', borderRadius: 14, padding: 14, marginBottom: 14 }}>
            <Text style={{ color: colors.muted, fontSize: 12, fontWeight: '600' }}>SENDING TO</Text>
            <Text style={{ fontWeight: '700', fontSize: 16, color: colors.ink, marginTop: 4 }}>{owner.name}</Text>
            <Text style={{ color: colors.muted }}>{owner.document} · {owner.institution}</Text>
            <Text onPress={() => setOwner(undefined)} style={{ color: colors.brand, fontWeight: '700', marginTop: 8 }}>Change key</Text>
          </View>
          <Field label="Amount" value={amount} onChangeText={setAmount} keyboardType="decimal-pad" placeholder="0,00" autoFocus />
          {error && <Notice>{error}</Notice>}
          <Button title={value ? `Send ${money(value)}` : 'Send'} onPress={pay} disabled={!value} />
        </>
      )}
    </Card>
  )
}

function ReceivePix() {
  const user = useUser()
  const keys = usePixKeys()
  const register = useRegisterPixKey()
  const [amount, setAmount] = useState('')
  const [code, setCode] = useState<string>()
  const [error, setError] = useState<string>()

  if (keys.isPending) return <Loading />
  const key = keys.data?.[0]
  if (!key) {
    return (
      <Card>
        <Empty title="Register a Pix key first">A key lets people pay you without your account details.</Empty>
        {register.error && <Notice>{errorMessage(register.error)}</Notice>}
        <Button title={`Use my email (${user.email})`} loading={register.isPending} onPress={() => register.mutate({ type: 'EMAIL', value: user.email })} />
        <Button title="Use a random key" variant="secondary" onPress={() => register.mutate({ type: 'EVP' })} style={{ marginTop: 10 }} />
      </Card>
    )
  }

  const generate = async () => {
    setError(undefined)
    try {
      const value = amount ? parseAmount(amount) : undefined
      setCode((await createQrCode({ key: key.value, value: value ?? undefined })).brCode)
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <Card>
      <Text style={{ color: colors.muted, marginBottom: 12 }}>Key: {key.value}</Text>
      <Field label="Amount (optional)" value={amount} onChangeText={(v) => { setAmount(v); setCode(undefined) }} keyboardType="decimal-pad" placeholder="Any amount" />
      {error && <Notice>{error}</Notice>}
      {code ? (
        <View style={{ gap: 14 }}>
          <QrCode value={code} />
          <CopyButton value={code} label="Copy Pix code" />
        </View>
      ) : (
        <Button title="Generate QR code" onPress={generate} />
      )}
      <Text style={{ color: colors.muted, marginTop: 14, fontSize: 13 }}>PayWallet users can also transfer to your ID: #{user.id}</Text>
    </Card>
  )
}

function History() {
  const payments = usePixPayments(0)
  if (payments.isPending) return <Loading />
  if (!payments.data?.content.length) return <Card><Empty title="No Pix yet" /></Card>
  return (
    <Card>
      {payments.data.content.map((p) => (
        <Pressable key={p.endToEndId} onPress={() => router.push(`/pix/${p.endToEndId}`)} style={{ flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 11, gap: 12 }}>
          <View style={{ flexShrink: 1 }}>
            <Text style={{ fontWeight: '600', color: colors.ink }} numberOfLines={1}>{p.counterpartyName ?? 'Pix'}</Text>
            <Text style={{ color: colors.muted, fontSize: 12 }}>{dateTime(p.createdAt)}</Text>
          </View>
          <View style={{ alignItems: 'flex-end', gap: 4 }}>
            <Money value={p.value} direction={p.direction === 'IN' ? 'in' : 'out'} />
            {p.status !== 'COMPLETED' && <StatusBadge status={p.status} />}
          </View>
        </Pressable>
      ))}
    </Card>
  )
}
