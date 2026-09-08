import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { DirStat, Stats, TaskStatus } from '../api/types'

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

function Stat({
  label,
  value,
  hint,
  to,
  tone,
}: {
  label: string
  value: string | number
  hint?: string
  to?: string
  tone?: 'ok' | 'warn' | 'err'
}) {
  const body = (
    <>
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {hint && <div className="hint">{hint}</div>}
    </>
  )
  return to ? (
    <Link to={to} className={`stat link ${tone ? tone : ''}`}>
      {body}
      <span className="stat-arrow">→</span>
    </Link>
  ) : (
    <div className={`stat ${tone ? tone : ''}`}>{body}</div>
  )
}

const QUEUE_LABELS: Record<string, string> = {
  scan: '扫描',
  thumb: '缩略图',
  compress: '压缩',
  geo: '地理',
  detect: 'AI 识别',
  dedup: '去重',
  probe: '视频探测',
  trash_purge: '回收站清理',
  backup: '备份',
  dedup_scan: '重复扫描',
}

export default function Home() {
  const [stats, setStats] = useState<Stats | null>(null)
  const [tasks, setTasks] = useState<TaskStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    Promise.all([
      api.get<Stats>('/api/admin/stats'),
      api.get<TaskStatus>('/api/admin/tasks'),
    ])
      .then(([s, t]) => {
        setStats(s)
        setTasks(t)
      })
      .catch((e: Error) => setError(e.message))
  }, [])

  async function rescan() {
    if (!confirm('对全部目录执行一次全量重扫？')) return
    setBusy(true)
    try {
      await api.post('/api/admin/tasks/rescan', { full: true })
      const t = await api.get<TaskStatus>('/api/admin/tasks')
      setTasks(t)
    } catch (e) {
      setError(e instanceof Error ? e.message : '触发失败')
    } finally {
      setBusy(false)
    }
  }

  if (error) return <div className="error">{error}</div>
  if (!stats) return <div className="spinner">加载中…</div>

  const activeQueues = (tasks?.queues ?? []).filter((q) => q.pending > 0 || q.running > 0 || q.failed > 0)

  return (
    <div>
      <div className="grid-stats">
        <Stat label="照片" value={stats.photos} to="/album" />
        <Stat label="视频" value={stats.videos ?? 0} to="/album" />
        <Stat label="已打标" value={stats.tagged} hint="AI 识别覆盖" to="/search" />
        <Stat label="人物" value={stats.people} hint={`${stats.faces} 张人脸`} to="/album" />
        <Stat
          label="回收站"
          value={stats.trash.count}
          hint={formatBytes(stats.trash.bytes)}
          to="/trash"
          tone={stats.trash.count > 0 ? 'warn' : undefined}
        />
        <Stat
          label="原图保护"
          value={stats.originals.count}
          hint={formatBytes(stats.originals.bytes)}
          to="/system"
        />
      </div>

      <div className="two-col">
        <div className="card">
          <h2>存储分布</h2>
          <table>
            <thead>
              <tr>
                <th>目录</th>
                <th>标记</th>
                <th>照片</th>
                <th>占用</th>
              </tr>
            </thead>
            <tbody>
              {stats.dirs.map((d: DirStat) => {
                const max = Math.max(...stats.dirs.map((x) => x.total_bytes), 1)
                return (
                  <tr key={d.id}>
                    <td>
                      <div className="dir-name">{d.name}</div>
                      <div className="dir-bar">
                        <div
                          className="dir-bar-fill"
                          style={{ width: `${Math.max((d.total_bytes / max) * 100, 2)}%` }}
                        />
                      </div>
                    </td>
                    <td>
                      {d.marks.map((m) => (
                        <span key={m} className="badge">
                          {m}
                        </span>
                      ))}
                      {!d.enabled && <span className="badge mute">停用</span>}
                    </td>
                    <td>{d.photo_count}</td>
                    <td className="muted">{formatBytes(d.total_bytes)}</td>
                  </tr>
                )
              })}
              {stats.dirs.length === 0 && (
                <tr>
                  <td colSpan={4} className="empty">
                    还没有登记目录，去
                    <Link to="/dirs"> 目录管理 </Link>
                    添加
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="card">
          <h2>任务队列</h2>
          {tasks && (
            <div className="queue-summary">
              <div className="queue-kpi">
                <span className="queue-kpi-num">{tasks.running}</span>
                <span className="queue-kpi-label">运行中</span>
              </div>
              <div className="queue-kpi">
                <span className="queue-kpi-num">
                  {tasks.queues.reduce((a, q) => a + q.pending, 0)}
                </span>
                <span className="queue-kpi-label">排队</span>
              </div>
              <div className="queue-kpi">
                <span className="queue-kpi-num warn">
                  {tasks.queues.reduce((a, q) => a + q.failed, 0)}
                </span>
                <span className="queue-kpi-label">失败</span>
              </div>
              <div className="queue-kpi">
                <span className="queue-kpi-num">{tasks.throughput_5min}</span>
                <span className="queue-kpi-label">5 分钟吞吐</span>
              </div>
            </div>
          )}
          {activeQueues.length > 0 && (
            <table>
              <tbody>
                {activeQueues.map((q) => (
                  <tr key={q.kind}>
                    <td>{QUEUE_LABELS[q.kind] ?? q.kind}</td>
                    <td>
                      {q.running > 0 && <span className="badge ok">运行 {q.running}</span>}
                      {q.pending > 0 && <span className="badge">等待 {q.pending}</span>}
                      {q.failed > 0 && <span className="badge err">失败 {q.failed}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {activeQueues.length === 0 && (
            <div className="empty" style={{ padding: 20 }}>
              队列空闲，一切正常
            </div>
          )}
          <div className="row" style={{ marginTop: 10 }}>
            <button className="ghost small" disabled={busy} onClick={rescan}>
              {busy ? '触发中…' : '全量重扫'}
            </button>
            <Link to="/tasks" className="btn ghost small">
              任务中心 →
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}
