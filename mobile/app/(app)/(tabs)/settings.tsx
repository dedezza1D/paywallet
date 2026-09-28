import { maskDocument, useAuth, useUser } from '@paywallet/core'
import { router, useFocusEffect } from 'expo-router'
import { useCallback, useState } from 'react'
import { Pressable, Switch, Text, View } from 'react-native'
import { openLegal } from '../../../src/legal'
import { appLockEnabled, canUseBiometrics, setAppLock, unlock } from '../../../src/lock'
import { biometricPinEnabled, disableBiometricPin } from '../../../src/pin'
import { Button, Card, colors, Row, Screen, styles } from '../../../src/ui'

function LinkRow({ label, onPress, danger }: { label: string; onPress: () => void; danger?: boolean }) {
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={{ paddingVertical: 13 }}>
      <Text style={{ fontSize: 15, fontWeight: '600', color: danger ? colors.danger : colors.ink }}>{label}</Text>
    </Pressable>
  )
}

export default function Settings() {
  const user = useUser()
  const { logout } = useAuth()
  const [biometrics, setBiometrics] = useState(false)
  const [lock, setLock] = useState(false)
  const [payWithBiometrics, setPayWithBiometrics] = useState(false)

  useFocusEffect(useCallback(() => {
    void canUseBiometrics().then(setBiometrics)
    void appLockEnabled().then(setLock)
    void biometricPinEnabled().then(setPayWithBiometrics)
  }, []))

  return (
    <Screen>
      <Card>
        <Text style={styles.sectionTitle}>Profile</Text>
        <Row label="Name" value={user.fullName} />
        <Row label="CPF" value={maskDocument(user.document)} />
        <Row label="Email" value={user.email} />
        <Row label="PayWallet ID" value={`#${user.id}`} />
      </Card>

      <Card>
        <Text style={styles.sectionTitle}>Security</Text>
        <LinkRow label={user.transactionPinSet ? 'Change transaction PIN' : 'Create transaction PIN'} onPress={() => router.push('/settings/pin')} />
        {biometrics && (
          <>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 8 }}>
              <Text style={{ fontSize: 15, color: colors.ink, flexShrink: 1 }}>Unlock the app with biometrics</Text>
              <Switch
                value={lock}
                accessibilityLabel="Unlock the app with biometrics"
                onValueChange={async (enabled) => {
                  // Confirm it works before relying on it, or the user could lock themselves out.
                  if (enabled && !(await unlock())) return
                  await setAppLock(enabled)
                  setLock(enabled)
                }}
              />
            </View>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 8 }}>
              <Text style={{ fontSize: 15, color: colors.ink, flexShrink: 1 }}>Confirm payments with biometrics</Text>
              <Switch
                value={payWithBiometrics}
                accessibilityLabel="Confirm payments with biometrics"
                onValueChange={async (enabled) => {
                  if (enabled) {
                    router.push('/settings/pin?biometric=1')
                  } else {
                    await disableBiometricPin()
                    setPayWithBiometrics(false)
                  }
                }}
              />
            </View>
          </>
        )}
        <Text style={{ color: colors.muted, fontSize: 13, marginTop: 6 }}>
          Two-factor authentication and your devices are managed in the web app.
        </Text>
      </Card>

      <Card>
        <Text style={styles.sectionTitle}>About</Text>
        <LinkRow label="Terms of use" onPress={() => void openLegal('terms')} />
        <LinkRow label="Privacy policy" onPress={() => void openLegal('privacy')} />
        <LinkRow label="Close my account" danger onPress={() => router.push('/settings/close')} />
      </Card>

      <Button title="Sign out" variant="secondary" onPress={() => void logout()} />
    </Screen>
  )
}
