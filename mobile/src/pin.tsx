import { ApiError, errorMessage } from '@paywallet/core'
import * as SecureStore from 'expo-secure-store'
import { router } from 'expo-router'
import { createContext, type ReactNode, use, useCallback, useState } from 'react'
import { KeyboardAvoidingView, Modal, Platform, Pressable, Text, TextInput, View } from 'react-native'
import { Button, colors, Notice } from './ui'

// Saved only behind the device's biometrics: reading it prompts Face ID or the fingerprint.
const PIN_KEY = 'pw.pin'
const PIN_ENABLED_KEY = 'pw.pin.biometric'
const protectedPin: SecureStore.SecureStoreOptions = {
  requireAuthentication: true,
  authenticationPrompt: 'Confirm the payment',
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
}

export function biometricsSupported(): boolean {
  return Platform.OS !== 'web' && SecureStore.canUseBiometricAuthentication()
}

export async function biometricPinEnabled(): Promise<boolean> {
  return biometricsSupported() && (await SecureStore.getItemAsync(PIN_ENABLED_KEY)) === '1'
}

export async function enableBiometricPin(pin: string) {
  await SecureStore.setItemAsync(PIN_KEY, pin, protectedPin)
  await SecureStore.setItemAsync(PIN_ENABLED_KEY, '1')
}

export async function disableBiometricPin() {
  await SecureStore.deleteItemAsync(PIN_KEY)
  await SecureStore.deleteItemAsync(PIN_ENABLED_KEY)
}

async function readBiometricPin(): Promise<string | null> {
  if (!(await biometricPinEnabled())) return null
  try {
    return await SecureStore.getItemAsync(PIN_KEY, protectedPin)
  } catch {
    // Biometrics cancelled or failed: fall back to typing the PIN.
    return null
  }
}

export class PinCancelled extends Error {
  constructor() {
    super('Cancelled')
    this.name = 'PinCancelled'
  }
}

export type PinTask<T> = { title: string; summary?: string; run: (pin: string) => Promise<T> }
type PinRunner = <T>(task: PinTask<T>) => Promise<T>

const PinContext = createContext<PinRunner | null>(null)

export function usePin(): PinRunner {
  const context = use(PinContext)
  if (!context) throw new Error('usePin outside PinProvider')
  return context
}

export function actionError(error: unknown): string | undefined {
  return error instanceof PinCancelled ? undefined : errorMessage(error)
}

type Pending = { task: PinTask<unknown>; resolve: (value: unknown) => void; reject: (error: unknown) => void }

export function PinProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null)
  const [pin, setPin] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const attempt = useCallback(async (current: Pending, value: string) => {
    setBusy(true)
    setError(undefined)
    try {
      current.resolve(await current.task.run(value))
      setPending(null)
    } catch (e) {
      if (e instanceof ApiError && e.isWrongPin) {
        // A saved PIN that no longer works was changed elsewhere; stop offering it.
        await disableBiometricPin().catch(() => undefined)
        setError('Wrong PIN. After 5 wrong attempts, payments are locked for 30 minutes.')
        setPin('')
      } else if (e instanceof ApiError && e.status === 428) {
        current.reject(e)
        setPending(null)
        router.push('/settings/pin')
      } else {
        current.reject(e)
        setPending(null)
      }
    } finally {
      setBusy(false)
    }
  }, [])

  const runWithPin = useCallback<PinRunner>((task) => new Promise((resolve, reject) => {
    const current = { task: task as PinTask<unknown>, resolve: resolve as (value: unknown) => void, reject }
    setPin('')
    setError(undefined)
    setPending(current)
    void readBiometricPin().then((saved) => {
      if (saved) void attempt(current, saved)
    })
  }), [attempt])

  const cancel = () => {
    pending?.reject(new PinCancelled())
    setPending(null)
  }

  return (
    <PinContext value={runWithPin}>
      {children}
      <Modal visible={!!pending} transparent animationType="slide" onRequestClose={cancel}>
        <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(15,23,42,0.4)' }}>
          <View style={{ backgroundColor: colors.white, borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: 24, paddingBottom: 36 }}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <Text style={{ fontSize: 18, fontWeight: '700', color: colors.ink }}>{pending?.task.title}</Text>
              <Pressable onPress={cancel} accessibilityRole="button" accessibilityLabel="Cancel" hitSlop={12}>
                <Text style={{ color: colors.muted, fontSize: 15 }}>Cancel</Text>
              </Pressable>
            </View>
            {pending?.task.summary && <Text style={{ color: colors.muted, marginBottom: 16 }}>{pending.task.summary}</Text>}
            <Text style={{ fontWeight: '600', color: '#334155', marginBottom: 8 }}>Transaction PIN</Text>
            <TextInput
              autoFocus
              value={pin}
              onChangeText={(text) => setPin(text.replace(/\D/g, '').slice(0, 6))}
              keyboardType="number-pad"
              secureTextEntry
              maxLength={6}
              accessibilityLabel="Transaction PIN"
              style={{
                height: 56, borderWidth: 1, borderColor: colors.line, borderRadius: 14, textAlign: 'center', fontSize: 26,
                letterSpacing: 12, color: colors.ink, marginBottom: 12,
              }}
            />
            {error && <Notice>{error}</Notice>}
            <Button title="Confirm" loading={busy} disabled={pin.length !== 6} onPress={() => pending && void attempt(pending, pin)} />
          </View>
        </KeyboardAvoidingView>
      </Modal>
    </PinContext>
  )
}
