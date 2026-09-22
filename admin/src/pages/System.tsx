import { useEffect, useState } from 'react'
import { api, formatBytes, formatTime } from '../api/client'
import type { Alert, BackupStatus, DuplicateGroup, RuntimeSettings, SystemInfo } from '../api/types'

type Tab = 'overview' | 'alerts' | 'dedup' | 'backup'

export default function SystemPage() {
  const [info, setInfo] = useState<SystemInfo | null>(null)
  const [settings, setSettings] = useState<RuntimeSettings | null>(null)
  const [dups, setDups] = useState<DuplicateGroup[]>([])
  const [backups, setBackups] = useState<BackupStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('overview')

  async function load() {
    try {
      const [i, s] = await Promise.all([
        api.get<SystemInfo>('/api/admin/system'),
        api.get<RuntimeSettings>('/api/admin/settings'),
      ])
      setInfo(i)
      setSettings(s)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }

  useEffect(() => {
    void load()
  }, [])

  useEffect(() => {
    if (tab === 'dedup') {
      api
        .get<{ groups: DuplicateGroup[] }>('/api/admin/duplicates')
        .then((r) => setDups(r.groups))
        .catch((e: Error) => setError(e.message))
    }
    if (tab === 'backup') void loadBackups()
  }, [tab])

  async function loadBackups() {
    try {
      setBackups(await api.get<BackupStatus>('/api/admin/backups'))
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载备份失败')
    }
  }

  async function runBackup() {
    setError(null)
    try {
      await api.post('/api/admin/backups/run')
      await loadBackups()
    } catch (e) {
      setError(e instanceof Error ? e.message : '备份失败')
    }
  }

  function patchBackup(patch: Partial<RuntimeSettings['backup']>) {
    setSettings((s) => (s ? { ...s, backup: { ...s.backup, ...patch } } : s))
  }

  async function saveAlerts() {
    if (!settings) return
    setError(null)
    try {
      await api.put('/api/admin/settings', settings)
      await load()
      alert('已保存')
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败')
    }
  }

  async function saveBackup() {
    if (!settings) return
    setError(null)
    try {
      await api.put('/api/admin/settings', settings)
      await load()
      await loadBackups()
      alert('已保存')
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败')
    }
  }

  async function trashGroup(fp: string) {
    if (!confirm('除第一张外，其余重复照片将移入回收站，确定继续？')) return
    await api.post('/api/admin/duplicates/trash', { fingerprint: fp })
    const r = await api.get<{ groups: DuplicateGroup[] }>('/api/admin/duplicates')
    setDups(r.groups)
  }

  function patchAlerts(patch: Partial<RuntimeSettings['alerts']>) {
    setSettings((s) => (s ? { ...s, alerts: { ...s.alerts, ...patch } } : s))
  }

  // 磁盘水位带上状态语义：用用户自己配的告警阈值当 warn 线，接近写满时升级为 err。
  // 否则「90.8%」和「v0.1.0」长得一模一样，告警色就白定义了。
  const diskWarnAt = settings?.alerts['disk-usage-percent'] ?? 85
  const diskPct = info?.disk_usage_percent ?? null
  const diskTone = diskPct == null ? '' : diskPct >= 95 ? 'err' : diskPct >= diskWarnAt ? 'warn' : ''

  return (
    <div>
      {error && <div className="error">{error}</div>}

      <div className="toolbar">
        <span className="seg" role="group" aria-label="系统信息分区">
          {(
            [
              ['overview', '概览'],
              ['alerts', '告警与通知'],
              ['dedup', '重复照片'],
              ['backup', '数据库备份'],
            ] as const
          ).map(([t, label]) => (
            <button
              key={t}
              type="button"
              className={`seg-btn${tab === t ? ' on' : ''}`}
              aria-pressed={tab === t}
              onClick={() => setTab(t)}
            >
              {label}
            </button>
          ))}
        </span>
      </div>

      {tab === 'overview' && info && (
        <>
          <div className="grid-stats">
            <div className="stat">
              <div className="label">版本</div>
              <div className="value">v{info.version}</div>
            </div>
            <div className="stat">
              <div className="label">运行时长</div>
              <div className="value">{Math.floor(info.uptime_secs / 3600)} 小时</div>
            </div>
            <div className="stat">
              <div className="label">SQLite</div>
              <div className="value">{formatBytes(info.db_bytes)}</div>
            </div>
            <div className={diskTone ? `stat ${diskTone}` : 'stat'}>
              <div className="label">磁盘水位</div>
              <div className="value">
                {info.disk_usage_percent != null ? `${info.disk_usage_percent.toFixed(1)}%` : '-'}
              </div>
              {diskPct != null && <div className="hint">告警阈值 {diskWarnAt}%</div>}
            </div>
            <div className="stat">
              <div className="label">ML 后端</div>
              <div className="value">{info.ml_backend}</div>
            </div>
          </div>

          <div className="card">
            <h2>系统</h2>
            <table>
              <tbody>
                <tr>
                  <td className="muted">启动时间</td>
                  <td>{formatTime(info.started_at)}</td>
                </tr>
                <tr>
                  <td className="muted">数据目录</td>
                  <td>{info.data_dir}</td>
                </tr>
                <tr>
                  <td className="muted">未恢复告警</td>
                  <td>{info.alerts.filter((a: Alert) => !a.resolved_at).length}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </>
      )}

      {tab === 'alerts' && settings && (
        <>
          <div className="card">
            <h2>健康告警</h2>
            <div className="row">
              <label className="row" style={{ gap: 6, margin: 0 }}>
                <input
                  type="checkbox"
                  style={{ width: 'auto' }}
                  checked={settings.alerts.enabled}
                  onChange={(e) => patchAlerts({ enabled: e.target.checked })}
                />
                启用告警
              </label>
              <div className="field" style={{ width: 150, marginBottom: 0 }}>
                <label>磁盘水位阈值 %</label>
                <input
                  type="number"
                  value={settings.alerts['disk-usage-percent']}
                  onChange={(e) => patchAlerts({ 'disk-usage-percent': Number(e.target.value) })}
                />
              </div>
              <div className="field" style={{ width: 160, marginBottom: 0 }}>
                <label>连续失败任务阈值</label>
                <input
                  type="number"
                  value={settings.alerts['task-failure-threshold']}
                  onChange={(e) => patchAlerts({ 'task-failure-threshold': Number(e.target.value) })}
                />
              </div>
              <div className="field" style={{ width: 150, marginBottom: 0 }}>
                <label>通知冷却（秒）</label>
                <input
                  type="number"
                  value={settings.alerts['cooldown-secs']}
                  onChange={(e) => patchAlerts({ 'cooldown-secs': Number(e.target.value) })}
                />
              </div>
            </div>
          </div>

          <div className="card">
            <h2>通知渠道（Server酱）</h2>
            <div className="row" style={{ alignItems: 'flex-end' }}>
              <label className="row" style={{ gap: 6 }}>
                <input
                  type="checkbox"
                  style={{ width: 'auto' }}
                  checked={settings.alerts.serverchan.enabled}
                  onChange={(e) =>
                    patchAlerts({
                      serverchan: { ...settings.alerts.serverchan, enabled: e.target.checked },
                    })
                  }
                />
                启用 Server酱推送
              </label>
              <div className="field" style={{ width: 320, marginBottom: 0 }}>
                <label>SendKey（sct.ftqq.com 获取）</label>
                <input
                  type="password"
                  placeholder="SCT…"
                  value={settings.alerts.serverchan['send-key']}
                  onChange={(e) =>
                    patchAlerts({
                      serverchan: { ...settings.alerts.serverchan, 'send-key': e.target.value },
                    })
                  }
                />
              </div>
            </div>

            <div className="row" style={{ marginTop: 14 }}>
              <button onClick={saveAlerts}>保存通知设置</button>
              <button
                className="ghost"
                onClick={async () => {
                  try {
                    await api.post('/api/admin/alerts/test')
                    alert('测试告警已发送（若渠道可用）')
                  } catch (e) {
                    alert(e instanceof Error ? e.message : '发送失败')
                  }
                }}
              >
                发送测试告警
              </button>
            </div>
          </div>

          <div className="card">
            <h2>告警历史</h2>
            {info && info.alerts.length === 0 && <div className="empty">暂无告警</div>}
            <table>
              <tbody>
                {info?.alerts.map((a) => (
                  <tr key={a.id}>
                    <td style={{ width: 90 }}>
                      <span
                        className={
                          a.level === 'error' ? 'badge err' : a.level === 'warn' ? 'badge warn' : 'badge ok'
                        }
                      >
                        {a.level}
                      </span>
                    </td>
                    <td style={{ width: 110 }}>{a.kind}</td>
                    <td>{a.message}</td>
                    <td style={{ width: 170 }}>{formatTime(a.created_at)}</td>
                    <td style={{ width: 90 }}>
                      {a.resolved_at ? <span className="badge ok">已恢复</span> : <span className="badge warn">未恢复</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {tab === 'backup' && settings && (
        <>
          <div className="card">
            <h2>SQLite 定期备份</h2>
            <p className="muted" style={{ marginTop: 0, fontSize: 13 }}>
              使用 <code>VACUUM INTO</code> 生成一致且已整理的快照（PRD §6）。
              快照写入数据卷，只保留最近若干份；恢复时停服、用快照文件替换 <code>homehub.db</code> 即可。
            </p>
            <div className="row" style={{ alignItems: 'flex-end' }}>
              <label className="row" style={{ gap: 6, margin: 0 }}>
                <input
                  type="checkbox"
                  style={{ width: 'auto' }}
                  checked={settings.backup.enabled}
                  onChange={(e) => patchBackup({ enabled: e.target.checked })}
                />
                启用定期备份
              </label>
              <div className="field" style={{ width: 150, marginBottom: 0 }}>
                <label>间隔（小时）</label>
                <input
                  type="number"
                  min={1}
                  value={settings.backup['interval-hours']}
                  onChange={(e) => patchBackup({ 'interval-hours': Number(e.target.value) })}
                />
              </div>
              <div className="field" style={{ width: 150, marginBottom: 0 }}>
                <label>保留份数</label>
                <input
                  type="number"
                  min={1}
                  value={settings.backup.keep}
                  onChange={(e) => patchBackup({ keep: Number(e.target.value) })}
                />
              </div>
            </div>
            <div className="row" style={{ marginTop: 14 }}>
              <button onClick={saveBackup}>保存备份设置</button>
              <button className="ghost" onClick={runBackup}>
                立即备份
              </button>
            </div>
          </div>

          <div className="card">
            <h2>备份文件</h2>
            {backups && (
              <div className="grid-stats">
                <div className="stat">
                  <div className="label">份数</div>
                  <div className="value">{backups.items.length}</div>
                </div>
                <div className="stat">
                  <div className="label">占用</div>
                  <div className="value">{formatBytes(backups.total_bytes)}</div>
                </div>
                <div className="stat">
                  <div className="label">上次备份</div>
                  <div className="value">{formatTime(backups.last_backup_at)}</div>
                </div>
              </div>
            )}
            {backups?.last_error && <div className="error">上次失败：{backups.last_error}</div>}
            {backups && <p className="muted" style={{ fontSize: 12 }}>目录：{backups.dir}</p>}
            {backups && backups.items.length === 0 && <div className="empty">还没有备份</div>}
            {backups && backups.items.length > 0 && (
              <table>
                <thead>
                  <tr>
                    <th>文件</th>
                    <th style={{ width: 110 }}>大小</th>
                    <th style={{ width: 180 }}>时间</th>
                  </tr>
                </thead>
                <tbody>
                  {backups.items.map((b) => (
                    <tr key={b.name}>
                      <td>{b.name}</td>
                      <td>{formatBytes(b.bytes)}</td>
                      <td>{formatTime(b.created_at)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </>
      )}

      {tab === 'dedup' && (
        <div className="card">
          <h2>重复照片分组</h2>
          {dups.length === 0 && <div className="empty">没有检测到重复照片</div>}
          {dups.map((g) => (
            <div key={g.fingerprint} style={{ marginBottom: 18 }}>
              <div className="row">
                <span className={g.reason === 'file_hash' ? 'badge' : 'badge warn'}>
                  {g.reason === 'file_hash' ? '字节完全相同' : '像素相同（可能已无损压缩）'}
                </span>
                <span className="muted">{g.items.length} 个文件</span>
                <button className="ghost small" onClick={() => trashGroup(g.fingerprint)}>
                  保留第一张，其余移入回收站
                </button>
              </div>
              <div className="photo-grid" style={{ marginTop: 8 }}>
                {g.items.map((p) => (
                  <div key={p.id} className="tile" title={`${p.dir_name}/${p.rel_path}`}>
                    <img src={p.thumb_url} alt={p.name} loading="lazy" />
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
