import { History, Lock, Users } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useFeed, usePublicFeed, useStatement } from '../api/queries'
import { useUser } from '../auth/AuthContext'
import { ActivityList } from '../components/wallet'
import { Badge, Button, Card, EmptyState, PageHeader, Spinner, Tabs } from '../components/ui'
import { dateTime, money } from '../lib/format'

type Tab = 'statement' | 'friends' | 'community'

export default function ActivityPage() {
  const [tab, setTab] = useState<Tab>('statement')
  return (
    <>
      <PageHeader title="Activity" subtitle="Every movement in your account, straight from the ledger." />
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: 'statement', label: 'Statement' },
          { value: 'friends', label: 'Transfers' },
          { value: 'community', label: 'Community' },
        ]}
      />
      {tab === 'statement' && <Statement />}
      {tab === 'friends' && <MyFeed />}
      {tab === 'community' && <CommunityFeed />}
    </>
  )
}

function Statement() {
  const [page, setPage] = useState(0)
  const statement = useStatement(page)
  if (statement.isPending) return <Spinner />
  const data = statement.data
  return (
    <Card>
      {data?.content.length ? <ActivityList entries={data.content} /> : <EmptyState icon={<History className="size-6" />} title="No movements yet" />}
      {data && data.totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between border-t border-line pt-4 text-sm">
          <Button variant="secondary" size="sm" disabled={data.first} onClick={() => setPage(page - 1)}>Newer</Button>
          <span className="text-muted">Page {data.number + 1} of {data.totalPages}</span>
          <Button variant="secondary" size="sm" disabled={data.last} onClick={() => setPage(page + 1)}>Older</Button>
        </div>
      )}
    </Card>
  )
}

function MyFeed() {
  const user = useUser()
  const feed = useFeed()
  if (feed.isPending) return <Spinner />
  const items = feed.data?.content ?? []
  if (!items.length) {
    return (
      <Card>
        <EmptyState icon={<Users className="size-6" />} title="No transfers with friends yet">
          <Link to="/send" className="font-semibold text-brand-700">Send money</Link> to someone on PayWallet.
        </EmptyState>
      </Card>
    )
  }
  return (
    <Card>
      <ul className="divide-y divide-line">
        {items.map((item) => {
          const sent = item.payerId === user.id
          return (
            <li key={item.id} className="flex items-start gap-3 py-3">
              <div className="min-w-0 flex-1">
                <p className="text-sm">
                  <span className="font-semibold">{sent ? 'You' : item.payerName}</span> paid{' '}
                  <span className="font-semibold">{sent ? item.payeeName : 'you'}</span>
                </p>
                {item.message && <p className="mt-0.5 text-sm text-slate-600">“{item.message}”</p>}
                <p className="mt-1 flex items-center gap-2 text-xs text-muted">
                  {dateTime(item.createdAt)}
                  {item.visibility === 'PRIVATE' && <Badge><Lock className="mr-1 size-3" />Private</Badge>}
                </p>
              </div>
              <span className={`text-sm font-semibold tabular-nums ${sent ? '' : 'text-brand-700'}`}>
                {sent ? '− ' : '+ '}{money(item.value)}
              </span>
            </li>
          )
        })}
      </ul>
    </Card>
  )
}

function CommunityFeed() {
  const feed = usePublicFeed()
  if (feed.isPending) return <Spinner />
  const items = feed.data?.content ?? []
  return (
    <Card>
      <p className="mb-2 text-xs text-muted">Public transfers, without amounts.</p>
      {items.length ? (
        <ul className="divide-y divide-line">
          {items.map((item, i) => (
            <li key={`${item.createdAt}-${i}`} className="py-3 text-sm">
              <p><span className="font-semibold">{item.payerName}</span> paid <span className="font-semibold">{item.payeeName}</span></p>
              {item.message && <p className="text-slate-600">“{item.message}”</p>}
              <p className="text-xs text-muted">{dateTime(item.createdAt)}</p>
            </li>
          ))}
        </ul>
      ) : (
        <EmptyState title="Nothing public yet" />
      )}
    </Card>
  )
}
