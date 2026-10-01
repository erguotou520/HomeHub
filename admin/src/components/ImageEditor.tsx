import { useCallback, useEffect, useRef, useState } from 'react'
import { API_BASE, api, formatBytes, formatTime } from '../api/client'
import type { FileEntry } from '../api/types'
import Icon from './Icon'
import { useToast } from './Toast'

/** Encode each path segment individually so "/" separators survive. */
function enc(path: string): string {
  return path.split('/').filter(Boolean).map(encodeURIComponent).join('/')
}

/** Listing paths include the dir prefix; per-file endpoints want dir-relative. */
function rel(entry: FileEntry, dir: string): string {
  return entry.path.startsWith(`${dir}/`) ? entry.path.slice(dir.length + 1) : entry.path
}

/** Fresh URL with a cache-buster so edits always show the new bytes. */
function rawUrl(dir: string, path: string): string {
  return `${API_BASE}/api/media/${encodeURIComponent(dir)}/${enc(path)}?v=${Date.now()}`
}
function apiUrl(dir: string, path: string, tail: string): string {
  return `${API_BASE}/api/images/${tail}/${encodeURIComponent(dir)}/${enc(path)}`
}

type Op = { op: 'rotate'; angle: number } | { op: 'flip-h' } | { op: 'flip-v' } | { op: 'resize'; width?: number; height?: number; quality?: number }

interface Exif {
  name: string
  dir: string
  path: string
  width: number
  height: number
  size: number
  orientation: number
  has_original: boolean
  taken_at: number | null
  gps_lat: number | null
  gps_lng: number | null
  camera_make: string | null
  camera_model: string | null
}

