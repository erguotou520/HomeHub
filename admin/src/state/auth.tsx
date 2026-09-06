import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api, getToken, setToken } from '../api/client'

interface AuthState {
  token: string | null
  ready: boolean
  login: (password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(getToken())
  const [ready, setReady] = useState(false)

  useEffect(() => {
    // Validate the stored token once on boot.
    if (!token) {
      setReady(true)
      return
    }
    api
      .get<{ sub: string }>('/api/admin/me')
      .then(() => setReady(true))
      .catch(() => {
        setToken(null)
        setTokenState(null)
        setReady(true)
      })
  }, [token])

  const login = useCallback(async (password: string) => {
    const res = await api.post<{ token: string }>('/api/admin/login', { password })
    setToken(res.token)
    setTokenState(res.token)
  }, [])

  const logout = useCallback(() => {
    setToken(null)
    setTokenState(null)
  }, [])

  const value = useMemo(() => ({ token, ready, login, logout }), [token, ready, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
