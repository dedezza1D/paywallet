import type { TokenResponse, UserType } from './types'

/** Where each app keeps the refresh token: sessionStorage on the web, the OS keychain on mobile. */
export type TokenStore = {
  load(): Promise<string | null>
  save(token: string): Promise<void>
  clear(): Promise<void>
}

export type Platform = {
  /** API root, e.g. "/api" behind the web app's proxy or "https://app.example.com/api" on mobile. */
  baseUrl: string
  store: TokenStore
  /** A stable id for this device, so the API can tell a new one apart and send a sign-in alert. */
  deviceId: () => Promise<string>
}

export type Claims = { userId: number; type: UserType; roles: string[]; expiresAt: number }

const RENEW_BEFORE_MS = 60_000

let platform: Platform | null = null
let access: { token: string; claims: Claims } | null = null
let refreshToken: string | null = null
let refreshing: Promise<boolean> | null = null
const listeners = new Set<() => void>()

export function configure(target: Platform) {
  platform = target
}

function current(): Platform {
  if (!platform) throw new Error('Call configure() before using the API')
  return platform
}

export const baseUrl = () => current().baseUrl
export const deviceId = () => current().deviceId()

function base64UrlDecode(part: string): string {
  const base64 = part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '=')
  return atob(base64)
}

function decode(token: string): Claims {
  const payload = JSON.parse(base64UrlDecode(token.split('.')[1]))
  return {
    userId: Number(payload.sub),
    type: payload.user_type,
    roles: payload.roles ?? [],
    expiresAt: payload.exp * 1000,
  }
}

/** Loads the saved refresh token; call once at startup before reading the session. */
export async function restore(): Promise<boolean> {
  refreshToken = await current().store.load().catch(() => null)
  return refreshToken !== null
}

export function start(tokens: TokenResponse) {
  access = { token: tokens.accessToken, claims: decode(tokens.accessToken) }
  refreshToken = tokens.refreshToken
  void current().store.save(tokens.refreshToken).catch(() => undefined)
}

export function hasRefreshToken(): boolean {
  return refreshToken !== null
}

export function currentRefreshToken(): string | null {
  return refreshToken
}

export function claims(): Claims | null {
  return access?.claims ?? null
}

/** Clears the session and tells subscribers (the auth context) that the user is signed out. */
export function end() {
  access = null
  refreshToken = null
  void current().store.clear().catch(() => undefined)
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
    const token = refreshToken
    if (!token) return false
    try {
      const response = await fetch(`${baseUrl()}/auth/refresh`, {
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
  if (!refreshToken) return null
  return (await refresh()) ? access!.token : null
}
