import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { API_BASE, api, formatBytes, formatTime } from '../api/client'
import type { DirStat, FileEntry } from '../api/types'
import Icon from '../components/Icon'
import ImageEditor from '../components/ImageEditor'

type SortKey = 'name' | 'mtime' | 'size'

type UpStatus = 'pending' | 'uploading' | 'done' | 'skipped' | 'error'

interface UpItem {
  id: number
  file: File
  status: UpStatus
  progress: number
  error?: string
}

/** Single-file XHR upload with progress; resolves to the duplicate outcome. */
function uploadOne(
  item: UpItem,
  url: string,
  onProgress: (p: number) => void,
): Promise<'done' | 'skipped'> {
  return new Promise((resolve, reject) => {
    const form = new FormData()
    form.append('file', item.file, item.file.name)
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    xhr.responseType = 'json'
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(e.loaded / e.total)
    }
    xhr.onload = () => {
      const body = xhr.response as { uploaded?: { skipped?: boolean }[]; error?: string } | null
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(body?.uploaded?.[0]?.skipped ? 'skipped' : 'done')
      } else {
        reject(new Error(body?.error ?? `HTTP ${xhr.status}`))
      }
    }
    xhr.onerror = () => reject(new Error('网络错误'))
    xhr.send(form)
  })
}

/** Encode each path segment individually so "/" separators survive. */
function enc(path: string): string {
  return path.split('/').filter(Boolean).map(encodeURIComponent).join('/')
}

/**
 * The listing API returns `path` relative to the registered-root (it includes
 * the dir name), but per-file endpoints take paths relative to the dir.
 */
function rel(entry: FileEntry, dir: string): string {
  return entry.path.startsWith(`${dir}/`) ? entry.path.slice(dir.length + 1) : entry.path
}

export function mediaUrl(dir: string, path: string, thumb = false, rev = 0): string {
  const q: string[] = []
  if (thumb) q.push('size=thumb')
  if (rev) q.push(`rev=${rev}`)
  const s = q.length ? `?${q.join('&')}` : ''
  return `${API_BASE}/api/media/${encodeURIComponent(dir)}/${enc(path)}${s}`
}

const TEXT_EXTS = new Set([
  'txt', 'md', 'log', 'json', 'yaml', 'yml', 'toml', 'ini', 'conf', 'csv',
  'ts', 'tsx', 'js', 'jsx', 'rs', 'py', 'go', 'sh', 'html', 'css', 'xml', 'srt', 'ass',
])

/** Browser-native iframe preview (served inline by the media endpoint). */
const IFRAME_EXTS = new Set(['pdf'])

function extOf(name: string): string {
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : ''
}

