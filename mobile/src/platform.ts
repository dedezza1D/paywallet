import type { Platform as ApiPlatform, TokenStore } from '@paywallet/core'
import * as Crypto from 'expo-crypto'
import * as SecureStore from 'expo-secure-store'
import { Platform } from 'react-native'

const REFRESH_KEY = 'pw.refresh'
const DEVICE_KEY = 'pw.device'

// Readable only while the device is unlocked, and never restored to another device from a backup.
const keychain: SecureStore.SecureStoreOptions = { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY }

// The web build (used in development) has no keychain.
const webStore: TokenStore = {
  load: async () => sessionStorage.getItem(REFRESH_KEY),
  save: async (token) => sessionStorage.setItem(REFRESH_KEY, token),
  clear: async () => sessionStorage.removeItem(REFRESH_KEY),
}

const nativeStore: TokenStore = {
  load: () => SecureStore.getItemAsync(REFRESH_KEY, keychain),
  save: (token) => SecureStore.setItemAsync(REFRESH_KEY, token, keychain),
  clear: () => SecureStore.deleteItemAsync(REFRESH_KEY, keychain),
}

async function deviceId(): Promise<string> {
  if (Platform.OS === 'web') return 'web-dev'
  let id = await SecureStore.getItemAsync(DEVICE_KEY, keychain)
  if (!id) {
    id = Crypto.randomUUID()
    await SecureStore.setItemAsync(DEVICE_KEY, id, keychain)
  }
  return id
}

export const API_URL = process.env.EXPO_PUBLIC_API_URL ?? 'https://localhost/api'

/** The web app, which hosts the legal documents the stores link to. */
export const WEB_URL = process.env.EXPO_PUBLIC_WEB_URL ?? API_URL.replace(/\/api\/?$/, '')

export const mobilePlatform: ApiPlatform = {
  baseUrl: API_URL,
  store: Platform.OS === 'web' ? webStore : nativeStore,
  deviceId,
}

/** The shared API client creates idempotency keys with crypto.randomUUID, which Hermes does not provide. */
export function installCryptoPolyfill() {
  const target = globalThis as unknown as { crypto?: { randomUUID?: () => string } }
  if (!target.crypto?.randomUUID) {
    target.crypto = Object.assign(target.crypto ?? {}, { randomUUID: Crypto.randomUUID })
  }
}
