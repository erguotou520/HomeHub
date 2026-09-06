import { useCallback, useEffect, useState } from 'react'
import { api, formatBytes } from '../api/client'
import type { DirStat, GeoPoint, PhotoItem, Stats, TimelineGroup } from '../api/types'
import { PhotoViewer } from '../components/PhotoViewer'
import GeoMap from '../components/GeoMap'

type AlbumView = 'timeline' | 'tree' | 'tags' | 'people' | 'geo'

export default function Album() {
  const [view, setView] = useState<AlbumView>('timeline')
  const [groups, setGroups] = useState<TimelineGroup[]>([])
  const [tags, setTags] = useState<{ tag: string; kind: string; photo_count: number; cover_url?: string | null }[]>([])
  const [people, setPeople] = useState<{ id: number; name?: string | null; photo_count: number; cover_url?: string | null }[]>([])
  const [dirs, setDirs] = useState<DirStat[]>([])
  const [items, setItems] = useState<PhotoItem[]>([])
  const [points, setPoints] = useState<GeoPoint[]>([])
  const [tag, setTag] = useState<string | null>(null)
  const [personId, setPersonId] = useState<number | null>(null)
  const [dirId, setDirId] = useState<number | null>(null)
  const [geoLabel, setGeoLabel] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [viewer, setViewer] = useState<{ items: PhotoItem[]; index: number } | null>(null)

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

  useEffect(() => {
    setError(null)
    if (view === 'timeline') {
      api
        .get<{ groups: TimelineGroup[] }>('/api/photos/timeline', { group: 'month' })
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
    } else if (view === 'geo') {
      api
        .get<{ points: GeoPoint[] }>('/api/photos/geo', { precision: 0.02 })
        .then((r) => setPoints(r.points))
        .catch((e: Error) => setError(e.message))
    }
  }, [view])

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
        {loading && <span className="muted">加载中…</span>}
      </div>

      {error && <div className="error">{error}</div>}

      {view === 'timeline' && !tag && !personId && !geoLabel && (
        <>
          {groups.length === 0 && <div className="empty">暂无照片，请先在「目录管理」登记带 album 标记的目录</div>}
          {groups.map((g) => (
            <div key={g.key}>
              <div className="section-title">
                {g.label} <span className="muted">{g.count} 张</span>
              </div>
              <PhotoGrid items={g.items} onOpen={(i) => setViewer({ items: g.items, index: i })} />
            </div>
          ))}
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
              <div key={p.id} className="tile" onClick={() => setPersonId(p.id)}>
                {p.cover_url ? (
                  <img src={p.cover_url} alt={p.name ?? `人物 ${p.id}`} loading="lazy" />
                ) : (
                  <div className="empty">无封面</div>
                )}
                <div
                  style={{
                    position: 'absolute',
                    bottom: 0,
                    left: 0,
                    right: 0,
                    background: 'rgba(0,0,0,.6)',
                    padding: '4px 8px',
                    fontSize: 12,
                  }}
                >
                  {p.name ?? `未命名 ${p.id}`} · {p.photo_count}
                </div>
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
          {points.length > 0 && <GeoMap points={points} onSelect={showGeo} />}
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
              · {items.length} 张
            </span>
          </div>
          {items.length === 0 && !loading && <div className="empty">没有匹配的照片</div>}
          <PhotoGrid items={items} onOpen={(i) => setViewer({ items, index: i })} />
        </div>
      )}

      {viewer && viewer.items.length > 0 && (
        <PhotoViewer
          items={viewer.items}
          index={viewer.index}
          onIndexChange={(i) => setViewer({ items: viewer.items, index: i })}
          onClose={() => setViewer(null)}
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
        <div key={p.id} className="tile" onClick={() => onOpen(i)} title={p.rel_path}>
          <img src={p.thumb_url} alt={p.name} loading="lazy" />
        </div>
      ))}
    </div>
  )
}
