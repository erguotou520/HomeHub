import { useEffect, useState } from 'react'
import { api } from '../api/client'

interface MonitorConfig {
  frigate_url?: string
  note?: string
  [key: string]: unknown
}

export default function Monitor() {
  const [config, setConfig] = useState<MonitorConfig>({ frigate_url: '' })
  const [meta, setMeta] = useState<{ implemented: boolean; note: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    api
      .get<{ config: MonitorConfig; implemented: boolean; note: string }>('/api/monitor/config')
      .then((r) => {
        setConfig({ frigate_url: '', ...r.config })
        setMeta({ implemented: r.implemented, note: r.note })
      })
      .catch((e: Error) => setError(e.message))
  }, [])

  async function save(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await api.put('/api/monitor/config', config)
      setSaved(true)
      setTimeout(() => setSaved(false), 2500)
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败')
    }
  }

  return (
    <div>
      <div className="card">
        <h2>监控（预留）</h2>
        <div className="empty">
          <p>监控模块本期仅保留入口与配置骨架，不实现播放。</p>
          {meta && <p className="muted">{meta.note}</p>}
          <p className="muted">
            后续将对接 Frigate（或自研录像）：服务端出聚合数据 / 代理地址，客户端负责渲染。
          </p>
        </div>
      </div>

      <div className="card">
        <h2>Frigate 地址配置</h2>
        <form onSubmit={save}>
          <div className="field">
            <label>Frigate 地址（保存后暂不连接）</label>
            <input
              value={config.frigate_url ?? ''}
              placeholder="http://10.0.0.5:5000"
              onChange={(e) => setConfig({ ...config, frigate_url: e.target.value })}
            />
          </div>
          <div className="row">
            <button type="submit">保存</button>
            {saved && <span className="badge ok">已保存</span>}
          </div>
        </form>
      </div>

      {error && <div className="error">{error}</div>}
    </div>
  )
}