export default function ImageEditor({
  dir,
  entries,
  index,
  onIndexChange,
  onClose,
  onChanged,
}: {
  dir: string
  /** All image entries of the current view, for prev/next navigation. */
  entries: FileEntry[]
  index: number
  onIndexChange: (i: number) => void
  onClose: () => void
  onChanged: () => void
}) {
  const toast = useToast()
  const entry = entries[index]
  const [imgUrl, setImgUrl] = useState(() => rawUrl(dir, rel(entry, dir)))
  const [busy, setBusy] = useState(false)
  const [showInfo, setShowInfo] = useState(false)
  const [exif, setExif] = useState<Exif | null>(null)
  const [confirmRestore, setConfirmRestore] = useState(false)
  const [resizeOpen, setResizeOpen] = useState(false)
  const [widthText, setWidthText] = useState('')
  const imgRef = useRef<HTMLImageElement>(null)

  // Reset state whenever the active image changes.
  useEffect(() => {
    setImgUrl(rawUrl(dir, rel(entries[index], dir)))
    setExif(null)
    setConfirmRestore(false)
    setShowInfo(false)
    setResizeOpen(false)
    setWidthText('')
  }, [dir, entries, index])

  const refreshCache = useCallback(() => {
    setImgUrl(rawUrl(dir, rel(entry, dir)))
  }, [dir, entry.path])

  async function transform(ops: Op[], after?: string) {
    setBusy(true)
    try {
      const r = await api.post<{ success: boolean; result: { archived: boolean; lossless: boolean; width: number; height: number; size: number } }>(
        apiUrl(dir, rel(entry, dir), 'transform'),
        { ops },
      )
      if (!r.success) throw new Error('编辑失败')
      refreshCache()
      onChanged()
      setExif(null)
      if (after) toast.ok(after)
    } catch (e) {
      toast.err(e instanceof Error ? e.message : '操作失败')
    } finally {
      setBusy(false)
    }
  }

  function rotate(angle: number) {
    return transform([{ op: 'rotate', angle }], angle === 90 ? '已顺时针旋转' : angle === -90 ? '已逆时针旋转' : angle === 180 ? '已旋转 180°' : '已旋转')
  }

  function doRestore() {
    setBusy(true)
    api
      .post<{ success: boolean }>(apiUrl(dir, rel(entry, dir), 'restore'), undefined)
      .then((r) => {
        if (!r.success) throw new Error('还原失败')
        setConfirmRestore(false)
        refreshCache()
        onChanged()
        setExif(null)
        toast.ok('已还原到最近一次编辑前')
      })
      .catch((e) => toast.err(e instanceof Error ? e.message : '还原失败'))
      .finally(() => setBusy(false))
  }

  function doResize() {
    const w = Number.parseInt(widthText, 10)
    if (!Number.isFinite(w) || w <= 0) {
      toast.warn('请输入有效的宽度（像素）')
      return
    }
    setResizeOpen(false)
    void transform([{ op: 'resize', width: w, quality: 85 }], `已缩放到宽 ${w}px`)
  }

  function loadInfo() {
    if (exif) {
      setShowInfo((s) => !s)
      return
    }
    setBusy(true)
    api
      .get<Exif>(apiUrl(dir, rel(entry, dir), 'exif'))
      .then((d) => {
        setExif(d)
        setShowInfo(true)
      })
      .catch((e) => toast.err(e instanceof Error ? e.message : '读取信息失败'))
      .finally(() => setBusy(false))
  }

  // Keyboard shortcuts.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        onClose()
        return
      }
      const t = e.target as HTMLElement
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
      if (e.key === 'ArrowLeft' && index > 0) onIndexChange(index - 1)
      else if (e.key === 'ArrowRight' && index < entries.length - 1) onIndexChange(index + 1)
      else if (e.key === 'r' || e.key === 'R') rotate(90)
      else if (e.key === 'i' || e.key === 'I') loadInfo()
      else if (e.key === 'h' || e.key === 'H') transform([{ op: 'flip-h' }], '已水平翻转')
      else if (e.key === 'v' || e.key === 'V') transform([{ op: 'flip-v' }], '已垂直翻转')
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  if (!entry) return null

  return (
    <div className="viewer" role="dialog" aria-modal="true" aria-label={`编辑图片 ${entry.name}`}>
      <div className="viewer-top">
        <div className="viewer-title truncate">
          <span className="truncate">{entry.name}</span>
          <span className="viewer-counter">
            {index + 1} / {entries.length}
          </span>
        </div>
        <div className="viewer-top-actions">
          {exif?.has_original && (
            <button
              className="icon-btn"
              title="还原原图"
              aria-label="还原原图"
              onClick={() => (confirmRestore ? doRestore() : setConfirmRestore(true))}
              disabled={busy}
            >
              <Icon name="retry" size={15} />
            </button>
          )}
          <button className={`icon-btn${showInfo ? ' on' : ''}`} title="属性 / EXIF (I)" aria-label="属性信息" onClick={loadInfo} disabled={busy}>
            <Icon name="info" size={15} />
          </button>
          <a className="icon-btn" href={rawUrl(dir, rel(entry, dir))} download={entry.name} title="下载" aria-label="下载原图">
            <Icon name="download" size={15} />
          </a>
          <button className="icon-btn" title="关闭 (Esc)" aria-label="关闭" onClick={onClose}>
            <Icon name="x" size={15} />
          </button>
        </div>
      </div>

      <div className="viewer-stage">
        <img
          ref={imgRef}
          key={imgUrl}
          src={imgUrl}
          alt={entry.name}
          draggable={false}
        />
      </div>

      {confirmRestore && (
        <div className="viewer-confirm">
          <span>确认还原到最近一次编辑前的状态？当前编辑将丢弃。</span>
          <button className="danger" onClick={doRestore} disabled={busy}>
            {busy ? '还原中…' : '确认还原'}
          </button>
          <button className="ghost" onClick={() => setConfirmRestore(false)} disabled={busy}>取消</button>
        </div>
      )}

      {resizeOpen && (
        <div className="viewer-confirm">
          <label className="resize-field">
            <span>目标宽度（px，等比缩放）：</span>
            <input
              value={widthText}
              onChange={(e) => setWidthText(e.target.value.replace(/\D/g, ''))}
              onKeyDown={(e) => e.key === 'Enter' && doResize()}
              inputMode="numeric"
              autoFocus
              placeholder="如 1280"
            />
          </label>
          <button onClick={doResize} disabled={busy}>缩放</button>
          <button className="ghost" onClick={() => setResizeOpen(false)} disabled={busy}>取消</button>
        </div>
      )}

      <div className="viewer-bar">
        <button onClick={() => index > 0 && onIndexChange(index - 1)} disabled={index === 0} aria-label="上一张">
          <Icon name="chevronRight" size={13} style={{ transform: 'rotate(180deg)' }} />
        </button>
        <div className="zoom-group">
          <button title="逆时针 (H)" aria-label="水平翻转" onClick={() => transform([{ op: 'flip-h' }], '已水平翻转')} disabled={busy}>
            <Icon name="flipH" size={13} />
          </button>
          <button title="旋转 90° (R)" aria-label="旋转 90 度" onClick={() => rotate(90)} disabled={busy}>
            <Icon name="rotateRight" size={13} />
          </button>
          <button title="逆时针 90°" aria-label="逆时针旋转" onClick={() => rotate(-90)} disabled={busy}>
            <Icon name="rotateLeft" size={13} />
          </button>
          <button title="垂直翻转 (V)" aria-label="垂直翻转" onClick={() => transform([{ op: 'flip-v' }], '已垂直翻转')} disabled={busy}>
            <Icon name="flipV" size={13} />
          </button>
          <button title="按宽度缩放" aria-label="按宽度缩放" onClick={() => setResizeOpen(true)} disabled={busy}>
            <Icon name="resize" size={13} />
          </button>
        </div>
        <button onClick={() => index < entries.length - 1 && onIndexChange(index + 1)} disabled={index === entries.length - 1} aria-label="下一张">
          <Icon name="chevronRight" size={13} />
        </button>
      </div>

      {showInfo && exif && (
        <div className="viewer-info">
          <h4>图片属性</h4>
          <dl>
            <dt>分辨率</dt><dd>{exif.width} × {exif.height}</dd>
            <dt>文件大小</dt><dd>{formatBytes(exif.size)}</dd>
            <dt>拍摄时间</dt><dd>{formatTime(exif.taken_at)}</dd>
            <dt>相机</dt><dd>{[exif.camera_make, exif.camera_model].filter(Boolean).join(' ') || '-'}</dd>
            <dt>GPS</dt><dd>{exif.gps_lat != null ? `${exif.gps_lat.toFixed(5)}, ${exif.gps_lng?.toFixed(5)}` : '-'}</dd>
            <dt>方向</dt><dd>{exif.orientation}</dd>
            <dt>原图</dt><dd>{exif.has_original ? '已归档（可还原）' : '无'}</dd>
          </dl>
        </div>
      )}
    </div>
  )
}
