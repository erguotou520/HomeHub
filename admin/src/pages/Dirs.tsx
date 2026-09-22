import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { DirInput, DirStat } from '../api/types'

const ALL_MARKS = ['album', 'video', 'music', 'document', 'none']
const MARK_LABELS: Record<string, string> = {
  album: '相册',
  video: '视频',
  music: '音乐',
  document: '文档',
  none: '无归属',
}
const DEFAULT_IGNORE = ['@eaDir', '.thumbnails', '#recycle', '.stfolder', '.originals']

const empty: DirInput = {
  name: '',
  path: '',
  marks: ['album'],
  ignore: [...DEFAULT_IGNORE],
  enabled: true,
}

type FsListing = {
  path: string
  parent: string | null
  entries: { name: string; path: string }[]
}

function FsPicker({
  onPick,
  onClose,
}: {
  onPick: (path: string) => void
  onClose: () => void
}) {
  const [listing, setListing] = useState<FsListing | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function load(path?: string) {
    setError(null)
    try {
      const q = path ? `?path=${encodeURIComponent(path)}` : ''
      setListing(await api.get<FsListing>(`/api/admin/fs${q}`))
    } catch (e) {
      setError(e instanceof Error ? e.message : '浏览失败')
    }
  }

  useEffect(() => {
    void load()
  }, [])

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.4)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 100,
      }}
      onClick={onClose}
    >
      <div
        className="card"
        style={{ width: 520, maxHeight: '70vh', overflow: 'auto', margin: 0 }}
        onClick={(e) => e.stopPropagation()}
      >
        <h2>选择服务器目录</h2>
        {error && <div className="error">{error}</div>}
        {listing && (
          <>
            <div className="row" style={{ alignItems: 'center', gap: 8 }}>
              <code style={{ flex: 1 }}>{listing.path}</code>
              <button
                className="ghost small"
                disabled={!listing.parent}
                onClick={() => load(listing.parent ?? undefined)}
              >
                上一级
              </button>
              <button className="small" onClick={() => onPick(listing.path)}>
                选这个目录
              </button>
            </div>
            <table>
              <tbody>
                {listing.entries.map((e) => (
                  <tr key={e.path} style={{ cursor: 'pointer' }} onClick={() => load(e.path)}>
                    <td>{e.name}</td>
                  </tr>
                ))}
                {listing.entries.length === 0 && (
                  <tr>
                    <td className="empty">没有子目录</td>
                  </tr>
                )}
              </tbody>
            </table>
          </>
        )}
        <div className="row">
          <button className="ghost" onClick={onClose}>
            取消
          </button>
        </div>
      </div>
    </div>
  )
}

