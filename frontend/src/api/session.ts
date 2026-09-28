import type { TokenResponse, UserType } from './types'

// The access token lives only in memory. The refresh token is kept per tab in sessionStorage, so closing the tab
// ends the session; it never goes to localStorage, where it would outlive the browser session.
const REFRESH_KEY = 'pw.refresh'
const DEVICE_KEY = 'pw.device'
const RENEW_BEFORE_MS = 60_000

export type Claims = { userId: number; type: UserType; roles: string[]; expiresAt: number }

let access: { token: string; claims: Claims } | null = null
let refreshing: Promise<boolean> | null = null
const listeners = new Set<() => void>()

function decode(token: string): Claims {
  const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
  return {
    userId: Number(payload.sub),
    type: payload.user_type,
    roles: payload.roles ?? [],
    expiresAt: payload.exp * 1000,
  }
}

function storage(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

export function start(tokens: TokenResponse) {
  access = { token: tokens.accessToken, claims: decode(tokens.accessToken) }
  storage()?.setItem(REFRESH_KEY, tokens.refreshToken)
}

export function refreshToken(): string | null {
  return storage()?.getItem(REFRESH_KEY) ?? null
}

export function claims(): Claims | null {
  return access?.claims ?? null
}

/** Clears the session and tells subscribers (the auth context) that the user is signed out. */
export function end() {
  access = null
  storage()?.removeItem(REFRESH_KEY)
  listeners.forEach((listener) => listener())
}

export function onEnd(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * Rotates the refresh token. Only one rotation runs at a time: the API revokes the whole session when a refresh
 * token is used twice, which two parallel rotations would do.
 */
export function refresh(): Promise<boolean> {
  refreshing ??= (async () => {
    const token = refreshToken()
    if (!token) return false
    try {
      const response = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: token }),
      })
      if (!response.ok) {
        if (response.status === 401) end()
        return false
      }
      start(await response.json())
      return true
    } catch {
      return false
    }
  })().finally(() => {
    refreshing = null
  })
  return refreshing
}

export async function accessToken(): Promise<string | null> {
  if (access && access.claims.expiresAt - Date.now() > RENEW_BEFORE_MS) return access.token
  if (!refreshToken()) return null
  return (await refresh()) ? access!.token : null
}

/** A stable id for this browser, so the API can tell a new device apart and send a sign-in alert. */
export function deviceId(): string {
  try {
    let id = window.localStorage.getItem(DEVICE_KEY)
    if (!id) {
      id = crypto.randomUUID()
      window.localStorage.setItem(DEVICE_KEY, id)
    }
    return id
  } catch {
    return 'unknown'
  }
}
