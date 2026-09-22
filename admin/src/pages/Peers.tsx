import { useEffect, useState } from 'react'
import { api, formatBytes, formatTime } from '../api/client'
import type { AuditLog, PeerTraffic, WgPeer } from '../api/types'

export default function Peers() {
  const [peers, setPeers] = useState<WgPeer[]>([])
  const [traffic, setTraffic] = useState<PeerTraffic[]>([])
  const [logs, setLogs] = useState<AuditLog[]>([])
  const [selected, setSelected] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [newPeer, setNewPeer] = useState({ name: '', tunnel_ip: '', public_key: '' })

  async function load() {
    try {
      const [p, t] = await Promise.all([
        api.get<{ peers: WgPeer[] }>('/api/admin/peers'),
        api.get<{ traffic: PeerTraffic[] }>('/api/admin/traffic'),
      ])
      setPeers(p.peers)
      setTraffic(t.traffic)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }

  useEffect(() => {
    void load()
  }, [])

  useEffect(() => {
    if (selected === null) {
      setLogs([])
      return
    }
    api
      .get<{ logs: AuditLog[] }>(`/api/admin/peers/${selected}/logs`, { limit: 200 })
      .then((r) => setLogs(r.logs))
      .catch((e: Error) => setError(e.message))
  }, [selected])

  async function addPeer(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await api.post('/api/admin/peers', newPeer)
      setNewPeer({ name: '', tunnel_ip: '', public_key: '' })
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '添加失败')
    }
  }

  async function renamePeer(id: number) {
    const name = prompt('设备名称')
    if (!name) return
    await api.put(`/api/admin/peers/${id}`, { name, tunnel_ip: '' })
    await load()
  }

  return (
    <div>
      {error && <div className="error">{error}</div>}

      <div className="card">
        <h2>WireGuard 设备</h2>
        <table>
          <thead>
            <tr>
              <th>名称</th>
              <th>隧道 IP</th>
              <th>公钥</th>
              <th>来源</th>
              <th>最近访问</th>
              <th>请求数 / 流量</th>
              <th><span className="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            {peers.map((p) => {
              const t = traffic.find((x) => x.peer_id === p.id)
              return (
                <tr key={p.id}>
                  <td>{p.name}</td>
                  <td className="muted">{p.tunnel_ip}</td>
                  <td className="muted">{p.public_key ? `${p.public_key.slice(0, 12)}…` : '-'}</td>
                  <td>
                    <span className={p.source === 'wg-show' ? 'badge ok' : 'badge mute'}>
                      {p.source}
                    </span>
                  </td>
                  <td>{formatTime(t?.last_seen ?? p.last_seen)}</td>
                  <td>
                    {t ? `${t.requests} 次 · ${formatBytes(t.bytes)}` : '—'}
                  </td>
                  <td>
                    <div className="row" style={{ gap: 6 }}>
                      <button className="ghost small" onClick={() => setSelected(p.id)}>
                        访问日志
                      </button>
                      <button className="ghost small" onClick={() => renamePeer(p.id)}>
                        改名
                      </button>
                    </div>
                  </td>
                </tr>
              )
            })}
            {peers.length === 0 && (
              <tr>
                <td colSpan={7} className="empty">
                  还没有设备记录。设备首次访问数据面接口后会自动登记，也可手工添加。
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="card">
        <h2>手工登记设备</h2>
        <form onSubmit={addPeer}>
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <div className="field" style={{ width: 160, marginBottom: 0 }}>
              <label>设备名</label>
              <input
                value={newPeer.name}
                onChange={(e) => setNewPeer({ ...newPeer, name: e.target.value })}
                required
              />
            </div>
            <div className="field" style={{ width: 160, marginBottom: 0 }}>
              <label>隧道 IP</label>
              <input
                value={newPeer.tunnel_ip}
                onChange={(e) => setNewPeer({ ...newPeer, tunnel_ip: e.target.value })}
                required
              />
            </div>
            <div className="field" style={{ flex: 1, marginBottom: 0 }}>
              <label>公钥（可选）</label>
              <input
                value={newPeer.public_key}
                onChange={(e) => setNewPeer({ ...newPeer, public_key: e.target.value })}
              />
            </div>
            <button type="submit">添加</button>
          </div>
        </form>
        <p className="muted" style={{ fontSize: 12, marginTop: 10 }}>
          若服务端与 WG 网关同机，可在 config.yaml 打开 <code>wireguard.auto-sync</code>，
          服务端会周期解析 <code>wg show wg0 dump</code> 自动同步设备。
        </p>
      </div>

      {selected !== null && (
        <div className="card">
          <h2>设备 #{selected} 访问日志</h2>
          {logs.length === 0 && <div className="empty">暂无日志</div>}
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>方法</th>
                <th>路径</th>
                <th>状态</th>
                <th>字节</th>
              </tr>
            </thead>
            <tbody>
              {logs.map((l) => (
                <tr key={l.id}>
                  <td>{formatTime(l.created_at)}</td>
                  <td>{l.method}</td>
                  <td className="muted">{l.path}</td>
                  <td>
                    <span className={l.status >= 400 ? 'badge err' : 'badge ok'}>{l.status}</span>
                  </td>
                  <td>{formatBytes(l.bytes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
