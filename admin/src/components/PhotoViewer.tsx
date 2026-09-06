import { useCallback, useEffect, useState } from 'react'
import { api, formatBytes, formatTime } from '../api/client'
import type { PhotoItem } from '../api/types'

interface Props {
  items: PhotoItem[]
  index: number
  onIndexChange: (i: number) => void
  onClose: () => void
}

export function PhotoViewer({ items, index, onIndexChange, onClose }: Props) {
  const [info, setInfo] = useState<Record<string, unknown> | null>(null)
  const [busy, setBusy] = useState(false)
  const photo = items[Math.min(index, items.length - 1)]

  const loadInfo = useCallback(async (id: number) => {
    try {
      const res = await api.get<Record<string, unknown>>(`/api/photos/${id}`)
      setInfo(res)
    } catch {
      setInfo(null)
    }
  }, [])

  useEffect(() => {
    void loadInfo(photo.id)
  }, [photo.id, loadInfo])

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
      if (e.key === 'ArrowLeft') onIndexChange(Math.max(0, index - 1))
      if (e.key === 'ArrowRight') onIndexChange(Math.min(items.length - 1, index + 1))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [index, items.length, onClose, onIndexChange])

  async function rotate(angle: number) {
    setBusy(true)
    try {
      await api.post(`/api/photos/${photo.id}/rotate`, { angle })
      // Refresh the tile after the server rewrote the file.
      await loadInfo(photo.id)
      window.location.reload()
    } catch (e) {
      alert(e instanceof Error ? e.message : '旋转失败')
    } finally {
      setBusy(false)
    }
  }

  const num = (v: unknown) => (typeof v === 'number' ? v : undefined)

  return (
    <div className="viewer" onClick={onClose}>
      <button
        className="close"
        onClick={(e) => {
          e.stopPropagation()
          onClose()
        }}
      >
        ×
      </button>
      {index > 0 && (
        <button
          className="nav prev"
          onClick={(e) => {
            e.stopPropagation()
            onIndexChange(index - 1)
          }}
        >
          ‹
        </button>
      )}
      {index < items.length - 1 && (
        <button
          className="nav next"
          onClick={(e) => {
            e.stopPropagation()
            onIndexChange(index + 1)
          }}
        >
          ›
        </button>
      )}

      <img
        src={photo.url}
        alt={photo.name}
        onClick={(e) => e.stopPropagation()}
        style={{ transform: `rotate(0deg)` }}
      />

      <div className="info" onClick={(e) => e.stopPropagation()}>
        <div style={{ fontWeight: 600, marginBottom: 4 }}>{photo.name}</div>
        <div className="muted">{photo.dir_name}/{photo.rel_path}</div>
        <div>拍摄时间：{formatTime(num(info?.taken_at) ?? photo.taken_at)}</div>
        <div>
          尺寸：{num(info?.width) ?? photo.width} × {num(info?.height) ?? photo.height} ·{' '}
          {formatBytes(photo.size)}
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
          <div>标签：{(info!.tags as { tag: string }[]).map((t) => t.tag).join('、')}</div>
        )}
      </div>

      <div className="actions" onClick={(e) => e.stopPropagation()}>
        <button onClick={() => rotate(270)} disabled={busy}>
          ↺ 左转
        </button>
        <button onClick={() => rotate(90)} disabled={busy}>
          ↻ 右转
        </button>
        <button className="ghost" onClick={() => rotate(180)} disabled={busy}>
          180°
        </button>
      </div>
    </div>
  )
}
