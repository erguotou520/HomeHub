import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { API_BASE, api, formatBytes, formatTime } from '../api/client'
import type { DirStat, FileEntry } from '../api/types'

type SortKey = 'name' | 'mtime' | 'size'

/** Encode each path segment individually so "/" separators survive. */
function enc(path: string): string {
  return path.split('/').filter(Boolean).map(encodeURIComponent).join('/')
}

export function mediaUrl(dir: string, path: string, thumb = false): string {
  return `${API_BASE}/api/media/${encodeURIComponent(dir)}/${enc(path)}${thumb ? '?size=thumb' : ''}`
}

const TEXT_EXTS = new Set([
  'txt', 'md', 'log', 'json', 'yaml', 'yml', 'toml', 'ini', 'conf', 'csv',
  'ts', 'tsx', 'js', 'jsx', 'rs', 'py', 'go', 'sh', 'html', 'css', 'xml', 'srt', 'ass',
])

function extOf(name: string): string {
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : ''
}

type Preview =
  | { kind: 'image'; entry: FileEntry }
  | { kind: 'video'; entry: FileEntry }
  | { kind: 'audio'; entry: FileEntry }
  | { kind: 'text'; entry: FileEntry; content: string }
  | { kind: 'other'; entry: FileEntry }

export default function Files() {
  const [dirs, setDirs] = useState<DirStat[]>([])
  const [dir, setDir] = useState<string>('')
  const [path, setPath] = useState('')
  const [entries, setEntries] = useState<FileEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sort, setSort] = useState<SortKey>('name')
  const [desc, setDesc] = useState(false)
  const [grid, setGrid] = useState(false)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [renameTarget, setRenameTarget] = useState<FileEntry | null>(null)
  const [moveTarget, setMoveTarget] = useState<{ entry: FileEntry; op: 'copy' | 'move' } | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<FileEntry | null>(null)
  const [showMkdir, setShowMkdir] = useState(false)
  const [busy, setBusy] = useState(false)
  const [toast, setToast] = useState<string | null>(null)
  const uploadRef = useRef<HTMLInputElement>(null)

  const showToast = useCallback((msg: string) => {
    setToast(msg)
    window.setTimeout(() => setToast(null), 2600)
  }, [])

  useEffect(() => {
    api
      .get<{ dirs: DirStat[] }>('/api/admin/dirs')
      .then((r) => {
        setDirs(r.dirs)
        setDir((cur) => cur || r.dirs[0]?.name || '')
      })
      .catch((e: Error) => setError(e.message))
  }, [])

  const load = useCallback(async () => {
    if (!dir) return
    setLoading(true)
    setError(null)
    try {
      const res = await api.get<{ entries: FileEntry[] }>(
        `/api/files/${encodeURIComponent(dir)}/${enc(path)}`,
        { sort, desc },
      )
      setEntries(res.entries)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [dir, path, sort, desc])

  useEffect(() => {
    void load()
  }, [load])

  const segments = useMemo(() => path.split('/').filter(Boolean), [path])

  function open(entry: FileEntry) {
    if (entry.is_dir) {
      setPath(entry.path.slice(dir.length + 1))
      return
    }
    const ext = extOf(entry.name)
    if (entry.media_kind === 'image') setPreview({ kind: 'image', entry })
    else if (entry.media_kind === 'video') setPreview({ kind: 'video', entry })
    else if (entry.media_kind === 'music') setPreview({ kind: 'audio', entry })
    else if (TEXT_EXTS.has(ext)) {
      api
        .get<{ content: string }>(`/api/documents/${encodeURIComponent(dir)}/${enc(entry.path)}`)
        .then((r) => setPreview({ kind: 'text', entry, content: r.content ?? '' }))
        .catch((e: Error) => showToast(`无法读取：${e.message}`))
    } else setPreview({ kind: 'other', entry })
  }

  async function doRename(entry: FileEntry, name: string) {
    setBusy(true)
    try {
      await api.patch(`/api/files/${encodeURIComponent(dir)}/${enc(entry.path)}`, { name })
      setRenameTarget(null)
      showToast('已重命名')
      await load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : '重命名失败')
    } finally {
      setBusy(false)
    }
  }

  async function doCopyMove(entry: FileEntry, op: 'copy' | 'move', toDir: string, toPath: string) {
    setBusy(true)
    try {
      await api.patch(`/api/files/${encodeURIComponent(dir)}/${enc(entry.path)}`, {
        op,
        to_dir: toDir,
        to_path: toPath,
      })
      setMoveTarget(null)
      showToast(op === 'copy' ? '已复制' : '已移动')
      await load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : '操作失败')
    } finally {
      setBusy(false)
    }
  }

  async function doDelete(entry: FileEntry) {
    setBusy(true)
    try {
      await api.del(`/api/files/${encodeURIComponent(dir)}/${enc(entry.path)}`)
      setDeleteTarget(null)
      showToast('已移入回收站')
      await load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : '删除失败')
    } finally {
      setBusy(false)
    }
  }

  async function doMkdir(name: string) {
    setBusy(true)
    try {
      const base = path ? `${dir}/${path}` : dir
      await api.post(`/api/mkdir/${encodeURIComponent(dir)}/${enc(path ? `${path}/${name}` : name)}`)
      setShowMkdir(false)
      showToast(`已创建 ${base}/${name}`)
      await load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : '创建失败')
    } finally {
      setBusy(false)
    }
  }

  async function doUpload(files: FileList) {
    if (!files.length) return
    setBusy(true)
    try {
      const form = new FormData()
      for (const f of Array.from(files)) form.append('file', f, f.name)
      const headers: Record<string, string> = {}
      const res = await fetch(`${API_BASE}/api/files/${encodeURIComponent(dir)}/${enc(path)}`, {
        method: 'POST',
        headers,
        body: form,
      })
      if (!res.ok) throw new Error((await res.json().catch(() => null))?.error ?? '上传失败')
      showToast(`已上传 ${files.length} 个文件`)
      await load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : '上传失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="files-layout">
      <aside className="files-side card" aria-label="注册目录">
        <h2>目录</h2>
        {dirs.map((d) => (
          <button
            key={d.id}
            className={`side-item${d.name === dir ? ' active' : ''}`}
            onClick={() => {
              setDir(d.name)
              setPath('')
            }}
          >
            <span className="side-name">{d.name}</span>
            <span className="side-marks">
              {d.marks.map((m) => (
                <span key={m} className="badge">{MARK_ZH[m] ?? m}</span>
              ))}
            </span>
          </button>
        ))}
        {dirs.length === 0 && <div className="empty">没有注册目录</div>}
      </aside>

      <div className="files-main">
        <div className="toolbar">
          <nav className="crumbs" aria-label="路径">
            <button className="crumb" onClick={() => setPath('')}>{dir}</button>
            {segments.map((s, i) => (
              <button
                key={i}
                className="crumb"
                onClick={() => setPath(segments.slice(0, i + 1).join('/'))}
              >
                / {s}
              </button>
            ))}
          </nav>
          <span className="flex-spacer" />
          <button className="ghost small" onClick={() => setGrid((g) => !g)} aria-label="切换视图">
            {grid ? '列表' : '网格'}
          </button>
          <select
            value={sort}
            onChange={(e) => setSort(e.target.value as SortKey)}
            aria-label="排序字段"
          >
            <option value="name">按名称</option>
            <option value="mtime">按时间</option>
            <option value="size">按大小</option>
          </select>
          <button className="ghost small" onClick={() => setDesc((d) => !d)} aria-label="排序方向">
            {desc ? '降序' : '升序'}
          </button>
          <button className="small" onClick={() => setShowMkdir(true)}>新建目录</button>
          <button className="small" onClick={() => uploadRef.current?.click()} disabled={busy}>
            {busy ? '处理中…' : '上传'}
          </button>
          <input
            ref={uploadRef}
            type="file"
            multiple
            hidden
            onChange={(e) => {
              if (e.target.files?.length) void doUpload(e.target.files)
              e.target.value = ''
            }}
          />
        </div>

        {error && <div className="error" role="alert">{error}</div>}
        {loading && entries.length === 0 && <div className="empty">加载中…</div>}
        {!loading && !error && entries.length === 0 && (
          <div className="empty">这个目录是空的</div>
        )}

        {grid ? (
          <div className="files-grid">
            {entries.map((en) => (
              <button
                key={en.path}
                className="file-tile"
                onClick={() => open(en)}
                title={en.name}
              >
                <span className="file-thumb">
                  {en.media_kind === 'image' ? (
                    <img src={mediaUrl(dir, en.path, true)} alt="" loading="lazy" />
                  ) : (
                    <KindIcon entry={en} />
                  )}
                </span>
                <span className="file-name">{en.name}</span>
                {!en.is_dir && (
                  <span className="file-meta">{formatBytes(en.size ?? 0)}</span>
                )}
              </button>
            ))}
          </div>
        ) : (
          <div className="card table-card">
            <table>
              <thead>
                <tr>
                  <th>名称</th>
                  <th className="num">大小</th>
                  <th>修改时间</th>
                  <th className="ops" />
                </tr>
              </thead>
              <tbody>
                {entries.map((en) => (
                  <tr key={en.path}>
                    <td>
                      <button className="file-link" onClick={() => open(en)}>
                        <KindIcon entry={en} small />
                        <span className="truncate">{en.name}</span>
                      </button>
                    </td>
                    <td className="num">{en.is_dir ? '-' : formatBytes(en.size ?? 0)}</td>
                    <td>{formatTime(en.modified)}</td>
                    <td className="ops">
                      {!en.is_dir && (
                        <>
                          <a
                            className="op"
                            href={mediaUrl(dir, en.path)}
                            download={en.name}
                            aria-label={`下载 ${en.name}`}
                          >
                            下载
                          </a>
                          <button className="op" onClick={() => setRenameTarget(en)}>重命名</button>
                          <button className="op" onClick={() => setMoveTarget({ entry: en, op: 'copy' })}>复制</button>
                          <button className="op" onClick={() => setMoveTarget({ entry: en, op: 'move' })}>移动</button>
                        </>
                      )}
                      <button
                        className="op danger"
                        onClick={() => setDeleteTarget(en)}
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {preview && (
        <PreviewModal
          preview={preview}
          dir={dir}
          onClose={() => setPreview(null)}
          onSaved={() => showToast('已保存')}
          onError={(m) => showToast(m)}
        />
      )}
      {renameTarget && (
        <PromptDialog
          title="重命名"
          label="新名称"
          initial={renameTarget.name}
          confirmText="重命名"
          busy={busy}
          onConfirm={(v) => doRename(renameTarget, v)}
          onDismiss={() => setRenameTarget(null)}
        />
      )}
      {moveTarget && (
        <CopyMoveDialog
          op={moveTarget.op}
          name={moveTarget.entry.name}
          dirs={dirs}
          currentDir={dir}
          currentPath={path}
          busy={busy}
          onConfirm={(toDir, toPath) => doCopyMove(moveTarget.entry, moveTarget.op, toDir, toPath)}
          onDismiss={() => setMoveTarget(null)}
        />
      )}
      {deleteTarget && (
        <ConfirmDialog
          title="删除"
          message={`「${deleteTarget.name}」会移入回收站，30 天后自动清理。确认删除？`}
          confirmText="删除"
          busy={busy}
          onConfirm={() => doDelete(deleteTarget)}
          onDismiss={() => setDeleteTarget(null)}
        />
      )}
      {showMkdir && (
        <PromptDialog
          title="新建目录"
          label="目录名"
          initial=""
          confirmText="创建"
          busy={busy}
          onConfirm={(v) => doMkdir(v)}
          onDismiss={() => setShowMkdir(false)}
        />
      )}
      {toast && (
        <div className="toast" role="status" aria-live="polite">{toast}</div>
      )}
    </div>
  )
}

const MARK_ZH: Record<string, string> = {
  album: '相册',
  video: '视频',
  music: '音乐',
  document: '文档',
  none: '无归属',
}

function KindIcon({ entry, small }: { entry: FileEntry; small?: boolean }) {
  const cls = small ? 'kind kind-sm' : 'kind'
  if (entry.is_dir) return <span className={cls} aria-hidden="true">📁</span>
  if (entry.media_kind === 'image') return <span className={cls} aria-hidden="true">🖼️</span>
  if (entry.media_kind === 'video') return <span className={cls} aria-hidden="true">🎬</span>
  if (entry.media_kind === 'music') return <span className={cls} aria-hidden="true">🎵</span>
  return <span className={cls} aria-hidden="true">📄</span>
}

function PreviewModal({
  preview,
  dir,
  onClose,
  onSaved,
  onError,
}: {
  preview: Preview
  dir: string
  onClose: () => void
  onSaved: () => void
  onError: (msg: string) => void
}) {
  const { entry } = preview
  const [text, setText] = useState(preview.kind === 'text' ? preview.content : '')
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape' && !dirty) onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [dirty, onClose])

  async function save() {
    setSaving(true)
    try {
      await api.put(`/api/documents/${encodeURIComponent(dir)}/${enc(entry.path)}`, { content: text })
      setDirty(false)
      onSaved()
    } catch (e) {
      onError(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="modal-overlay" onClick={dirty ? undefined : onClose}>
      <div
        className="modal preview-modal"
        role="dialog"
        aria-modal="true"
        aria-label={entry.name}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-head">
          <span className="truncate">{entry.name}</span>
          <span className="muted">{formatBytes(entry.size ?? 0)}</span>
          <button className="ghost small" onClick={onClose} aria-label="关闭预览">关闭</button>
        </div>
        <div className="preview-body">
          {preview.kind === 'image' && (
            <img src={mediaUrl(dir, entry.path)} alt={entry.name} />
          )}
          {preview.kind === 'video' && (
            <video src={mediaUrl(dir, entry.path)} controls preload="metadata" />
          )}
          {preview.kind === 'audio' && (
            <audio src={mediaUrl(dir, entry.path)} controls style={{ width: '100%' }} />
          )}
          {preview.kind === 'text' && (
            <textarea
              value={text}
              onChange={(e) => {
                setText(e.target.value)
                setDirty(true)
              }}
              spellCheck={false}
              aria-label={`编辑 ${entry.name}`}
            />
          )}
          {preview.kind === 'other' && (
            <div className="empty">
              暂不支持在线预览，
              <a href={mediaUrl(dir, entry.path)} download={entry.name}>点击下载</a>
            </div>
          )}
        </div>
        {preview.kind === 'text' && (
          <div className="modal-foot">
            {dirty && <span className="muted">有未保存修改</span>}
            <span className="flex-spacer" />
            <button onClick={save} disabled={saving || !dirty}>
              {saving ? '保存中…' : '保存'}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

function PromptDialog({
  title,
  label,
  initial,
  confirmText,
  busy,
  onConfirm,
  onDismiss,
}: {
  title: string
  label: string
  initial: string
  confirmText: string
  busy: boolean
  onConfirm: (value: string) => void
  onDismiss: () => void
}) {
  const [value, setValue] = useState(initial)
  return (
    <div className="modal-overlay" onClick={onDismiss}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
      >
        <h3>{title}</h3>
        <label className="field">
          <span>{label}</span>
          <input
            value={value}
            onChange={(e) => setValue(e.target.value)}
            autoFocus
            onFocus={(e) => e.target.select()}
          />
        </label>
        <div className="modal-foot">
          <button className="ghost" onClick={onDismiss}>取消</button>
          <button onClick={() => onConfirm(value.trim())} disabled={busy || !value.trim()}>
            {busy ? '处理中…' : confirmText}
          </button>
        </div>
      </div>
    </div>
  )
}

function ConfirmDialog({
  title,
  message,
  confirmText,
  busy,
  onConfirm,
  onDismiss,
}: {
  title: string
  message: string
  confirmText: string
  busy: boolean
  onConfirm: () => void
  onDismiss: () => void
}) {
  return (
    <div className="modal-overlay" onClick={onDismiss}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
      >
        <h3>{title}</h3>
        <p>{message}</p>
        <div className="modal-foot">
          <button className="ghost" onClick={onDismiss}>取消</button>
          <button className="danger" onClick={onConfirm} disabled={busy}>
            {busy ? '处理中…' : confirmText}
          </button>
        </div>
      </div>
    </div>
  )
}

function CopyMoveDialog({
  op,
  name,
  dirs,
  currentDir,
  currentPath,
  busy,
  onConfirm,
  onDismiss,
}: {
  op: 'copy' | 'move'
  name: string
  dirs: DirStat[]
  currentDir: string
  currentPath: string
  busy: boolean
  onConfirm: (toDir: string, toPath: string) => void
  onDismiss: () => void
}) {
  const [toDir, setToDir] = useState(currentDir)
  const [toPath, setToPath] = useState(currentPath)
  return (
    <div className="modal-overlay" onClick={onDismiss}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={op === 'copy' ? '复制到' : '移动到'}
        onClick={(e) => e.stopPropagation()}
      >
        <h3>{op === 'copy' ? '复制' : '移动'}「{name}」到</h3>
        <div className="row gap">
          <label className="field grow">
            <span>目标目录</span>
            <select value={toDir} onChange={(e) => setToDir(e.target.value)}>
              {dirs.map((d) => (
                <option key={d.id} value={d.name}>{d.name}</option>
              ))}
            </select>
          </label>
          <label className="field grow">
            <span>子目录（留空为根）</span>
            <input
              value={toPath}
              onChange={(e) => setToPath(e.target.value)}
              placeholder="如 2024/旅行…"
            />
          </label>
        </div>
        <div className="modal-foot">
          <button className="ghost" onClick={onDismiss}>取消</button>
          <button onClick={() => onConfirm(toDir, toPath.trim())} disabled={busy}>
            {busy ? '处理中…' : op === 'copy' ? '复制' : '移动'}
          </button>
        </div>
      </div>
    </div>
  )
}
