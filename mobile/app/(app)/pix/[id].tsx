import { dateTime, errorMessage, money, newIdempotencyKey, parseAmount, usePixPayment, usePixReturns, useReturnPix } from '@paywallet/core'
import { Stack, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'
import { Text } from 'react-native'
import { actionError, usePin } from '../../../src/pin'
import { Button, Card, colors, Field, Loading, Notice, Row, Screen, StatusBadge, styles } from '../../../src/ui'

export default function PixPayment() {
  const { id = '' } = useLocalSearchParams<{ id: string }>()
  const payment = usePixPayment(id)
  const returns = usePixReturns(id)
  const giveBack = useReturnPix(id)
  const withPin = usePin()
  const [returning, setReturning] = useState(false)
  const [amount, setAmount] = useState('')
  const [error, setError] = useState<string>()

  if (payment.isPending) return <Loading />
  if (payment.error) return <Screen><Notice>{errorMessage(payment.error)}</Notice></Screen>
  const p = payment.data
  const incoming = p.direction === 'IN'
  const returned = returns.data?.filter((r) => r.status !== 'FAILED').reduce((sum, r) => sum + r.value, 0) ?? 0
  const value = parseAmount(amount)

  const submitReturn = async () => {
    if (!value) return
    setError(undefined)
    const idempotencyKey = newIdempotencyKey()
    try {
      await withPin({
        title: 'Confirm return',
        summary: `Return ${money(value)} to ${p.counterpartyName}.`,
        run: (pin) => giveBack.mutateAsync({ pin, idempotencyKey, value, reason: 'MD06' }),
      })
      setReturning(false)
      setAmount('')
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <Screen>
      <Stack.Screen options={{ title: incoming ? 'Pix received' : 'Pix sent' }} />
      <Card>
        <Text style={{ fontSize: 32, fontWeight: '800', color: colors.ink, textAlign: 'center' }}>{money(p.value)}</Text>
        <Text style={{ textAlign: 'center', marginTop: 6 }}><StatusBadge status={p.status} /></Text>
        {p.status === 'PENDING' && <Text style={{ textAlign: 'center', color: colors.muted, marginTop: 8 }}>Waiting for the other bank to confirm…</Text>}
        {p.failureReason && <Notice>{`${p.failureReason}. The money is back in your balance.`}</Notice>}
        <Row label={incoming ? 'From' : 'To'} value={p.counterpartyName ?? '—'} />
        {p.counterpartyDocument && <Row label="Document" value={p.counterpartyDocument} />}
        <Row label="Type" value={p.scope === 'INTERNAL' ? 'PayWallet to PayWallet' : 'Another bank'} />
        {p.description && <Row label="Description" value={p.description} />}
        <Row label="Date" value={dateTime(p.createdAt)} />
      </Card>

      {!!returns.data?.length && (
        <Card>
          <Text style={styles.sectionTitle}>Returns</Text>
          {returns.data.map((r) => <Row key={r.returnId} label={money(r.value)} value={<StatusBadge status={r.status} />} />)}
        </Card>
      )}

      {incoming && p.status === 'COMPLETED' && returned < p.value && (
        returning ? (
          <Card>
            <Field label="Amount to return" value={amount} onChangeText={setAmount} keyboardType="decimal-pad" hint={`Up to ${money(p.value - returned)}`} />
            {error && <Notice>{error}</Notice>}
            <Button title="Return money" onPress={submitReturn} disabled={!value || value > p.value - returned} />
          </Card>
        ) : (
          <Button title="Return money" variant="secondary" onPress={() => setReturning(true)} />
        )
      )}
    </Screen>
  )
}
