import type { Platform } from '@paywallet/core'

// The refresh token is kept per tab in sessionStorage, so closing the tab ends the session; it never goes to
// localStorage, where it would outlive the browser session.
const REFRESH_KEY = 'pw.refresh'
const DEVICE_KEY = 'pw.device'

function storage(kind: 'session' | 'local'): Storage | null {
  try {
    return kind === 'session' ? window.sessionStorage : window.localStorage
  } catch {
    return null
  }
}

export const webPlatform: Platform = {
  baseUrl: '/api',
  store: {
    load: async () => storage('session')?.getItem(REFRESH_KEY) ?? null,
    save: async (token) => storage('session')?.setItem(REFRESH_KEY, token),
    clear: async () => storage('session')?.removeItem(REFRESH_KEY),
  },
  deviceId: async () => {
    const local = storage('local')
    let id = local?.getItem(DEVICE_KEY)
    if (!id) {
      id = crypto.randomUUID()
      local?.setItem(DEVICE_KEY, id)
    }
    return id
  },
}
