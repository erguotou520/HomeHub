import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { FailedTask, RuntimeSettings, TaskStatus } from '../api/types'

const KIND_LABEL: Record<string, string> = {
  scan: '目录扫描',
  thumb: '缩略图',
  detect_object: '物体识别',
  detect_scene: '场景识别',
  detect_face: '人像检测',
  compress: '无损压缩',
  geo: 'EXIF/GPS',
  dedup_scan: '全库去重',
  clean_trash: '回收站清理',
  audit_retention: '审计清理',
  health_check: '健康检查',
  peer_sync: 'WG 设备同步',
}

export default function Tasks() {
  const [status, setStatus] = useState<TaskStatus | null>(null)
  const [failed, setFailed] = useState<FailedTask[]>([])
  const [settings, setSettings] = useState<RuntimeSettings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selectedDir, setSelectedDir] = useState<string>('')

  async function load() {
    try {
      const [s, f, st] = await Promise.all([
        api.get<TaskStatus>('/api/admin/tasks'),
        api.get<{ tasks: FailedTask[] }>('/api/admin/tasks/failed'),
        api.get<RuntimeSettings>('/api/admin/settings'),
      ])
      setStatus(s)
      setFailed(f.tasks)
      setSettings(st)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }

  useEffect(() => {
    void load()
    const timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [])

  async function act(fn: () => Promise<unknown>) {
    setError(null)
    try {
      await fn()
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败')
    }
  }

  // 只提交本页负责的 tasks 段：接口是深合并的，带上整份快照会把别的页面
  // 在这期间改过的配置一起退回去。
  async function saveSettings() {
    if (!settings) return
    await act(() => api.put('/api/admin/settings', { tasks: settings.tasks }))
  }

  function patchTasks(patch: Partial<RuntimeSettings['tasks']>) {
    setSettings((s) => (s ? { ...s, tasks: { ...s.tasks, ...patch } } : s))
  }

  return (
    <div>
      {error && <div className="error">{error}</div>}

      <div className="grid-stats">
        <div className="stat">
          <div className="label">运行中</div>
          <div className="value">{status?.running ?? 0}</div>
        </div>
        <div className="stat">
          <div className="label">待处理</div>
          <div className="value">{status?.queues.reduce((a, q) => a + q.pending, 0) ?? 0}</div>
        </div>
        <div className="stat">
          <div className="label">失败</div>
          <div className="value">{status?.queues.reduce((a, q) => a + q.failed, 0) ?? 0}</div>
        </div>
        <div className="stat">
          <div className="label">近 5 分钟完成</div>
          <div className="value">{status?.throughput_5min ?? 0}</div>
        </div>
      </div>

      {status?.progress && status.progress.length > 0 && (
        <div className="card">
          <h2>识别进度</h2>
          <div className="row">
            <div className="stat">
              <div className="label">媒体总量</div>
              <div className="value">
                {status.progress.reduce((a, p) => a + p.total, 0).toLocaleString()}
              </div>
            </div>
            <div className="stat">
              <div className="label">已识别</div>
              <div className="value">
                {status.progress.reduce((a, p) => a + p.embedded, 0).toLocaleString()}
              </div>
            </div>
            <div className="stat">
              <div className="label">识别待处理</div>
              <div className="value">{(status.recognition_pending ?? 0).toLocaleString()}</div>
            </div>
            <div className="stat">
              <div className="label">识别失败</div>
              <div className="value">{status.recognition_failed ?? 0}</div>
            </div>
          </div>
          {status.progress.map((p) => {
            const pct = p.total > 0 ? Math.round((p.embedded / p.total) * 100) : 0
            return (
              <div key={p.dir_id} className="row" style={{ alignItems: 'center', gap: 12 }}>
                <span style={{ width: 120 }}>{p.name}</span>
                <div className="progress-track" style={{ flex: 1 }}>
                  <div className="progress-fill" style={{ width: `${pct}%` }} />
                </div>
                <span className="muted">
                  {p.embedded.toLocaleString()} / {p.total.toLocaleString()}（{pct}%）
                </span>
              </div>
            )
          })}
          <div className="muted" style={{ marginTop: 8 }}>
            说明：总量 = 扫描已发现的媒体数，随扫描推进增长；已识别 = 已生成 CLIP 向量（缩略图 + 对象/场景/人脸/语义全部完成）。
          </div>
        </div>
      )}

      <div className="card">
        <h2>队列状态</h2>
        <div className="toolbar">
          <button onClick={() => act(() => api.post('/api/admin/tasks/rescan', { full: true }))}>
            全量重扫
          </button>
          <button
            className="ghost"
            onClick={() => act(() => api.post('/api/admin/tasks/rescan', { full: false }))}
          >
            增量扫描
          </button>
          <input
            placeholder="目录 ID（可选）"
            value={selectedDir}
            onChange={(e) => setSelectedDir(e.target.value)}
          />
          <button
            className="ghost"
            onClick={() =>
              act(() =>
                api.post('/api/admin/tasks/rescan', {
                  full: true,
                  dir_id: selectedDir ? Number(selectedDir) : undefined,
                }),
              )
            }
          >
            扫描指定目录
          </button>
          <button className="ghost" onClick={() => act(() => api.post('/api/admin/tasks/retry'))}>
            重试失败任务
          </button>
          <button className="danger" onClick={() => act(() => api.del('/api/admin/tasks/failed'))}>
            清空失败任务
          </button>
        </div>
        <table>
          <thead>
            <tr>
              <th>类型</th>
              <th>待处理</th>
              <th>运行中</th>
              <th>失败</th>
              <th>并发上限</th>
            </tr>
          </thead>
          <tbody>
            {status?.queues.map((q) => (
              <tr key={q.kind}>
                <td>
                  {KIND_LABEL[q.kind] ?? q.kind} <span className="muted">({q.kind})</span>
                </td>
                <td>{q.pending}</td>
                <td>{q.running}</td>
                <td>{q.failed > 0 ? <span className="badge err">{q.failed}</span> : 0}</td>
                <td>{q.concurrency}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card">
        <h2>资源控制</h2>
        {settings && (
          <>
            <div className="row">
              <div className="field" style={{ width: 120 }}>
                <label>CPU 并发</label>
                <input
                  type="number"
                  min={1}
                  value={settings.tasks.concurrency.cpu}
                  onChange={(e) =>
                    patchTasks({
                      concurrency: { ...settings.tasks.concurrency, cpu: Number(e.target.value) },
                    })
                  }
                />
              </div>
              <div className="field" style={{ width: 120 }}>
                <label>IO 并发</label>
                <input
                  type="number"
                  min={1}
                  value={settings.tasks.concurrency.io}
                  onChange={(e) =>
                    patchTasks({
                      concurrency: { ...settings.tasks.concurrency, io: Number(e.target.value) },
                    })
                  }
                />
              </div>
              <div className="field" style={{ width: 140 }}>
                <label>每秒文件数上限</label>
                <input
                  type="number"
                  min={0}
                  value={settings.tasks['rate-limit-per-sec']}
                  onChange={(e) => patchTasks({ 'rate-limit-per-sec': Number(e.target.value) })}
                />
              </div>
              <div className="field" style={{ width: 140 }}>
                <label>单文件超时（秒）</label>
                <input
                  type="number"
                  min={5}
                  value={settings.tasks['file-timeout-secs']}
                  onChange={(e) => patchTasks({ 'file-timeout-secs': Number(e.target.value) })}
                />
              </div>
              <div className="field" style={{ width: 120 }}>
                <label>最大重试</label>
                <input
                  type="number"
                  min={1}
                  value={settings.tasks['max-attempts']}
                  onChange={(e) => patchTasks({ 'max-attempts': Number(e.target.value) })}
                />
              </div>
              <div className="field" style={{ width: 120 }}>
                <label>工作线程</label>
                <input
                  type="number"
                  min={1}
                  value={settings.tasks['max-workers']}
                  onChange={(e) => patchTasks({ 'max-workers': Number(e.target.value) })}
                />
              </div>
            </div>

            <div className="row">
              <label className="row" style={{ gap: 6, margin: 0 }}>
                <input
                  type="checkbox"
                  style={{ width: 'auto' }}
                  checked={settings.tasks['work-window'].enabled}
                  onChange={(e) =>
                    patchTasks({
                      'work-window': { ...settings.tasks['work-window'], enabled: e.target.checked },
                    })
                  }
                />
                仅在工作时段全速执行重任务
              </label>
              <div className="field" style={{ width: 110, marginBottom: 0 }}>
                <label>开始</label>
                <input
                  value={settings.tasks['work-window'].start}
                  onChange={(e) =>
                    patchTasks({
                      'work-window': { ...settings.tasks['work-window'], start: e.target.value },
                    })
                  }
                />
              </div>
              <div className="field" style={{ width: 110, marginBottom: 0 }}>
                <label>结束</label>
                <input
                  value={settings.tasks['work-window'].end}
                  onChange={(e) =>
                    patchTasks({
                      'work-window': { ...settings.tasks['work-window'], end: e.target.value },
                    })
                  }
                />
              </div>
              <div className="field" style={{ width: 140, marginBottom: 0 }}>
                <label>全量重扫</label>
                <select
                  value={settings.tasks['full-rescan']}
                  onChange={(e) => patchTasks({ 'full-rescan': e.target.value })}
                >
                  <option value="off">关闭</option>
                  <option value="daily">每天</option>
                  <option value="weekly">每周</option>
                </select>
              </div>
              <div className="field" style={{ width: 100, marginBottom: 0 }}>
                <label>重扫时刻</label>
                <input
                  type="number"
                  min={0}
                  max={23}
                  value={settings.tasks['full-rescan-hour']}
                  onChange={(e) => patchTasks({ 'full-rescan-hour': Number(e.target.value) })}
                />
              </div>
            </div>

            <div className="row" style={{ marginTop: 12 }}>
              <button onClick={saveSettings}>保存参数</button>
              <span className="muted">参数立即生效，无需重启</span>
            </div>
          </>
        )}
      </div>

      <div className="card">
        <h2>手动执行维护任务</h2>
        <div className="row">
          {['clean_trash', 'audit_retention', 'dedup_scan', 'health_check', 'peer_sync'].map((k) => (
            <button key={k} className="ghost" onClick={() => act(() => api.post('/api/admin/tasks/run', { kind: k }))}>
              {KIND_LABEL[k] ?? k}
            </button>
          ))}
        </div>
      </div>

      <div className="card">
        <h2>失败任务</h2>
        {failed.length === 0 && <div className="empty">暂无失败任务</div>}
        <table>
          <tbody>
            {failed.map((t) => (
              <tr key={t.id}>
                <td>{KIND_LABEL[t.kind] ?? t.kind}</td>
                <td className="muted">{t.payload}</td>
                <td>{t.attempts} 次</td>
                <td className="muted">{t.error}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
