import * as LocalAuthentication from 'expo-local-authentication'
import * as SecureStore from 'expo-secure-store'
import { useEffect, useRef, useState } from 'react'
import { AppState, Platform } from 'react-native'

const LOCK_KEY = 'pw.lock'
// Coming back after a short trip to another app does not ask again.
const RELOCK_AFTER_MS = 60_000

export async function appLockEnabled(): Promise<boolean> {
  if (Platform.OS === 'web') return false
  return (await SecureStore.getItemAsync(LOCK_KEY)) === '1'
}

export async function setAppLock(enabled: boolean) {
  if (enabled) await SecureStore.setItemAsync(LOCK_KEY, '1')
  else await SecureStore.deleteItemAsync(LOCK_KEY)
}

export async function canUseBiometrics(): Promise<boolean> {
  if (Platform.OS === 'web') return false
  return (await LocalAuthentication.hasHardwareAsync()) && (await LocalAuthentication.isEnrolledAsync())
}

export function unlock() {
  return LocalAuthentication.authenticateAsync({ promptMessage: 'Unlock PayWallet', cancelLabel: 'Cancel' })
    .then((result) => result.success)
}

/**
 * Whether the app is locked: on start and after staying in the background, when the user turned the lock on.
 * `locked` is null while the preference is being read.
 */
export function useAppLock(active: boolean) {
  const [locked, setLocked] = useState<boolean | null>(null)
  const backgroundedAt = useRef<number | null>(null)

  useEffect(() => {
    if (!active) return
    void appLockEnabled().then(setLocked)
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'background') backgroundedAt.current = Date.now()
      if (state === 'active' && backgroundedAt.current && Date.now() - backgroundedAt.current > RELOCK_AFTER_MS) {
        void appLockEnabled().then(setLocked)
      }
    })
    return () => subscription.remove()
  }, [active])

  return { locked: active ? locked : false, unlock: async () => setLocked(!(await unlock())) }
}
