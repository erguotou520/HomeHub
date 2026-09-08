import { useRef, useState } from 'react'
import { useAuth } from '../state/auth'

export default function Login() {
  const { login } = useAuth()
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const pwRef = useRef<HTMLInputElement>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (busy) return
    // Stay enabled until the request starts; validate instead of disabling.
    if (!password) {
      setError('请输入管理员密码')
      pwRef.current?.focus()
      return
    }
    setBusy(true)
    setError(null)
    try {
      await login(password)
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败')
      pwRef.current?.focus()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <h1>
          Home<span style={{ background: 'var(--grad)', WebkitBackgroundClip: 'text', backgroundClip: 'text', color: 'transparent' }}>Hub</span>
        </h1>
        <p className="muted" style={{ marginTop: 0, marginBottom: 18 }}>
          管理后台 · 请输入管理员密码
        </p>
        <div className="field">
          <label htmlFor="pw">管理员密码</label>
          <input
            ref={pwRef}
            id="pw"
            name="password"
            type="password"
            autoComplete="current-password"
            spellCheck={false}
            value={password}
            autoFocus
            aria-describedby={error ? 'login-error' : undefined}
            aria-invalid={error ? true : undefined}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        {error && (
          <div className="error" id="login-error" role="alert" aria-live="polite">
            {error}
          </div>
        )}
        <button type="submit" disabled={busy} style={{ width: '100%', marginTop: 8 }}>
          {busy ? '登录中…' : '登 录'}
        </button>
      </form>
    </div>
  )
}
