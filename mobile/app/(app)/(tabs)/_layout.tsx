import { Tabs } from 'expo-router/js-tabs'
import { History, Home, QrCode, Settings } from 'lucide-react-native'
import { colors } from '../../../src/ui'

export default function TabsLayout() {
  return (
    <Tabs
      screenOptions={{
        tabBarActiveTintColor: colors.brand,
        tabBarInactiveTintColor: '#64748b',
        headerShadowVisible: false,
        headerStyle: { backgroundColor: colors.canvas },
        headerTitleStyle: { fontWeight: '700' },
      }}
    >
      <Tabs.Screen name="index" options={{ title: 'Home', headerShown: false, tabBarIcon: ({ color }) => <Home color={color} size={22} /> }} />
      <Tabs.Screen name="pix" options={{ title: 'Pix', tabBarIcon: ({ color }) => <QrCode color={color} size={22} /> }} />
      <Tabs.Screen name="activity" options={{ title: 'Activity', tabBarIcon: ({ color }) => <History color={color} size={22} /> }} />
      <Tabs.Screen name="settings" options={{ title: 'Settings', tabBarIcon: ({ color }) => <Settings color={color} size={22} /> }} />
    </Tabs>
  )
}
