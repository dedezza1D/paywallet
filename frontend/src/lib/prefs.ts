import { useSyncExternalStore } from 'react'

// Per-browser display preference; it holds no personal data.
const HIDE_KEY = 'pw.hideBalance'
const listeners = new Set<() => void>()

function read(): boolean {
  try {
    return window.localStorage.getItem(HIDE_KEY) === '1'
  } catch {
    return false
  }
}

export function useHideBalance(): [boolean, () => void] {
  const hidden = useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    read,
  )
  const toggle = () => {
    try {
      window.localStorage.setItem(HIDE_KEY, hidden ? '0' : '1')
    } catch {
      // Storage unavailable: the toggle simply does not persist.
    }
    listeners.forEach((listener) => listener())
  }
  return [hidden, toggle]
}
