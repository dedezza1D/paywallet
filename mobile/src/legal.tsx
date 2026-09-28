import * as WebBrowser from 'expo-web-browser'
import { Text } from 'react-native'
import { WEB_URL } from './platform'
import { colors } from './ui'

export function openLegal(document: 'terms' | 'privacy') {
  return WebBrowser.openBrowserAsync(`${WEB_URL}/legal/${document}`)
}

export function LegalLink({ document, children }: { document: 'terms' | 'privacy'; children: string }) {
  return (
    <Text accessibilityRole="link" onPress={() => void openLegal(document)} style={{ color: colors.brand, fontWeight: '700' }}>
      {children}
    </Text>
  )
}
