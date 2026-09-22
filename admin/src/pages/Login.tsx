import { useRef, useState } from 'react'
import { useAuth } from '../state/auth'
import Icon from '../components/Icon'

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
    // 用 <main> 而不是 <div>：登录态下整页只有这一块内容，
    // 没有 main landmark 读屏用户会失去「跳到主内容」的落点。
    <main className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand">
          <span className="brand-mark" aria-hidden="true">
            <Icon name="brand" size={19} strokeWidth={1.7} />
          </span>
          <div>
            <h1>
              Home<span>Hub</span>
            </h1>
            <p className="login-sub">家庭数据中心 · 管理后台</p>
          </div>
        </div>
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
        <button type="submit" className="primary" disabled={busy}>
          {busy ? '登录中…' : '登录'}
        </button>
      </form>
    </main>
  )
}
