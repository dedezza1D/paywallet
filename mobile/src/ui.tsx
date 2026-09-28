import { money } from '@paywallet/core'
import * as Clipboard from 'expo-clipboard'
import { type ReactNode, useState } from 'react'
import {
  ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, TextInput, type TextInputProps, View,
  type ViewStyle,
} from 'react-native'
import QRCodeSvg from 'react-native-qrcode-svg'
import { SafeAreaView } from 'react-native-safe-area-context'

export const colors = {
  brand: '#0b8a4f',
  brandDark: '#053b23',
  brandSoft: '#ecfdf4',
  ink: '#0f172a',
  muted: '#64748b',
  line: '#e2e8f0',
  canvas: '#f6f8f7',
  white: '#ffffff',
  danger: '#dc2626',
  dangerSoft: '#fef2f2',
  amber: '#b45309',
  amberSoft: '#fffbeb',
  infoSoft: '#eff6ff',
  info: '#1d4ed8',
}

export function Screen({ children, onRefresh, refreshing = false, padded = true }: {
  children: ReactNode
  onRefresh?: () => void
  refreshing?: boolean
  padded?: boolean
}) {
  return (
    <SafeAreaView style={styles.safe} edges={['left', 'right']}>
      <ScrollView
        contentContainerStyle={padded ? styles.screen : undefined}
        keyboardShouldPersistTaps="handled"
        refreshControl={onRefresh ? <RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={colors.brand} /> : undefined}
      >
        {children}
      </ScrollView>
    </SafeAreaView>
  )
}

export function Title({ children, subtitle }: { children: ReactNode; subtitle?: string }) {
  return (
    <View style={{ marginBottom: 20 }}>
      <Text style={styles.title}>{children}</Text>
      {subtitle && <Text style={styles.subtitle}>{subtitle}</Text>}
    </View>
  )
}

export function Button({ title, onPress, variant = 'primary', loading, disabled, style }: {
  title: string
  onPress: () => void
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost'
  loading?: boolean
  disabled?: boolean
  style?: ViewStyle
}) {
  const inactive = disabled || loading
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: !!inactive, busy: !!loading }}
      onPress={onPress}
      disabled={inactive}
      style={({ pressed }) => [
        styles.button,
        variant === 'primary' && { backgroundColor: colors.brand },
        variant === 'secondary' && { backgroundColor: colors.white, borderWidth: 1, borderColor: colors.line },
        variant === 'danger' && { backgroundColor: colors.danger },
        variant === 'ghost' && { backgroundColor: 'transparent' },
        (pressed || inactive) && { opacity: inactive ? 0.5 : 0.85 },
        style,
      ]}
    >
      {loading && <ActivityIndicator color={variant === 'primary' || variant === 'danger' ? colors.white : colors.brand} />}
      <Text
        style={[
          styles.buttonText,
          { color: variant === 'primary' || variant === 'danger' ? colors.white : variant === 'ghost' ? colors.brand : colors.ink },
        ]}
      >
        {title}
      </Text>
    </Pressable>
  )
}

export function Field({ label, error, hint, ...props }: TextInputProps & { label: string; error?: string; hint?: string }) {
  return (
    <View style={{ marginBottom: 14 }}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        placeholderTextColor="#94a3b8"
        style={[styles.input, error ? { borderColor: '#f87171' } : null]}
        {...props}
      />
      {(error || hint) && <Text style={[styles.note, error ? { color: colors.danger } : null]}>{error ?? hint}</Text>}
    </View>
  )
}

export function Card({ children, style }: { children: ReactNode; style?: ViewStyle }) {
  return <View style={[styles.card, style]}>{children}</View>
}

export function Notice({ tone = 'error', children }: { tone?: 'error' | 'info' | 'success' | 'warning'; children: ReactNode }) {
  const palette = {
    error: [colors.dangerSoft, colors.danger],
    info: [colors.infoSoft, colors.info],
    success: [colors.brandSoft, colors.brandDark],
    warning: [colors.amberSoft, colors.amber],
  }[tone]
  return (
    <View accessibilityRole={tone === 'error' ? 'alert' : undefined} style={[styles.notice, { backgroundColor: palette[0] }]}>
      <Text style={{ color: palette[1], fontSize: 14, lineHeight: 20 }}>{children}</Text>
    </View>
  )
}

