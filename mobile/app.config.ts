import type { ExpoConfig } from 'expo/config'

// Store identifiers are set per environment; the defaults are placeholders until the store accounts exist.
const appId = process.env.APP_ID ?? 'br.com.paywallet.app'

const config: ExpoConfig = {
  name: 'PayWallet',
  slug: 'paywallet',
  scheme: 'paywallet',
  version: '0.1.0',
  orientation: 'portrait',
  icon: './assets/icon.png',
  userInterfaceStyle: 'light',
  ios: {
    bundleIdentifier: appId,
    supportsTablet: false,
    infoPlist: {
      // Only standard encryption (TLS, the OS keychain): exempt from US export documentation.
      ITSAppUsesNonExemptEncryption: false,
    },
  },
  android: {
    package: appId,
    // Keeps session tokens and device data out of cloud backups.
    allowBackup: false,
    adaptiveIcon: {
      backgroundColor: '#0b8a4f',
      foregroundImage: './assets/android-icon-foreground.png',
      backgroundImage: './assets/android-icon-background.png',
      monochromeImage: './assets/android-icon-monochrome.png',
    },
  },
  web: { favicon: './assets/favicon.png' },
  plugins: [
    'expo-router',
    'expo-secure-store',
    'expo-web-browser',
    ['expo-local-authentication', { faceIDPermission: 'PayWallet uses Face ID to unlock the app and confirm payments.' }],
  ],
  experiments: { typedRoutes: true },
}

export default config
