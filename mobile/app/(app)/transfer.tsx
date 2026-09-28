import { dateTime, money, newIdempotencyKey, parseAmount, type TransferResponse, useLimits, useTransfer } from '@paywallet/core'
import { router, Stack } from 'expo-router'
import { useState } from 'react'
import { Text } from 'react-native'
import { actionError, usePin } from '../../src/pin'
import { Button, Card, colors, Field, Notice, Row, Screen } from '../../src/ui'

export default function Transfer() {
  const transfer = useTransfer()
  const limits = useLimits()
  const withPin = usePin()
  const [payee, setPayee] = useState('')
  const [amount, setAmount] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState<string>()
  const [receipt, setReceipt] = useState<TransferResponse>()
  const value = parseAmount(amount)
  const payeeId = Number(payee)

  const submit = async () => {
    if (!value || !payeeId) return
    setError(undefined)
    // One key per confirmed transfer: retrying after a network error cannot send the money twice.
    const idempotencyKey = newIdempotencyKey()
    try {
      setReceipt(await withPin({
        title: 'Confirm transfer',
        summary: `Send ${money(value)} to user #${payeeId}.`,
        run: (pin) => transfer.mutateAsync({ pin, idempotencyKey, value, payee: payeeId, message: message.trim() || undefined, visibility: 'PRIVATE' }),
      }))
    } catch (e) {
      setError(actionError(e))
    }
  }

  if (receipt) {
    return (
      <Screen>
        <Stack.Screen options={{ title: 'Transfer sent' }} />
        <Card>
          <Text style={{ fontSize: 32, fontWeight: '800', color: colors.ink, textAlign: 'center', marginBottom: 12 }}>{money(receipt.value)}</Text>
          <Row label="To" value={`User #${receipt.payee}`} />
          <Row label="When" value={dateTime(receipt.createdAt)} />
          <Row label="Transaction" value={receipt.transactionId.slice(0, 18) + '…'} />
        </Card>
        <Button title="Done" onPress={() => router.back()} />
      </Screen>
    )
  }

  return (
    <Screen>
      <Stack.Screen options={{ title: 'Transfer' }} />
      <Notice tone="info">Know their email or phone? Use Pix: it works with any bank.</Notice>
      <Field label="Recipient's PayWallet ID" value={payee} onChangeText={(v) => setPayee(v.replace(/\D/g, ''))} keyboardType="number-pad" placeholder="e.g. 42" />
      <Field
        label="Amount"
        value={amount}
        onChangeText={setAmount}
        keyboardType="decimal-pad"
        placeholder="0,00"
        error={amount && !value ? 'Enter an amount like 25,90.' : undefined}
        hint={limits.data ? `${money(limits.data.remainingToday)} available today` : undefined}
      />
      <Field label="Message (optional)" value={message} onChangeText={setMessage} maxLength={140} />
      {error && <Notice>{error}</Notice>}
      <Button title="Continue" onPress={submit} disabled={!value || !payeeId} />
    </Screen>
  )
}
