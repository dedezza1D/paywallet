import {
  type BillQuote, date, dateTime, errorMessage, lookupBill, money, newIdempotencyKey, onlyDigits, parseAmount,
  useBillPayments, usePayBill,
} from '@paywallet/core'
import { Stack } from 'expo-router'
import { useState } from 'react'
import { Text, View } from 'react-native'
import { actionError, usePin } from '../../src/pin'
import { Button, Card, colors, Empty, Field, Loading, Notice, Row, Screen, StatusBadge, styles } from '../../src/ui'

export default function Bills() {
  const [code, setCode] = useState('')
  const [quote, setQuote] = useState<BillQuote>()
  const [amount, setAmount] = useState('')
  const [error, setError] = useState<string>()
  const [looking, setLooking] = useState(false)
  const pay = usePayBill()
  const payments = useBillPayments()
  const withPin = usePin()
  const digits = onlyDigits(code)
  const openAmount = !!quote && !quote.valueDue
  const value = openAmount ? parseAmount(amount) : quote?.valueDue ?? null

  const lookup = async () => {
    setError(undefined)
    setLooking(true)
    try {
      setQuote(await lookupBill(digits))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setLooking(false)
    }
  }

  const confirm = async () => {
    if (!quote || !value) return
    setError(undefined)
    const idempotencyKey = newIdempotencyKey()
    try {
      await withPin({
        title: 'Confirm bill payment',
        summary: `Pay ${money(value)} to ${quote.beneficiaryName}.`,
        run: (pin) => pay.mutateAsync({ pin, idempotencyKey, code: digits, value: openAmount ? value : undefined }),
      })
      setQuote(undefined)
      setCode('')
      setAmount('')
    } catch (e) {
      setError(actionError(e))
    }
  }

  return (
    <Screen refreshing={payments.isRefetching} onRefresh={() => void payments.refetch()}>
      <Stack.Screen options={{ title: 'Pay bills' }} />
      <Card>
        {!quote ? (
          <>
            <Field
              label="Barcode number"
              value={code}
              onChangeText={setCode}
              keyboardType="number-pad"
              placeholder="47 or 48 digits"
              hint={digits ? `${digits.length} digits` : 'Type or paste the digitable line printed on the bill.'}
            />
            {error && <Notice>{error}</Notice>}
            <Button title="Look up bill" onPress={lookup} loading={looking} disabled={digits.length < 44} />
          </>
        ) : (
          <>
            <Text style={styles.sectionTitle}>{quote.beneficiaryName}</Text>
            {quote.dueDate && <Row label="Due date" value={date(quote.dueDate)} />}
            {quote.valueDue != null && <Row label="Total to pay" value={money(quote.valueDue)} />}
            {quote.paymentDeadline && <Row label="Pay until" value={date(quote.paymentDeadline)} />}
            {!quote.payable && <Notice>{quote.notPayableReason ?? 'This bill cannot be paid.'}</Notice>}
            {quote.payable && openAmount && (
              <Field label="Amount" value={amount} onChangeText={setAmount} keyboardType="decimal-pad" placeholder="0,00" />
            )}
            {error && <Notice>{error}</Notice>}
            <View style={{ flexDirection: 'row', gap: 12, marginTop: 8 }}>
              <Button title="Back" variant="secondary" onPress={() => { setQuote(undefined); setError(undefined) }} style={{ flex: 1 }} />
              {quote.payable && <Button title={value ? `Pay ${money(value)}` : 'Pay'} onPress={confirm} disabled={!value} style={{ flex: 1 }} />}
            </View>
          </>
        )}
      </Card>

      <Card>
        <Text style={styles.sectionTitle}>Recent payments</Text>
        {payments.isPending ? <Loading /> : payments.data?.content.length ? (
          payments.data.content.map((b) => (
            <View key={b.id} style={{ paddingVertical: 10 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 12 }}>
                <Text style={{ fontWeight: '600', color: colors.ink, flexShrink: 1 }} numberOfLines={1}>{b.beneficiaryName}</Text>
                <Text style={{ fontWeight: '700', color: colors.ink }}>{money(b.value)}</Text>
              </View>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 }}>
                <Text style={{ color: colors.muted, fontSize: 12 }}>{dateTime(b.createdAt)}</Text>
                <StatusBadge status={b.status} />
              </View>
              {b.failureReason && <Text style={{ color: colors.danger, fontSize: 12, marginTop: 4 }}>{b.failureReason} · refunded</Text>}
            </View>
          ))
        ) : (
          <Empty title="No bills paid yet" />
        )}
      </Card>
    </Screen>
  )
}