const statusColors: Record<string, [string, string]> = {
  COMPLETED: [colors.brandSoft, colors.brand], CONFIRMED: [colors.brandSoft, colors.brand], PAID: [colors.brandSoft, colors.brand],
  ACTIVE: [colors.brandSoft, colors.brand], PENDING: [colors.amberSoft, colors.amber], FAILED: [colors.dangerSoft, colors.danger],
}

export function StatusBadge({ status }: { status: string }) {
  const [bg, fg] = statusColors[status] ?? ['#f1f5f9', '#334155']
  const label = status.charAt(0) + status.slice(1).toLowerCase().replace(/_/g, ' ')
  return (
    <View style={{ backgroundColor: bg, borderRadius: 999, paddingHorizontal: 8, paddingVertical: 2, alignSelf: 'flex-start' }}>
      <Text style={{ color: fg, fontSize: 12, fontWeight: '600' }}>{label}</Text>
    </View>
  )
}

export function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      {typeof value === 'string' ? <Text style={styles.rowValue}>{value}</Text> : value}
    </View>
  )
}

export function Money({ value, direction, size = 15 }: { value: number; direction?: 'in' | 'out'; size?: number }) {
  return (
    <Text style={{ fontSize: size, fontWeight: '700', fontVariant: ['tabular-nums'], color: direction === 'in' ? colors.brand : colors.ink }}>
      {direction === 'in' ? '+ ' : direction === 'out' ? '− ' : ''}{money(value)}
    </Text>
  )
}

export function Loading() {
  return <ActivityIndicator style={{ marginVertical: 32 }} color={colors.brand} accessibilityLabel="Loading" />
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <View style={{ alignItems: 'center', paddingVertical: 28 }}>
      <Text style={{ fontWeight: '600', fontSize: 15, color: colors.ink }}>{title}</Text>
      {children && <Text style={{ marginTop: 4, color: colors.muted, textAlign: 'center' }}>{children}</Text>}
    </View>
  )
}

export function QrCode({ value, size = 220 }: { value: string; size?: number }) {
  return (
    <View accessibilityLabel="Pix QR code" style={{ padding: 12, backgroundColor: colors.white, borderRadius: 16, alignSelf: 'center' }}>
      <QRCodeSvg value={value} size={size} />
    </View>
  )
}

export function CopyButton({ value, label = 'Copy' }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <Button
      variant="secondary"
      title={copied ? 'Copied' : label}
      onPress={async () => {
        await Clipboard.setStringAsync(value)
        setCopied(true)
        setTimeout(() => setCopied(false), 1500)
      }}
    />
  )
}

export const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.canvas },
  screen: { padding: 20, paddingBottom: 40 },
  title: { fontSize: 26, fontWeight: '800', color: colors.ink, letterSpacing: -0.5 },
  subtitle: { marginTop: 4, fontSize: 14, color: colors.muted },
  button: {
    minHeight: 50, borderRadius: 14, paddingHorizontal: 18, flexDirection: 'row', gap: 8, alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: { fontSize: 16, fontWeight: '700' },
  label: { marginBottom: 6, fontSize: 14, fontWeight: '600', color: '#334155' },
  input: {
    minHeight: 50, borderRadius: 14, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.white,
    paddingHorizontal: 14, fontSize: 16, color: colors.ink,
  },
  note: { marginTop: 6, fontSize: 12, color: colors.muted },
  card: { backgroundColor: colors.white, borderRadius: 20, borderWidth: 1, borderColor: colors.line, padding: 18, marginBottom: 16 },
  notice: { borderRadius: 14, padding: 14, marginBottom: 14 },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 9, gap: 12 },
  rowLabel: { color: colors.muted, fontSize: 14 },
  rowValue: { color: colors.ink, fontSize: 14, fontWeight: '600', flexShrink: 1, textAlign: 'right' },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: colors.ink, marginBottom: 10 },
})
