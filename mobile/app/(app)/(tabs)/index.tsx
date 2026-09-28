import {
  dateTime, ledgerLabel, money, parseAmount, useBalance, useDeposit, useLimits, useStatement, useUser, useYield,
} from '@paywallet/core'
import { Link, router } from 'expo-router'
import { ArrowLeftRight, FileText, QrCode } from 'lucide-react-native'
import { useState } from 'react'
import { Modal, Pressable, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { Button, Card, colors, Empty, Field, Loading, Money, Notice, Screen, styles } from '../../../src/ui'

const actions = [
  { href: '/pix', label: 'Pix', Icon: QrCode },
  { href: '/transfer', label: 'Transfer', Icon: ArrowLeftRight },
  { href: '/bills', label: 'Pay a bill', Icon: FileText },
] as const

export default function Home() {
  const user = useUser()
  const balance = useBalance()
  const statement = useStatement(0, 6)
  const limits = useLimits()
  const earnings = useYield()
  const [hidden, setHidden] = useState(false)
  const [depositing, setDepositing] = useState(false)
  const refreshing = balance.isRefetching || statement.isRefetching

  return (
    <SafeAreaView style={styles.safe} edges={['top', 'left', 'right']}>
      <Screen
        refreshing={refreshing}
        onRefresh={() => {
          void balance.refetch()
          void statement.refetch()
          void limits.refetch()
        }}
      >
        <Text style={{ fontSize: 24, fontWeight: '800', color: colors.ink, marginBottom: 16 }}>Hi, {user.fullName.split(' ')[0]}</Text>
        {!user.transactionPinSet && (
          <Pressable onPress={() => router.push('/settings/pin')}>
            <Notice tone="warning">Create your transaction PIN to send money. Tap here.</Notice>
          </Pressable>
        )}
        <View style={{ backgroundColor: colors.brand, borderRadius: 24, padding: 22, marginBottom: 16 }}>
          <Pressable onPress={() => setHidden(!hidden)} accessibilityRole="button" accessibilityLabel={hidden ? 'Show balance' : 'Hide balance'}>
            <Text style={{ color: '#d1fae4', fontSize: 14 }}>Available balance · tap to {hidden ? 'show' : 'hide'}</Text>
            <Text style={{ color: colors.white, fontSize: 34, fontWeight: '800', marginTop: 6 }} testID="balance">
              {balance.isPending ? '…' : hidden ? 'R$ ••••••' : money(balance.data?.balance)}
            </Text>
          </Pressable>
          <Text style={{ color: '#d1fae4', marginTop: 4 }}>
            Earning {earnings.data ? `${earnings.data.cdiPercentage}% of the CDI` : 'the CDI'} every business day
          </Text>
          <Button title="Add money" variant="secondary" onPress={() => setDepositing(true)} style={{ marginTop: 16, alignSelf: 'flex-start', minHeight: 40 }} />
        </View>

        <View style={{ flexDirection: 'row', gap: 12, marginBottom: 16 }}>
          {actions.map(({ href, label, Icon }) => (
            <Link key={href} href={href} asChild>
              <Pressable style={{ flex: 1, alignItems: 'center', gap: 8, backgroundColor: colors.white, borderRadius: 18, borderWidth: 1, borderColor: colors.line, paddingVertical: 16 }}>
                <Icon color={colors.brand} size={24} />
                <Text style={{ fontWeight: '600', color: colors.ink, fontSize: 13 }}>{label}</Text>
              </Pressable>
            </Link>
          ))}
        </View>

        <Card>
          <Text style={styles.sectionTitle}>Recent activity</Text>
          {statement.isPending ? <Loading /> : statement.data?.content.length ? (
            statement.data.content.map((entry) => (
              <View key={`${entry.transactionId}-${entry.direction}`} style={{ flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 10 }}>
                <View style={{ flexShrink: 1 }}>
                  <Text style={{ fontWeight: '600', color: colors.ink }}>{ledgerLabel(entry.type)}</Text>
                  <Text style={{ color: colors.muted, fontSize: 12 }}>{dateTime(entry.createdAt)}</Text>
                </View>
                <Money value={entry.value} direction={entry.direction === 'CREDIT' ? 'in' : 'out'} />
              </View>
            ))
          ) : (
            <Empty title="No activity yet">Add money or receive a Pix to get started.</Empty>
          )}
        </Card>

        <Card>
          <Text style={styles.sectionTitle}>Daily transfer limit</Text>
          <Text style={{ color: colors.muted }}>{money(limits.data?.remainingToday)} left today of {money(limits.data?.dailyLimit)}</Text>
        </Card>
      </Screen>
      <DepositSheet open={depositing} onClose={() => setDepositing(false)} />
    </SafeAreaView>
  )
}

function DepositSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const deposit = useDeposit()
  const [amount, setAmount] = useState('')
  const value = parseAmount(amount)
  return (
    <Modal visible={open} animationType="slide" presentationStyle="pageSheet" onRequestClose={onClose}>
      <Screen>
        <Text style={{ fontSize: 22, fontWeight: '800', color: colors.ink, marginBottom: 12 }}>Add money</Text>
        <Notice tone="info">Test environment: this simulates a deposit. In production money arrives by Pix.</Notice>
        <Field label="Amount" value={amount} onChangeText={setAmount} keyboardType="decimal-pad" placeholder="0,00" autoFocus />
        {deposit.error && <Notice>{deposit.error.message}</Notice>}
        <Button
          title={value ? `Add ${money(value)}` : 'Add'}
          disabled={!value}
          loading={deposit.isPending}
          onPress={async () => {
            try {
              await deposit.mutateAsync(value!)
              setAmount('')
              onClose()
            } catch {
              // The error is shown from the mutation state above.
            }
          }}
        />
        <Button title="Cancel" variant="ghost" onPress={onClose} style={{ marginTop: 8 }} />
      </Screen>
    </Modal>
  )
}
