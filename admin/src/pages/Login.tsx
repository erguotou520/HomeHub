import { useState } from 'react'
import { useAuth } from '../state/auth'

export default function Login() {
  const { login } = useAuth()
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(password)
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <h1>
          Home<span style={{ color: 'var(--accent)' }}>Hub</span>
        </h1>
        <p className="muted" style={{ marginTop: 0, marginBottom: 18 }}>
          管理后台 · 请输入管理员密码
        </p>
        <div className="field">
          <label htmlFor="pw">管理员密码</label>
          <input
            id="pw"
            type="password"
            value={password}
            autoFocus
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        {error && <div className="error">{error}</div>}
        <button type="submit" disabled={busy || !password} style={{ width: '100%', marginTop: 8 }}>
          {busy ? '登录中…' : '登 录'}
        </button>
      </form>
    </div>
  )
}
