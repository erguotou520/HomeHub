import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { API_BASE, api, formatBytes, formatTime } from '../api/client'
import type { PhotoItem } from '../api/types'

interface Props {
  items: PhotoItem[]
  index: number
  onIndexChange: (i: number) => void
  onClose: () => void
  /** Called after a photo is moved to the recycle bin so the grid can drop it. */
  onDeleted?: (id: number) => void
  /** Called after a successful rename so the grid can refresh. */
  onRenamed?: (id: number, name: string) => void
}

const MAX_ZOOM = 8
const MIN_ZOOM = 0.5

function formatDuration(ms?: number | null): string {
  if (!ms || ms <= 0) return ''
  const total = Math.round(ms / 1000)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

function absolute(base: string): string {
  return API_BASE + (base.startsWith('/') ? base : `/${base}`)
}

export function PhotoViewer({ items, index, onIndexChange, onClose, onDeleted, onRenamed }: Props) {
  const safeIndex = Math.min(Math.max(index, 0), Math.max(items.length - 1, 0))
  const photo = items[safeIndex]

  const [info, setInfo] = useState<Record<string, unknown> | null>(null)
  const [busy, setBusy] = useState(false)
  const [bust, setBust] = useState(0)
  const [zoom, setZoom] = useState(1)
  const [offset, setOffset] = useState({ x: 0, y: 0 })
  const [showInfo, setShowInfo] = useState(true)
  const [showFilm, setShowFilm] = useState(true)
  const [slideshow, setSlideshow] = useState(false)
  const [confirm, setConfirm] = useState<'trash' | null>(null)
  const [renaming, setRenaming] = useState(false)
  const [nameDraft, setNameDraft] = useState('')
  const [toast, setToast] = useState<string | null>(null)

  const stageRef = useRef<HTMLDivElement>(null)
  const dragRef = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null)
  const filmRef = useRef<HTMLDivElement>(null)

  const isVideo = photo?.media_kind === 'video'
  const photoId = photo?.id

  const notify = useCallback((msg: string) => {
    setToast(msg)
    window.setTimeout(() => setToast(null), 2200)
  }, [])

  const loadInfo = useCallback(async (id: number) => {
    try {
      const res = await api.get<Record<string, unknown>>(`/api/photos/${id}`)
      setInfo(res)
    } catch {
      setInfo(null)
    }
  }, [])

  // Reset transient view state whenever the active photo changes.
  useEffect(() => {
    setInfo(null)
    setBust(0)
    setZoom(1)
    setOffset({ x: 0, y: 0 })
    setConfirm(null)
    setRenaming(false)
    if (photoId !== undefined) void loadInfo(photoId)
  }, [photoId, loadInfo])

  // Keep the active thumbnail in view.
  useLayoutEffect(() => {
    if (!showFilm) return
    const el = filmRef.current?.querySelector<HTMLElement>('[data-active="true"]')
    el?.scrollIntoView({ block: 'nearest', inline: 'center' })
  }, [safeIndex, showFilm])

  const go = useCallback(
    (i: number) => {
      if (i < 0 || i >= items.length) return
      onIndexChange(i)
    },
    [items.length, onIndexChange],
  )

  const step = useCallback((d: number) => go(safeIndex + d), [go, safeIndex])

  // ── keyboard ────────────────────────────────────────────────────────────
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const target = e.target as HTMLElement | null
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return
      switch (e.key) {
        case 'Escape':
          if (confirm || renaming) {
            setConfirm(null)
            setRenaming(false)
          } else {
            onClose()
          }
          break
        case 'ArrowLeft':
          step(-1)
          break
        case 'ArrowRight':
          step(1)
          break
        case '=':
        case '+':
          setZoom((z) => Math.min(MAX_ZOOM, +(z * 1.25).toFixed(2)))
          break
        case '-':
        case '_':
          setZoom((z) => Math.max(MIN_ZOOM, +(z / 1.25).toFixed(2)))
          break
        case '0':
          setZoom(1)
          setOffset({ x: 0, y: 0 })
          break
        case 'f':
        case 'F':
          void toggleFullscreen()
          break
        case 'i':
        case 'I':
          setShowInfo((v) => !v)
          break
        default:
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [safeIndex, step, onClose, confirm, renaming])

  // ── wheel zoom (non-passive so we can prevent page scroll) ───────────────
  useEffect(() => {
    const el = stageRef.current
    if (!el || isVideo) return
    function onWheel(e: WheelEvent) {
      e.preventDefault()
      setZoom((z) => {
        const next = e.deltaY < 0 ? z * 1.12 : z / 1.12
        return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, +next.toFixed(2)))
      })
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [isVideo])

  // ── slideshow ───────────────────────────────────────────────────────────
  useEffect(() => {
    if (!slideshow || items.length < 2) return
    const timer = window.setInterval(() => {
      onIndexChange((safeIndex + 1) % items.length)
    }, 3500)
    return () => window.clearInterval(timer)
  }, [slideshow, safeIndex, items.length, onIndexChange])

  async function toggleFullscreen() {
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await document.documentElement.requestFullscreen()
    } catch {
      notify('当前浏览器不支持全屏')
    }
  }

  // ── pan ─────────────────────────────────────────────────────────────────
  function onPointerDown(e: React.PointerEvent) {
    if (isVideo || zoom <= 1) return
    dragRef.current = { x: e.clientX, y: e.clientY, ox: offset.x, oy: offset.y }
    ;(e.target as HTMLElement).setPointerCapture?.(e.pointerId)
  }
  function onPointerMove(e: React.PointerEvent) {
    const d = dragRef.current
    if (!d) return
    setOffset({ x: d.ox + (e.clientX - d.x), y: d.oy + (e.clientY - d.y) })
  }
  function onPointerUp() {
    dragRef.current = null
  }

  // ── actions ─────────────────────────────────────────────────────────────
  async function rotate(angle: number) {
    if (!photo) return
    setBusy(true)
    try {
      await api.post(`/api/photos/${photo.id}/rotate`, { angle })
      await loadInfo(photo.id)
      setBust(Date.now())
      notify('已旋转')
    } catch (e) {
      notify(e instanceof Error ? e.message : '旋转失败')
    } finally {
      setBusy(false)
    }
  }

  async function submitRename() {
    if (!photo) return
    const name = nameDraft.trim()
    if (!name || name === photo.name) {
      setRenaming(false)
      return
    }
    setBusy(true)
    try {
      await api.patch(`/api/files/${encodeURIComponent(photo.dir_name)}/${photo.rel_path}`, { name })
      onRenamed?.(photo.id, name)
      setRenaming(false)
      notify('已重命名')
    } catch (e) {
      notify(e instanceof Error ? e.message : '重命名失败')
    } finally {
      setBusy(false)
    }
  }

  async function trash() {
    if (!photo) return
    setBusy(true)
    try {
      await api.del(`/api/files/${encodeURIComponent(photo.dir_name)}/${photo.rel_path}`)
      onDeleted?.(photo.id)
      setConfirm(null)
      if (items.length <= 1 || onDeleted) {
        // Either there is nothing left to show, or the parent will rebuild the list.
        if (items.length <= 1) onClose()
        notify('已移入回收站')
        return
      }
      go(Math.min(safeIndex, items.length - 2))
      notify('已移入回收站')
    } catch (e) {
      notify(e instanceof Error ? e.message : '删除失败')
    } finally {
      setBusy(false)
    }
  }

  if (!photo) return null

  const num = (v: unknown) => (typeof v === 'number' ? v : undefined)
  const suffix = bust ? `?_=${bust}` : ''
  const src = absolute(photo.url) + suffix
  const thumbSrc = absolute(photo.thumb_url) + suffix
  const downloadUrl = isVideo
    ? `${API_BASE}/api/photos/${photo.id}/raw`
    : `${API_BASE}/api/files/${encodeURIComponent(photo.dir_name)}/${photo.rel_path}?download=1`

  return (
    <div
      className="viewer"
      role="dialog"
      aria-modal="true"
      aria-label={`查看 ${photo.name}`}
      onClick={onClose}
    >
      {/* ── top bar ── */}
      <div className="viewer-top" onClick={(e) => e.stopPropagation()}>
        <div className="viewer-title">
          <span className="truncate">{photo.name}</span>
          <span className="viewer-counter">
            {safeIndex + 1} / {items.length}
          </span>
        </div>
        <div className="viewer-top-actions">
          {items.length > 1 && (
            <button
              className={`icon-btn${slideshow ? ' on' : ''}`}
              onClick={() => setSlideshow((v) => !v)}
              aria-label={slideshow ? '停止播放' : '自动播放'}
              title={slideshow ? '停止播放' : '自动播放'}
            >
              {slideshow ? '⏸' : '▶'}
            </button>
          )}
          <button
            className={`icon-btn${showInfo ? ' on' : ''}`}
            onClick={() => setShowInfo((v) => !v)}
            aria-label="切换信息面板"
            title="信息 (I)"
          >
            ⓘ
          </button>
          <button
            className={`icon-btn${showFilm ? ' on' : ''}`}
            onClick={() => setShowFilm((v) => !v)}
            aria-label="切换缩略图条"
            title="缩略图条"
          >
            ▤
          </button>
          <button className="icon-btn" onClick={() => void toggleFullscreen()} aria-label="全屏" title="全屏 (F)">
            ⛶
          </button>
          <button className="icon-btn" onClick={onClose} aria-label="关闭查看器" title="关闭 (Esc)">
            ×
          </button>
        </div>
      </div>

      {/* ── nav arrows ── */}
      {safeIndex > 0 && (
        <button
          className="nav prev"
          onClick={(e) => {
            e.stopPropagation()
            step(-1)
          }}
          aria-label="上一张"
        >
          ‹
        </button>
      )}
      {safeIndex < items.length - 1 && (
        <button
          className="nav next"
          onClick={(e) => {
            e.stopPropagation()
            step(1)
          }}
          aria-label="下一张"
        >
          ›
        </button>
      )}

      {/* ── stage ── */}
      <div
        className={`viewer-stage${isVideo ? ' video' : ''}${zoom > 1 ? ' grabbing' : ''}`}
        ref={stageRef}
        onClick={(e) => e.stopPropagation()}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerLeave={onPointerUp}
      >
        {isVideo ? (
          // oxlint-disable-next-line jsx-a11y/media-has-caption -- home videos have no subtitle tracks
          <video
            key={photo.id}
            src={`${API_BASE}/api/photos/${photo.id}/raw`}
            controls
            autoPlay
            preload="metadata"
            poster={thumbSrc}
          />
        ) : (
          <img
            key={`${photo.id}-${bust}`}
            src={src}
            alt={photo.name}
            draggable={false}
            onDoubleClick={() => {
              if (zoom > 1) {
                setZoom(1)
                setOffset({ x: 0, y: 0 })
              } else {
                setZoom(2.5)
              }
            }}
            style={{
              transform: `translate(${offset.x}px, ${offset.y}px) scale(${zoom})`,
              cursor: zoom > 1 ? 'grab' : 'zoom-in',
            }}
          />
        )}
      </div>

      {/* ── info panel ── */}
      {showInfo && (
        <div className="info" onClick={(e) => e.stopPropagation()}>
          <div className="info-title">{photo.name}</div>
          <div className="muted">{photo.dir_name}/{photo.rel_path}</div>
          <div>拍摄时间：{formatTime(num(info?.taken_at) ?? photo.taken_at)}</div>
          <div>
            尺寸：{num(info?.width) ?? photo.width} × {num(info?.height) ?? photo.height} ·{' '}
            {formatBytes(photo.size)}
            {isVideo && (
              <>
                {' '}· {formatDuration(num(info?.duration_ms) ?? photo.duration_ms)}
                {(info?.video_codec ?? photo.video_codec) != null &&
                  ` · ${String(info?.video_codec ?? photo.video_codec).toUpperCase()}`}
              </>
            )}
          </div>
          {(info?.camera_make || photo.camera_make) && (
            <div>
              相机：{[info?.camera_make ?? photo.camera_make, info?.camera_model ?? photo.camera_model]
                .filter(Boolean)
                .join(' ')}
            </div>
          )}
          {(info?.gps_lat ?? photo.gps_lat) != null && (
            <div>
              位置：{String(info?.gps_lat ?? photo.gps_lat)}, {String(info?.gps_lng ?? photo.gps_lng)}
            </div>
          )}
          {Array.isArray(info?.tags) && (info!.tags as { tag: string }[]).length > 0 && (
            <div className="info-tags">
              {(info!.tags as { tag: string }[]).map((t) => (
                <span key={t.tag} className="badge">
                  {t.tag}
                </span>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ── action bar ── */}
      <div className="viewer-bar" onClick={(e) => e.stopPropagation()}>
        {!isVideo && (
          <div className="zoom-group">
            <button
              onClick={() => setZoom((z) => Math.max(MIN_ZOOM, +(z / 1.25).toFixed(2)))}
              aria-label="缩小"
              title="缩小 (-)"
            >
              −
            </button>
            <button className="zoom-value" onClick={() => { setZoom(1); setOffset({ x: 0, y: 0 }) }} title="重置 (0)">
              {Math.round(zoom * 100)}%
            </button>
            <button
              onClick={() => setZoom((z) => Math.min(MAX_ZOOM, +(z * 1.25).toFixed(2)))}
              aria-label="放大"
              title="放大 (+)"
            >
              +
            </button>
          </div>
        )}

        {!isVideo && (
          <>
            <button onClick={() => rotate(270)} disabled={busy} title="向左旋转">↺ 左转</button>
            <button onClick={() => rotate(90)} disabled={busy} title="向右旋转">↻ 右转</button>
          </>
        )}
        <button className="ghost" onClick={() => { setNameDraft(photo.name); setRenaming(true) }} title="重命名">
          重命名
        </button>
        <a className="btn-link" href={downloadUrl} download={photo.name} title="下载原文件">
          下载
        </a>
        <button className="danger" onClick={() => setConfirm('trash')} title="移入回收站">
          删除
        </button>
      </div>

      {/* ── filmstrip ── */}
      {showFilm && items.length > 1 && (
        <div className="filmstrip" ref={filmRef} onClick={(e) => e.stopPropagation()}>
          {items.map((p, i) => (
            <button
              key={p.id}
              data-active={i === safeIndex}
              className={`film-item${i === safeIndex ? ' on' : ''}`}
              onClick={() => go(i)}
              aria-label={`第 ${i + 1} 项：${p.name}`}
              aria-current={i === safeIndex}
            >
              <img src={absolute(p.thumb_url)} alt={p.name} loading="lazy" />
              {p.media_kind === 'video' && <span className="film-badge">▶</span>}
            </button>
          ))}
        </div>
      )}

      {/* ── rename dialog ── */}
      {renaming && (
        <div className="modal-overlay" onClick={() => setRenaming(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
            <h3>重命名</h3>
            <p className="muted">仅修改文件名，原文件会在下次扫描时重新索引。</p>
            <input
              autoFocus
              className="input"
              value={nameDraft}
              onChange={(e) => setNameDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void submitRename()
              }}
              aria-label="新文件名"
            />
            <div className="modal-foot">
              <button className="ghost" onClick={() => setRenaming(false)}>
                取消
              </button>
              <button onClick={() => void submitRename()} disabled={busy || !nameDraft.trim()}>
                保存
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── destructive confirm ── */}
      {confirm === 'trash' && (
        <div className="modal-overlay" onClick={() => setConfirm(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
            <h3>移入回收站？</h3>
            <p>
              「{photo.name}」将被移动到回收站，可在「回收站」页面恢复。
            </p>
            <div className="modal-foot">
              <button className="ghost" onClick={() => setConfirm(null)}>
                取消
              </button>
              <button className="danger" onClick={() => void trash()} disabled={busy}>
                移到回收站
              </button>
            </div>
          </div>
        </div>
      )}

      {toast && (
        <div className="toast" role="status" aria-live="polite">
          {toast}
        </div>
      )}
    </div>
  )
}
