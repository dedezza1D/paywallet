import { dateTime, ledgerLabel, money, useStatement } from '@paywallet/core'
import { useState } from 'react'
import { Text, View } from 'react-native'
import { Button, Card, colors, Empty, Loading, Money, Screen } from '../../../src/ui'

export default function Activity() {
  const [page, setPage] = useState(0)
  const statement = useStatement(page)
  const data = statement.data

  return (
    <Screen refreshing={statement.isRefetching} onRefresh={() => void statement.refetch()}>
      <Card>
        {statement.isPending ? <Loading /> : data?.content.length ? (
          data.content.map((entry) => (
            <View key={`${entry.transactionId}-${entry.direction}`} style={{ flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 11, gap: 12 }}>
              <View style={{ flexShrink: 1 }}>
                <Text style={{ fontWeight: '600', color: colors.ink }}>{ledgerLabel(entry.type)}</Text>
                <Text style={{ color: colors.muted, fontSize: 12 }}>{dateTime(entry.createdAt)}</Text>
              </View>
              <View style={{ alignItems: 'flex-end' }}>
                <Money value={entry.value} direction={entry.direction === 'CREDIT' ? 'in' : 'out'} />
                <Text style={{ color: colors.muted, fontSize: 12 }}>Balance {money(entry.balanceAfter)}</Text>
              </View>
            </View>
          ))
        ) : (
          <Empty title="No movements yet" />
        )}
      </Card>
      {data && data.totalPages > 1 && (
        <View style={{ flexDirection: 'row', gap: 12 }}>
          <Button title="Newer" variant="secondary" disabled={data.first} onPress={() => setPage(page - 1)} style={{ flex: 1 }} />
          <Button title="Older" variant="secondary" disabled={data.last} onPress={() => setPage(page + 1)} style={{ flex: 1 }} />
        </View>
      )}
    </Screen>
  )
}
