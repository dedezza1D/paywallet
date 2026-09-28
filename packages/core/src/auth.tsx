import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, type ReactNode, use, useCallback, useEffect, useMemo, useState } from 'react'
import { request } from './client'
import * as session from './session'
import type { MfaChallenge, TokenResponse, User } from './types'

type Status = 'loading' | 'signedIn' | 'signedOut'

type Auth = {
  status: Status
  user: User | undefined
  /** @returns the second-factor challenge when the account has 2FA, otherwise nothing (signed in) */
  login: (email: string, password: string) => Promise<MfaChallenge | undefined>
  completeMfa: (mfaToken: string, code: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<Auth | null>(null)

export function useAuth(): Auth {
  const context = use(AuthContext)
  if (!context) throw new Error('useAuth outside AuthProvider')
  return context
}

/** The signed-in user; only for pages behind the auth guard. */
export function useUser(): User {
  const { user } = useAuth()
  if (!user) throw new Error('No signed-in user')
  return user
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<Status>('loading')

  useEffect(() => {
    if (status === 'loading') {
      void session.restore()
        .then((saved) => (saved ? session.refresh() : false))
        .then((ok) => setStatus(ok ? 'signedIn' : 'signedOut'))
    }
    return session.onEnd(() => {
      setStatus('signedOut')
      queryClient.clear()
    })
  }, [queryClient, status])

  const me = useQuery({
    queryKey: ['me'],
    queryFn: () => request<User>(`/users/${session.claims()!.userId}`),
    enabled: status === 'signedIn',
    staleTime: 5 * 60_000,
  })

  const login = useCallback(async (email: string, password: string) => {
    const result = await request<TokenResponse | MfaChallenge>('/auth/login', {
      method: 'POST',
      auth: false,
      body: { email, password },
      headers: { 'X-Device-Id': await session.deviceId() },
    })
    if ('mfaRequired' in result) return result
    session.start(result)
    setStatus('signedIn')
    return undefined
  }, [])

  const completeMfa = useCallback(async (mfaToken: string, code: string) => {
    const tokens = await request<TokenResponse>('/auth/login/mfa', {
      method: 'POST',
      auth: false,
      body: { mfaToken, code },
      headers: { 'X-Device-Id': await session.deviceId() },
    })
    session.start(tokens)
    setStatus('signedIn')
  }, [])

  const logout = useCallback(async () => {
    const refreshToken = session.currentRefreshToken()
    if (refreshToken) {
      await request('/auth/logout', { method: 'POST', auth: false, body: { refreshToken } }).catch(() => undefined)
    }
    session.end()
  }, [])

  const value = useMemo<Auth>(
    () => ({ status, user: me.data, login, completeMfa, logout }),
    [status, me.data, login, completeMfa, logout],
  )
  return <AuthContext value={value}>{children}</AuthContext>
}
