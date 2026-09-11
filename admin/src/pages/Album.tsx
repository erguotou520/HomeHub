import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, formatBytes } from '../api/client'
import type { DirStat, GeoPoint, PhotoItem, Stats, TimelineGroup } from '../api/types'
import Icon from '../components/Icon'
import { PhotoViewer } from '../components/PhotoViewer'
import GeoMap from '../components/GeoMap'

type AlbumView = 'timeline' | 'tree' | 'tags' | 'people' | 'geo'
type KindFilter = 'all' | 'photo' | 'video'

export default function Album() {
  // View and media-type filter live in the URL so they survive refresh and sharing.
  const [params, setParams] = useSearchParams()
  const rawView = params.get('view') as AlbumView | null
  const rawKind = params.get('kind') as KindFilter | null
  const view: AlbumView =
    rawView && rawView in VIEW_LABELS ? rawView : 'timeline'
  const kind: KindFilter = rawKind === 'photo' || rawKind === 'video' ? rawKind : 'all'
  const setView = useCallback(
    (v: AlbumView) => {
      const next = new URLSearchParams(params)
      if (v === 'timeline') next.delete('view')
      else next.set('view', v)
      setParams(next, { replace: true })
    },
    [params, setParams],
  )
  const setKind = useCallback(
    (k: KindFilter) => {
      const next = new URLSearchParams(params)
      if (k === 'all') next.delete('kind')
      else next.set('kind', k)
      setParams(next, { replace: true })
    },
    [params, setParams],
  )
  const [groups, setGroups] = useState<TimelineGroup[]>([])
  const [tags, setTags] = useState<{ tag: string; kind: string; photo_count: number; cover_url?: string | null }[]>([])
  const [people, setPeople] = useState<{ id: number; name?: string | null; photo_count: number; cover_url?: string | null }[]>([])
  const [dirs, setDirs] = useState<DirStat[]>([])
  const [items, setItems] = useState<PhotoItem[]>([])
  const [points, setPoints] = useState<GeoPoint[]>([])
  /** Server aggregation grid for the geo view; the map bumps it while zooming. */
  const [geoPrecision, setGeoPrecision] = useState(0.02)
  const [tag, setTag] = useState<string | null>(null)
  const [personId, setPersonId] = useState<number | null>(null)
  const [dirId, setDirId] = useState<number | null>(null)
  const [geoLabel, setGeoLabel] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [viewer, setViewer] = useState<{ items: PhotoItem[]; index: number } | null>(null)
  /** Ids removed during this session — the grid hides them until the next fetch. */
  const [removed, setRemoved] = useState<Set<number>>(new Set())
  const [renamed, setRenamed] = useState<Record<number, string>>({})
  /** Per-photo edit counter, folded into the media URL to defeat stale caches. */
  const [editedRev, setEditedRev] = useState<Record<number, number>>({})

  const handleDeleted = useCallback((id: number) => {
    setRemoved((prev) => new Set(prev).add(id))
    setViewer((v) => {
      if (!v) return v
      const next = v.items.filter((p) => p.id !== id)
      if (next.length === 0) return null
      return { items: next, index: Math.min(v.index, next.length - 1) }
    })
  }, [])

  const handleRenamed = useCallback((id: number, name: string) => {
    setRenamed((prev) => ({ ...prev, [id]: name }))
  }, [])

  /**
   * An in-place edit (rotate / flip / restore) rewrites the pixels but not the
   * path, so the cached thumbnail URL keeps pointing at the old image. Bump a
   * per-photo counter and rewrite the `v` token here: the grid and the viewer
   * then request a URL nothing has cached yet. A fresh list fetch gets the
   * real server-side version instead.
   */
  const handleEdited = useCallback((id: number) => {
    setEditedRev((prev) => ({ ...prev, [id]: (prev[id] ?? 0) + 1 }))
  }, [])

  /** Drop deleted photos, apply local renames and post-edit cache bumps. */
  const decorate = useCallback(
    (list: PhotoItem[]) =>
      list
        .filter((p) => !removed.has(p.id))
        .map((p) => {
          const rev = editedRev[p.id]
          const base = renamed[p.id] ? { ...p, name: renamed[p.id] } : p
          if (!rev) return base
          const bump = (url: string) => `${url}${url.includes('?') ? '&' : '?'}_=${rev}`
          return { ...base, url: bump(base.url), thumb_url: bump(base.thumb_url) }
        }),
    [removed, renamed, editedRev],
  )

  const loadList = useCallback(
    async (ids?: number[]) => {
      setLoading(true)
      setError(null)
      try {
        const res = await api.get<{ items: PhotoItem[] }>('/api/photos/list', {
          tag: tag ?? undefined,
          person_id: personId ?? undefined,
          dir_id: dirId ?? undefined,
          ids: ids?.join(','),
          limit: 300,
        })
        setItems(res.items)
        // Only show the grid; the viewer opens when a tile is clicked.
        setViewer(null)
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败')
      } finally {
        setLoading(false)
      }
    },
    [tag, personId, dirId],
  )

  useEffect(() => {
    if (tag || personId || dirId) void loadList()
  }, [tag, personId, dirId, loadList])

  /** Drill down into one map cluster: the server filters by explicit ids. */
  const showGeo = useCallback(
    (point: GeoPoint) => {
      setGeoLabel(`${point.lat.toFixed(4)}, ${point.lng.toFixed(4)}`)
      void loadList(point.photo_ids)
    },
    [loadList],
  )

  /** Rename a person group in place; reloads the list so the caption updates. */
  async function renamePerson(p: { id: number; name?: string | null }) {
    const name = window.prompt('分组名称', p.name ?? '')
    if (!name?.trim()) return
    try {
      await api.post(`/api/photos/people/${p.id}/rename`, { name: name.trim() })
      const res = await api.get<{ people: typeof people }>('/api/photos/people')
      setPeople(res.people)
    } catch (e) {
      setError(e instanceof Error ? e.message : '重命名失败')
    }
  }

  useEffect(() => {
    setError(null)
    if (view === 'timeline') {
      api
        .get<{ groups: TimelineGroup[] }>('/api/photos/timeline', {
          group: 'day',
          kind: kind === 'all' ? undefined : kind,
        })
        .then((r) => setGroups(r.groups))
        .catch((e: Error) => setError(e.message))
    } else if (view === 'tags') {
      api
        .get<{ tags: typeof tags }>('/api/photos/tags')
        .then((r) => setTags(r.tags))
        .catch((e: Error) => setError(e.message))
    } else if (view === 'people') {
      api
        .get<{ people: typeof people }>('/api/photos/people')
        .then((r) => setPeople(r.people))
        .catch((e: Error) => setError(e.message))
    } else if (view === 'tree') {
      api
        .get<{ dirs: DirStat[] }>('/api/admin/dirs')
        .then((r) => setDirs(r.dirs))
        .catch((e: Error) => setError(e.message))
    }
  }, [view])

  /** Geo view refetches on its own: every precision change (map zoom) re-aggregates. */
  useEffect(() => {
    if (view !== 'geo') return
    api
      .get<{ points: GeoPoint[] }>('/api/photos/geo', { precision: geoPrecision })
      .then((r) => setPoints(r.points))
      .catch((e: Error) => setError(e.message))
  }, [view, geoPrecision])

  useEffect(() => {
    api
      .get<Stats>('/api/admin/stats')
      .then((s) => setDirs(s.dirs))
      .catch(() => undefined)
  }, [])

  async function showTree(dir: DirStat) {
    setDirId(dir.id)
    const res = await api.get<{ groups: TimelineGroup[] }>('/api/photos/tree', { dir_id: dir.id })
    setGroups(res.groups)
    setView('timeline')
  }

  return (
    <div>
      <div className="grid-stats">
        <Stat label="照片" value={dirs.reduce((a, d) => a + d.photo_count, 0)} />
        <Stat label="已打标" value={dirs.reduce((a, d) => a + d.tagged_count, 0)} />
        <Stat label="占用" value={formatBytes(dirs.reduce((a, d) => a + d.total_bytes, 0))} />
        <Stat label="标签数" value={tags.length} />
      </div>

      <div className="toolbar">
        {(['timeline', 'tree', 'tags', 'people', 'geo'] as const).map((v) => (
          <button
            key={v}
            className={view === v ? '' : 'ghost'}
            onClick={() => {
              setView(v)
              setTag(null)
              setPersonId(null)
              setDirId(null)
              setGeoLabel(null)
            }}
          >
            {VIEW_LABELS[v]}
          </button>
        ))}
        {(tag || personId || dirId || geoLabel) && (
          <button
            className="ghost"
            onClick={() => {
              setTag(null)
              setPersonId(null)
              setDirId(null)
              setGeoLabel(null)
              setView('timeline')
            }}
          >
            清除筛选
          </button>
        )}
        {view === 'timeline' && !tag && !personId && !dirId && !geoLabel && (
          <span className="seg" role="group" aria-label="媒体类型">
            {(['all', 'photo', 'video'] as const).map((k) => (
              <button
                key={k}
                className={`seg-btn${kind === k ? ' on' : ''}`}
                onClick={() => setKind(k)}
                aria-pressed={kind === k}
              >
                {KIND_LABELS[k]}
              </button>
            ))}
          </span>
        )}
        {loading && <span className="muted">加载中…</span>}
      </div>

      {error && <div className="error">{error}</div>}

      {view === 'timeline' && !tag && !personId && !geoLabel && (
        <>
          {groups.length === 0 && <div className="empty">暂无照片，请先在「目录管理」登记带 album 标记的目录</div>}
          {groups.map((g) => {
            const list = decorate(g.items)
            if (list.length === 0) return null
            return (
              <div key={g.key}>
                <div className="section-title">
                  {g.label} <span className="muted">{list.length} 项</span>
                </div>
                <PhotoGrid items={list} onOpen={(i) => setViewer({ items: list, index: i })} />
              </div>
            )
          })}
        </>
      )}

      {view === 'tree' && (
        <div className="card">
          <h2>按目录浏览</h2>
          <table>
            <thead>
              <tr>
                <th>目录</th>
                <th>标记</th>
                <th>照片</th>
                <th>文件</th>
                <th>占用</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {dirs.map((d) => (
                <tr key={d.id}>
                  <td>{d.name}</td>
                  <td>
                    {d.marks.map((m) => (
                      <span key={m} className="badge">
                        {m}
                      </span>
                    ))}
                  </td>
                  <td>{d.photo_count}</td>
                  <td>{d.file_count}</td>
                  <td>{formatBytes(d.total_bytes)}</td>
                  <td>
                    <button className="small" onClick={() => showTree(d)}>
                      浏览
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {view === 'tags' && (
        <div className="card">
          <h2>标签</h2>
          {tags.length === 0 && <div className="empty">还没有标签，等待识别任务完成</div>}
          <div>
            {tags.map((t) => (
              <button
                key={`${t.kind}-${t.tag}`}
                className="tag-chip"
                onClick={() => setTag(t.tag)}
              >
                {t.tag}
                <span className="muted">
                  {t.kind === 'scene' ? '场景' : '物体'} · {t.photo_count}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}

      {view === 'people' && (
        <div className="card">
          <h2>人物分组</h2>
          {people.length === 0 && <div className="empty">还没有人脸分组</div>}
          <div className="photo-grid">
            {people.map((p) => (
              <div key={p.id} className="person-tile">
                <button
                  type="button"
                  className="tile person-thumb"
                  onClick={() => setPersonId(p.id)}
                  aria-label={`人物 ${p.name ?? p.id}，${p.photo_count} 张`}
                >
                  {p.cover_url ? (
                    <img src={p.cover_url} alt="" loading="lazy" />
                  ) : (
                    <span className="empty">无封面</span>
                  )}
                  <span className="tile-caption">
                    {p.name ?? `未命名 ${p.id}`} · {p.photo_count}
                  </span>
                </button>
                <button
                  className="person-rename"
                  onClick={() => void renamePerson(p)}
                  title="重命名分组"
                  aria-label={`重命名 ${p.name ?? `未命名 ${p.id}`}`}
                >
                  <Icon name="edit" size={12} />
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {view === 'geo' && !geoLabel && (
        <div className="card">
          <h2>地点</h2>
          {points.length === 0 && !loading && (
            <div className="empty">没有带 GPS 信息的照片</div>
          )}
          {points.length > 0 && (
            <GeoMap
              points={points}
              precision={geoPrecision}
              onSelect={showGeo}
              onPrecisionChange={setGeoPrecision}
            />
          )}
        </div>
      )}

      {(tag || personId || dirId || geoLabel) && (
        <div>
          <div className="section-title">
            筛选结果
            <span className="muted">
              {tag
                ? `标签 ${tag}`
                : personId
                  ? `人物 ${personId}`
                  : geoLabel
                    ? `地点 ${geoLabel}`
                    : `目录 ${dirId}`}{' '}
              · {items.length} 项
            </span>
          </div>
          {items.length === 0 && !loading && <div className="empty">没有匹配的照片</div>}
          <PhotoGrid
            items={decorate(items)}
            onOpen={(i) => setViewer({ items: decorate(items), index: i })}
          />
        </div>
      )}

      {viewer && viewer.items.length > 0 && (
        <PhotoViewer
          items={viewer.items}
          index={viewer.index}
          onIndexChange={(i) => setViewer({ items: viewer.items, index: i })}
          onClose={() => setViewer(null)}
          onDeleted={handleDeleted}
          onRenamed={handleRenamed}
          onEdited={handleEdited}
        />
      )}
    </div>
  )
}

const VIEW_LABELS: Record<AlbumView, string> = {
  timeline: '时间轴',
  tree: '目录树',
  tags: '分类',
  people: '人物',
  geo: '地点',
}

const KIND_LABELS: Record<KindFilter, string> = {
  all: '全部',
  photo: '照片',
  video: '视频',
}

function formatDuration(ms?: number | null): string {
  if (!ms || ms <= 0) return ''
  const total = Math.round(ms / 1000)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

function Stat({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="stat">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
    </div>
  )
}

export function PhotoGrid({ items, onOpen }: { items: PhotoItem[]; onOpen: (i: number) => void }) {
  return (
    <div className="photo-grid">
      {items.map((p, i) => (
        <button
          key={p.id}
          type="button"
          className="tile"
          onClick={() => onOpen(i)}
          title={p.rel_path}
          aria-label={`打开 ${p.name}`}
        >
          <img src={p.thumb_url} alt={p.name} loading="lazy" width="256" height="256" />
          {p.media_kind === 'video' && (
            <span className="tile-video" aria-hidden="true">
              <Icon name="play" size={10} strokeWidth={2.2} /> {formatDuration(p.duration_ms)}
            </span>
          )}
        </button>
      ))}
    </div>
  )
}