type Preview =
  | { kind: 'video'; entry: FileEntry }
  | { kind: 'audio'; entry: FileEntry }
  | { kind: 'pdf'; entry: FileEntry }
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
  const [editIndex, setEditIndex] = useState<number | null>(null)
  const [rev, setRev] = useState(0)
  const [queue, setQueue] = useState<UpItem[]>([])
  const [dup, setDup] = useState<'skip' | 'keep'>('skip')
  const [dragging, setDragging] = useState(false)
  const [selMode, setSelMode] = useState(false)
  const [sel, setSel] = useState<Set<string>>(new Set())
  const [batchOp, setBatchOp] = useState<'copy' | 'move' | null>(null)
  const [confirmBatchDel, setConfirmBatchDel] = useState(false)
  const [query, setQuery] = useState('')
  const [showNewText, setShowNewText] = useState(false)
  const [attrTarget, setAttrTarget] = useState<FileEntry | null>(null)
  const [activeIdx, setActiveIdx] = useState(-1)
  const uploadRef = useRef<HTMLInputElement>(null)
  const queueRef = useRef<UpItem[]>([])
  const pumpLock = useRef(false)
  const dupRef = useRef(dup)
  const upIdRef = useRef(0)
  dupRef.current = dup

  /** Images of the current listing, in display order — the editor navigates these. */
  const imageEntries = useMemo(
    () => entries.filter((e) => !e.is_dir && e.media_kind === 'image'),
    [entries],
  )

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
      // Trailing slash on a single-segment path makes the server route miss
      // and fall back to the SPA shell; omit it when the subpath is empty.
      const tail = path ? `/${enc(path)}` : ''
      const res = await api.get<{ entries: FileEntry[] }>(
        `/api/files/${encodeURIComponent(dir)}${tail}`,
        { sort, desc },
      )
      setEntries(res.entries ?? [])
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [dir, path, sort, desc])

  const onEdited = useCallback(() => {
    setRev((r) => r + 1)
    void load()
  }, [load])

  function patchUp(id: number, patch: Partial<UpItem>) {
    queueRef.current = queueRef.current.map((i) => (i.id === id ? { ...i, ...patch } : i))
    setQueue(queueRef.current)
  }

  const enqueue = useCallback((files: File[]) => {
    if (!files.length) return
    const items: UpItem[] = files.map((file) => ({
      id: ++upIdRef.current,
      file,
      status: 'pending',
      progress: 0,
    }))
    queueRef.current = [...queueRef.current, ...items]
    setQueue(queueRef.current)
    void pumpRef.current()
  }, [])

  const pumpRef = useRef<() => Promise<void>>(async () => {})

  async function pump() {
    if (pumpLock.current) return
    pumpLock.current = true
    try {
      for (;;) {
        const next = queueRef.current.find((i) => i.status === 'pending')
        if (!next) break
        patchUp(next.id, { status: 'uploading', progress: 0 })
        const tail = path ? `/${enc(path)}` : ''
        const url = `${API_BASE}/api/files/${encodeURIComponent(dir)}${tail}?on-duplicate=${dupRef.current}`
        try {
          const result = await uploadOne(next, url, (p) => patchUp(next.id, { progress: p }))
          patchUp(next.id, { status: result, progress: 1 })
        } catch (e) {
          patchUp(next.id, { status: 'error', error: e instanceof Error ? e.message : '上传失败' })
        }
      }
    } finally {
      pumpLock.current = false
    }
    void load()
  }
  pumpRef.current = pump

  function retry(id: number) {
    patchUp(id, { status: 'pending', progress: 0, error: undefined })
    void pumpRef.current()
  }

  function toggleSel(p: string) {
    setSel((s) => {
      const n = new Set(s)
      if (n.has(p)) n.delete(p)
      else n.add(p)
      return n
    })
  }

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? entries.filter((e) => e.name.toLowerCase().includes(q)) : entries
  }, [entries, query])

  const fileEntries = useMemo(() => filtered.filter((e) => !e.is_dir), [filtered])

  function toggleAllSel() {
    setSel((s) => (s.size === fileEntries.length ? new Set() : new Set(fileEntries.map((e) => e.path))))
  }

  function exitSel() {
    setSelMode(false)
    setSel(new Set())
  }

  function batchDownload() {
    const items = entries.filter((e) => sel.has(e.path) && !e.is_dir)
    items.forEach((en, idx) => {
      window.setTimeout(() => {
        const a = document.createElement('a')
        a.href = mediaUrl(dir, rel(en, dir))
        a.download = en.name
        document.body.appendChild(a)
        a.click()
        a.remove()
      }, idx * 400)
    })
    showToast(`开始下载 ${items.length} 个文件`)
  }

  async function runBatch(op: 'copy' | 'move', toDir: string, toPath: string) {
    const targets = entries.filter((e) => sel.has(e.path) && !e.is_dir)
    setBusy(true)
    let ok = 0
    let fail = 0
    try {
      for (const en of targets) {
        try {
          const toPathFull = toPath ? `${toPath.replace(/\/+$/, '')}/${en.name}` : en.name
          await api.patch(`/api/files/${encodeURIComponent(dir)}/${enc(rel(en, dir))}`, {
            op,
            to_dir: toDir,
            to_path: toPathFull,
          })
          ok++
        } catch {
          fail++
        }
      }
      setBatchOp(null)
      showToast(`已${op === 'copy' ? '复制' : '移动'} ${ok} 项${fail ? `，${fail} 项失败` : ''}`)
      await load()
      exitSel()
    } finally {
      setBusy(false)
    }
  }

  async function runBatchDelete() {
    const targets = entries.filter((e) => sel.has(e.path) && !e.is_dir)
    setBusy(true)
    let ok = 0
    let fail = 0
    try {
      for (const en of targets) {
        try {
          await api.del(`/api/files/${encodeURIComponent(dir)}/${enc(rel(en, dir))}`)
          ok++
        } catch {
          fail++
        }
      }
      setConfirmBatchDel(false)
      showToast(`已移入回收站 ${ok} 项${fail ? `，${fail} 项失败` : ''}`)
      await load()
      exitSel()
    } finally {
      setBusy(false)
    }
  }

  // Esc leaves selection mode when no dialog is open.
  useEffect(() => {
    if (!selMode) return
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape') return
      const t = e.target as HTMLElement
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
      if (batchOp || confirmBatchDel) return
      exitSel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [selMode, batchOp, confirmBatchDel])

  // Arrow-key navigation + Enter to open (list view only).
  useEffect(() => {
    setActiveIdx(-1)
  }, [path, entries])

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Enter') return
      const t = e.target as HTMLElement
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return
      if (grid || selMode || preview || editIndex !== null) return
      if (document.querySelector('.modal') || document.querySelector('.viewer')) return
      if (filtered.length === 0) return
      e.preventDefault()
      if (e.key === 'ArrowDown') setActiveIdx((i) => Math.min(i + 1, filtered.length - 1))
      else if (e.key === 'ArrowUp') setActiveIdx((i) => Math.max(i - 1, 0))
      else if (e.key === 'Enter' && activeIdx >= 0 && activeIdx < filtered.length) open(filtered[activeIdx])
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  useEffect(() => {
    if (activeIdx < 0) return
    document.querySelector('.row-active')?.scrollIntoView({ block: 'nearest' })
  }, [activeIdx])

  function removeUp(id: number) {
    if (queueRef.current.find((i) => i.id === id)?.status === 'uploading') return
    queueRef.current = queueRef.current.filter((i) => i.id !== id)
    setQueue(queueRef.current)
  }

  function clearFinished() {
    queueRef.current = queueRef.current.filter((i) => i.status === 'pending' || i.status === 'uploading')
    setQueue(queueRef.current)
  }

  // Drag & drop onto the file area.
  const dropHandlers = useMemo(
    () => ({
      onDragOver: (e: React.DragEvent) => {
        if (Array.from(e.dataTransfer.types).includes('Files')) {
          e.preventDefault()
          setDragging(true)
        }
      },
      onDragLeave: (e: React.DragEvent) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node)) setDragging(false)
      },
      onDrop: (e: React.DragEvent) => {
        e.preventDefault()
        setDragging(false)
        const files = Array.from(e.dataTransfer.files ?? [])
        if (files.length) enqueue(files)
      },
    }),
    [enqueue],
  )

  // Paste files from the clipboard.
  useEffect(() => {
    function onPaste(e: ClipboardEvent) {
      const t = e.target as HTMLElement
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
      const files = Array.from(e.clipboardData?.files ?? [])
      if (files.length) {
        e.preventDefault()
        enqueue(files)
      }
    }
    window.addEventListener('paste', onPaste)
    return () => window.removeEventListener('paste', onPaste)
  }, [enqueue])

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
    if (entry.media_kind === 'image') {
      const idx = imageEntries.findIndex((e) => e.path === entry.path)
      setEditIndex(idx >= 0 ? idx : 0)
    }
    else if (entry.media_kind === 'video') setPreview({ kind: 'video', entry })
    else if (entry.media_kind === 'music') setPreview({ kind: 'audio', entry })
    else if (IFRAME_EXTS.has(ext)) setPreview({ kind: 'pdf', entry })
    else if (TEXT_EXTS.has(ext)) {
      api
        .get<{ content: string }>(`/api/documents/${encodeURIComponent(dir)}/${enc(rel(entry, dir))}`)
        .then((r) => setPreview({ kind: 'text', entry, content: r.content ?? '' }))
        .catch((e: Error) => showToast(`无法读取：${e.message}`))
    } else setPreview({ kind: 'other', entry })
  }

  async function doRename(entry: FileEntry, name: string) {
    setBusy(true)
    try {
      await api.patch(`/api/files/${encodeURIComponent(dir)}/${enc(rel(entry, dir))}`, { name })
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
      // The API's to_path is the full destination path (file name included);
      // the dialog collects only the target subdirectory.
      const toPathFull = toPath ? `${toPath.replace(/\/+$/, '')}/${entry.name}` : entry.name
      await api.patch(`/api/files/${encodeURIComponent(dir)}/${enc(rel(entry, dir))}`, {
        op,
        to_dir: toDir,
        to_path: toPathFull,
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
      await api.del(`/api/files/${encodeURIComponent(dir)}/${enc(rel(entry, dir))}`)
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
      const tail = path ? `/${enc(`${path}/${name}`)}` : `/${enc(name)}`
      await api.post(`/api/mkdir/${encodeURIComponent(dir)}${tail}`)
      setShowMkdir(false)
      showToast(`已创建 ${base}/${name}`)
      await load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : '创建失败')
    } finally {
      setBusy(false)
    }
  }

  async function doNewText(rawName: string) {
    const name = rawName.includes('.') ? rawName : `${rawName}.txt`
    setBusy(true)
    try {
      const tail = path ? `/${enc(path)}` : ''
      await api.put(`/api/documents/${encodeURIComponent(dir)}${tail}/${enc(name)}`, { content: '' })
      setShowNewText(false)
      showToast(`已创建 ${name}`)
      await load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : '创建失败')
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

      <div className="files-main" {...dropHandlers}>
        {dragging && (
          <div className="drop-overlay" aria-hidden="true">
            <div className="drop-hint">松开以上传到当前目录</div>
          </div>
        )}
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
          <button
            className={`ghost small${selMode ? ' on' : ''}`}
            onClick={() => (selMode ? exitSel() : setSelMode(true))}
            aria-pressed={selMode}
          >
            {selMode ? '退出选择' : '选择'}
          </button>
          <input
            className="search-input"
            type="search"
            placeholder="搜索当前目录…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label="搜索文件"
          />
          <button className="ghost small" onClick={() => setShowNewText(true)}>新建文本</button>
          <button className="small" onClick={() => setShowMkdir(true)}>新建目录</button>
          <select
            value={dup}
            onChange={(e) => setDup(e.target.value as 'skip' | 'keep')}
            aria-label="重复文件策略"
            title="上传重名/同内容文件时的处理方式"
          >
            <option value="skip">重复跳过</option>
            <option value="keep">重复保留</option>
          </select>
          <button className="small" onClick={() => uploadRef.current?.click()} disabled={busy}>
            {busy ? '处理中…' : '上传'}
          </button>
          <input
            ref={uploadRef}
            type="file"
            multiple
            hidden
            onChange={(e) => {
              if (e.target.files?.length) enqueue(Array.from(e.target.files))
              e.target.value = ''
            }}
          />
        </div>

        {selMode && (
          <div className="sel-bar card" role="toolbar" aria-label="批量操作">
            <label className="chk-all">
              <input
                type="checkbox"
                checked={fileEntries.length > 0 && sel.size === fileEntries.length}
                onChange={toggleAllSel}
                aria-label="全选文件"
              />
              全选
            </label>
            <span className="muted">已选 {sel.size} 项</span>
            <span className="flex-spacer" />
            <button className="small" disabled={!sel.size || busy} onClick={batchDownload}>下载</button>
            <button className="small" disabled={!sel.size || busy} onClick={() => setBatchOp('copy')}>复制到</button>
            <button className="small" disabled={!sel.size || busy} onClick={() => setBatchOp('move')}>移动到</button>
            <button className="danger small" disabled={!sel.size || busy} onClick={() => setConfirmBatchDel(true)}>删除</button>
          </div>
        )}

        {error && <div className="error" role="alert">{error}</div>}
        {loading && entries.length === 0 && <div className="empty">加载中…</div>}
        {!loading && !error && entries.length === 0 && (
          <div className="empty">这个目录是空的</div>
        )}
        {!loading && !error && entries.length > 0 && filtered.length === 0 && (
          <div className="empty">没有匹配「{query}」的文件</div>
        )}

        {grid ? (
          <div className="files-grid">
            {filtered.map((en) => (
              <button
                key={en.path}
                className={`file-tile${en.is_dir ? ' dir' : ''}${selMode && sel.has(en.path) ? ' selected' : ''}`}
                onClick={() => (selMode && !en.is_dir ? toggleSel(en.path) : open(en))}
                title={en.name}
              >
                <span className="file-thumb">
                  {en.media_kind === 'image' ? (
                    <img src={mediaUrl(dir, rel(en, dir), true, rev)} alt="" loading="lazy" />
                  ) : (
                    <KindIcon entry={en} />
                  )}
                </span>
                {selMode && !en.is_dir && (
                  <span className="tile-check" aria-hidden="true">
                    {sel.has(en.path) && <Icon name="check" size={13} strokeWidth={2.6} />}
                  </span>
                )}
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
                  {selMode && (
                    <th className="chk">
                      <input
                        type="checkbox"
                        checked={fileEntries.length > 0 && sel.size === fileEntries.length}
                        onChange={toggleAllSel}
                        aria-label="全选文件"
                      />
                    </th>
                  )}
                  <th>名称</th>
                  <th className="num">大小</th>
                  <th>修改时间</th>
                  <th className="ops" />
                </tr>
              </thead>
              <tbody>
                {filtered.map((en, idx) => (
                  <tr
                    key={en.path}
                    className={`${en.is_dir ? 'row-dir' : ''} ${selMode && sel.has(en.path) ? 'row-sel' : ''} ${idx === activeIdx ? 'row-active' : ''}`.trim() || undefined}
                  >
                    {selMode && (
                      <td className="chk">
                        {!en.is_dir && (
                          <input
                            type="checkbox"
                            checked={sel.has(en.path)}
                            onChange={() => toggleSel(en.path)}
                            aria-label={`选择 ${en.name}`}
                          />
                        )}
                      </td>
                    )}
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
                            href={mediaUrl(dir, rel(en, dir))}
                            download={en.name}
                            aria-label={`下载 ${en.name}`}
                          >
                            下载
                          </a>
                          <button className="op" onClick={() => setAttrTarget(en)}>属性</button>
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

      {editIndex !== null && imageEntries[editIndex] && (
        <ImageEditor
          dir={dir}
          entries={imageEntries}
          index={editIndex}
          onIndexChange={setEditIndex}
          onClose={() => setEditIndex(null)}
          onChanged={onEdited}
          notify={showToast}
        />
      )}
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
      {queue.length > 0 && (
        <UploadQueuePanel
          items={queue}
          onRetry={retry}
          onRemove={removeUp}
          onClear={clearFinished}
        />
      )}
      {batchOp && (
        <CopyMoveDialog
          op={batchOp}
          name={`${sel.size} 个文件`}
          dirs={dirs}
          currentDir={dir}
          currentPath={path}
          busy={busy}
          onConfirm={(toDir, toPath) => runBatch(batchOp, toDir, toPath)}
          onDismiss={() => setBatchOp(null)}
        />
      )}
      {confirmBatchDel && (
        <ConfirmDialog
          title="批量删除"
          message={`${sel.size} 个文件会移入回收站，30 天后自动清理。确认删除？`}
          confirmText="删除"
          busy={busy}
          onConfirm={runBatchDelete}
          onDismiss={() => setConfirmBatchDel(false)}
        />
      )}
      {showNewText && (
        <PromptDialog
          title="新建文本文件"
          label="文件名（无扩展名自动补 .txt）"
          initial="未命名.txt"
          confirmText="创建"
          busy={busy}
          onConfirm={(v) => doNewText(v)}
          onDismiss={() => setShowNewText(false)}
        />
      )}
      {attrTarget && (
        <div className="modal-overlay" onClick={() => setAttrTarget(null)}>
          <div
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-label={`属性 ${attrTarget.name}`}
            onClick={(e) => e.stopPropagation()}
          >
            <h3>属性</h3>
            <dl className="attr-dl">
              <dt>名称</dt>
              <dd className="truncate">{attrTarget.name}</dd>
              <dt>类型</dt>
              <dd>{attrTarget.is_dir ? '文件夹' : attrTarget.mime_type || `${extOf(attrTarget.name).toUpperCase()} 文件`}</dd>
              <dt>大小</dt>
              <dd>{attrTarget.is_dir ? '-' : formatBytes(attrTarget.size ?? 0)}</dd>
              <dt>修改时间</dt>
              <dd>{formatTime(attrTarget.modified)}</dd>
              <dt>完整路径</dt>
              <dd className="mono truncate">{attrTarget.path}</dd>
            </dl>
            <div className="modal-foot">
              <span className="flex-spacer" />
              <button className="ghost" onClick={() => setAttrTarget(null)}>关闭</button>
            </div>
          </div>
        </div>
      )}
      {toast && (
        <div className="toast" role="status" aria-live="polite">{toast}</div>
      )}
    </div>
  )
}

function UploadQueuePanel({
  items,
  onRetry,
  onRemove,
  onClear,
}: {
  items: UpItem[]
  onRetry: (id: number) => void
  onRemove: (id: number) => void
  onClear: () => void
}) {
  const finished = items.filter((i) => i.status === 'done' || i.status === 'skipped').length
  const errors = items.filter((i) => i.status === 'error').length
  return (
    <aside className="upload-panel card" aria-label="上传队列">
      <div className="up-head">
        <span className="up-title">
          上传队列
          {errors > 0 && <span className="badge danger-badge">{errors} 失败</span>}
        </span>
        <span className="muted">{finished}/{items.length}</span>
        <button className="ghost small" onClick={onClear} disabled={finished === 0}>
          清除已完成
        </button>
      </div>
      <ul className="up-list">
        {items.map((i) => (
          <li key={i.id} className={`up-item st-${i.status}`}>
            <span className="up-icon" aria-hidden="true">
              {i.status === 'done' && <Icon name="check" size={12} strokeWidth={2.4} />}
              {i.status === 'skipped' && <Icon name="skip" size={12} strokeWidth={2.4} />}
              {i.status === 'error' && <Icon name="x" size={12} strokeWidth={2.4} />}
              {i.status === 'uploading' && <Icon name="upload" size={12} strokeWidth={2.4} />}
              {i.status === 'pending' && <Icon name="clock" size={12} strokeWidth={2.4} />}
            </span>
            <span className="up-body">
              <span className="up-name truncate">{i.file.name}</span>
              <span className="up-meta">
                {i.status === 'uploading' && `上传中 ${Math.round(i.progress * 100)}%`}
                {i.status === 'pending' && '等待中…'}
                {i.status === 'done' && formatBytes(i.file.size)}
                {i.status === 'skipped' && '重复，已跳过'}
                {i.status === 'error' && (i.error ?? '失败')}
              </span>
              {(i.status === 'uploading' || i.status === 'pending') && (
                <span className="up-bar">
                  <span className="up-bar-fill" style={{ width: `${Math.max(i.progress * 100, 4)}%` }} />
                </span>
              )}
            </span>
            {i.status === 'error' && (
              <button className="op" onClick={() => onRetry(i.id)}>重试</button>
            )}
            {i.status !== 'uploading' && (
              <button className="op" aria-label={`移除 ${i.file.name}`} onClick={() => onRemove(i.id)}>
                <Icon name="x" size={13} />
              </button>
            )}
          </li>
        ))}
      </ul>
    </aside>
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
  const size = small ? 15 : 28
  const cls = `${small ? 'kind kind-sm' : 'kind'}${entry.is_dir ? ' kind-dir' : ''}`
  if (entry.is_dir) return <Icon name="folder" size={size} className={cls} />
  if (entry.media_kind === 'image') return <Icon name="image" size={size} className={cls} />
  if (entry.media_kind === 'video') return <Icon name="video" size={size} className={cls} />
  if (entry.media_kind === 'music') return <Icon name="music" size={size} className={cls} />
  return <Icon name="file" size={size} className={cls} />
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
      await api.put(`/api/documents/${encodeURIComponent(dir)}/${enc(rel(entry, dir))}`, { content: text })
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
          {preview.kind === 'video' && (
            <video src={mediaUrl(dir, rel(entry, dir))} controls preload="metadata" />
          )}
          {preview.kind === 'audio' && (
            <audio src={mediaUrl(dir, rel(entry, dir))} controls style={{ width: '100%' }} />
          )}
          {preview.kind === 'pdf' && (
            <iframe
              src={mediaUrl(dir, rel(entry, dir))}
              title={entry.name}
              className="pdf-frame"
            />
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
              <a href={mediaUrl(dir, rel(entry, dir))} download={entry.name}>点击下载</a>
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
            <span>目标子目录（留空为根，保留原文件名）</span>
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