export default function Dirs() {
  const [dirs, setDirs] = useState<DirStat[]>([])
  const [form, setForm] = useState<DirInput>(empty)
  const [editing, setEditing] = useState<number | null>(null)
  const [picking, setPicking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function load() {
    try {
      const res = await api.get<{ dirs: DirStat[] }>('/api/admin/dirs')
      setDirs(res.dirs)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }

  useEffect(() => {
    void load()
  }, [])

  function toggleMark(mark: string) {
    setForm((f) => {
      const has = f.marks.includes(mark)
      let marks = has ? f.marks.filter((m) => m !== mark) : [...f.marks, mark]
      if (marks.length === 0) marks = ['none']
      if (mark !== 'none' && !has) marks = marks.filter((m) => m !== 'none')
      if (mark === 'none') marks = ['none']
      return { ...f, marks }
    })
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (editing) await api.put(`/api/admin/dirs/${editing}`, form)
      else await api.post('/api/admin/dirs', form)
      setForm(empty)
      setEditing(null)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败')
    } finally {
      setBusy(false)
    }
  }

  async function remove(id: number) {
    if (!confirm('确定删除该目录登记吗？磁盘上的文件不会被删除。')) return
    await api.del(`/api/admin/dirs/${id}`)
    await load()
  }

  async function toggleEnabled(d: DirStat) {
    await api.post(`/api/admin/dirs/${d.id}/enabled`, { enabled: !d.enabled })
    await load()
  }

  return (
    <div>
      {error && <div className="error">{error}</div>}
      {picking && (
        <FsPicker
          onPick={(p) => {
            setForm((f) => ({ ...f, path: p }))
            setPicking(false)
          }}
          onClose={() => setPicking(false)}
        />
      )}

      <div className="card">
        <h2>{editing ? `编辑目录 #${editing}` : '登记新目录'}</h2>
        <form onSubmit={submit}>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: '160px 1fr 1fr',
              gap: 12,
              alignItems: 'end',
            }}
          >
            <div className="field" style={{ marginBottom: 0 }}>
              <label>名称（唯一）</label>
              <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
            </div>
            <div className="field" style={{ marginBottom: 0 }}>
              <label>本地路径</label>
              <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                <input
                  value={form.path}
                  onChange={(e) => setForm({ ...form, path: e.target.value })}
                  placeholder="点「浏览」选择，或直接输入"
                  required
                />
                <button
                  type="button"
                  className="ghost"
                  style={{ flex: '0 0 auto' }}
                  onClick={() => setPicking(true)}
                >
                  浏览…
                </button>
              </div>
            </div>
            <div className="field" style={{ marginBottom: 0 }}>
              <label>忽略目录（逗号分隔）</label>
              <input
                value={form.ignore.join(',')}
                onChange={(e) =>
                  setForm({
                    ...form,
                    ignore: e.target.value.split(',').map((s) => s.trim()).filter(Boolean),
                  })
                }
              />
            </div>
          </div>

          <div style={{ margin: '14px 0' }}>
            <label>归属标记（可多选）</label>
            {ALL_MARKS.map((m) => (
              <button
                type="button"
                key={m}
                className={form.marks.includes(m) ? 'tag-chip active' : 'tag-chip'}
                onClick={() => toggleMark(m)}
              >
                {MARK_LABELS[m] ?? m}
              </button>
            ))}
          </div>

          <div className="row">
            <button type="submit" disabled={busy}>
              {editing ? '保存修改' : '添加目录'}
            </button>
            {editing && (
              <button
                type="button"
                className="ghost"
                onClick={() => {
                  setEditing(null)
                  setForm(empty)
                }}
              >
                取消
              </button>
            )}
            <label className="row" style={{ margin: 0, gap: 6 }}>
              <input
                type="checkbox"
                style={{ width: 'auto' }}
                checked={form.enabled}
                onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
              />
              启用
            </label>
          </div>
        </form>
      </div>

      <div className="card">
        <h2>目录注册表</h2>
        <table>
          <thead>
            <tr>
              <th>名称</th>
              <th>路径</th>
              <th>标记</th>
              <th>照片</th>
              <th>已打标</th>
              <th>文件</th>
              <th>占用</th>
              <th>状态</th>
              <th><span className="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            {dirs.map((d) => (
              <tr key={d.id}>
                <td>{d.name}</td>
                <td className="muted">{d.path}</td>
                <td>
                  {d.marks.map((m) => (
                    <span key={m} className="badge">
                      {MARK_LABELS[m] ?? m}
                    </span>
                  ))}
                </td>
                <td>{d.photo_count}</td>
                <td>{d.tagged_count}</td>
                <td>{d.file_count}</td>
                <td>{(d.total_bytes / 1024 / 1024).toFixed(1)} MB</td>
                <td>
                  <span className={d.enabled ? 'badge ok' : 'badge mute'}>
                    {d.enabled ? '启用' : '停用'}
                  </span>
                </td>
                <td>
                  <div className="row" style={{ gap: 6 }}>
                    <button
                      className="ghost small"
                      onClick={() => {
                        setEditing(d.id)
                        setForm({
                          name: d.name,
                          path: d.path,
                          marks: d.marks,
                          ignore: d.ignore_rules,
                          enabled: d.enabled,
                        })
                      }}
                    >
                      编辑
                    </button>
                    <button className="ghost small" onClick={() => toggleEnabled(d)}>
                      {d.enabled ? '停用' : '启用'}
                    </button>
                    <button className="danger small" onClick={() => remove(d.id)}>
                      删除
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {dirs.length === 0 && (
              <tr>
                <td colSpan={9} className="empty">
                  还没有登记任何目录
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
