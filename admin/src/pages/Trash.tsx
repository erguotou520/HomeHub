import { useEffect, useState } from 'react'
import { api, formatBytes, formatTime } from '../api/client'
import type { TrashEntry } from '../api/types'

export default function Trash() {
  const [entries, setEntries] = useState<TrashEntry[]>([])
  const [stats, setStats] = useState<{ count: number; bytes: number; retention_days: number } | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function load() {
    try {
      const [list, st] = await Promise.all([
        api.get<{ entries: TrashEntry[] }>('/api/trash'),
        api.get<{ count: number; bytes: number; retention_days: number }>('/api/trash/stats'),
      ])
      setEntries(list.entries)
      setStats(st)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }

  useEffect(() => {
    void load()
  }, [])

  async function restore(id: number) {
    setError(null)
    try {
      await api.post(`/api/trash/${id}/restore`)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : '还原失败')
    }
  }

  async function purge(id: number) {
    if (!confirm('彻底删除后无法恢复，确定继续？')) return
    setError(null)
    try {
      await api.del(`/api/trash/${id}`)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败')
    }
  }

  async function empty() {
    if (!confirm('确定清空回收站？该操作不可撤销。')) return
    await api.del('/api/trash/empty')
    await load()
  }

  return (
    <div>
      {error && <div className="error">{error}</div>}

      <div className="grid-stats">
        <div className="stat">
          <div className="label">条目</div>
          <div className="value">{stats?.count ?? 0}</div>
        </div>
        <div className="stat">
          <div className="label">占用</div>
          <div className="value">{formatBytes(stats?.bytes ?? 0)}</div>
        </div>
        <div className="stat">
          <div className="label">保留期</div>
          <div className="value">{stats?.retention_days ?? '-'} 天</div>
        </div>
      </div>

      <div className="card">
        <div className="toolbar">
          <button className="danger" onClick={empty} disabled={!entries.length}>
            清空回收站
          </button>
          <span className="muted">超过保留期的条目由清理任务自动彻底删除</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>原路径</th>
              <th>目录</th>
              <th>大小</th>
              <th>删除时间</th>
              <th>到期时间</th>
              <th><span className="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            {entries.map((e) => (
              <tr key={e.id}>
                <td>{e.rel_path}</td>
                <td className="muted">{e.dir_name}</td>
                <td>{formatBytes(e.size)}</td>
                <td>{formatTime(e.trashed_at)}</td>
                <td>{formatTime(e.purged_due)}</td>
                <td>
                  <div className="row" style={{ gap: 6 }}>
                    <button className="small" onClick={() => restore(e.id)}>
                      还原
                    </button>
                    <button className="danger small" onClick={() => purge(e.id)}>
                      彻底删除
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {entries.length === 0 && (
              <tr>
                <td colSpan={6} className="empty">
                  回收站是空的
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
