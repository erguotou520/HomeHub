import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { PhotoItem, SearchHit } from '../api/types'

type Mode = 'keyword' | 'semantic'

/** ms → "1:23" style duration for the video badge. */
function formatDuration(ms: number): string {
  const s = Math.round(ms / 1000)
  const m = Math.floor(s / 60)
  return `${m}:${String(s % 60).padStart(2, '0')}`
}

/**
 * Render an FTS5 snippet safely.
 *
 * The server highlights matches with literal `<b>` / `</b>` markers inside
 * text that comes straight from indexed file names and tags. That text must
 * never be handed to `dangerouslySetInnerHTML` — a filename such as
 * `<img src=x onerror=…>.jpg` would then run in the admin origin. Here only
 * the two markers are interpreted; every other character is rendered as a text
 * node, so the worst case is a stray bold word.
 */
function renderSnippet(snippet: string): React.ReactNode[] {
  const out: React.ReactNode[] = []
  let buf = ''
  let bold = false
  let key = 0
  const flush = () => {
    if (!buf) return
    out.push(bold ? <b key={key++}>{buf}</b> : <span key={key++}>{buf}</span>)
    buf = ''
  }
  for (const part of snippet.split(/(<b>|<\/b>)/)) {
    if (part === '<b>') {
      flush()
      bold = true
    } else if (part === '</b>') {
      flush()
      bold = false
    } else {
      buf += part
    }
  }
  flush()
  return out
}

interface SemanticResult {
  items: PhotoItem[]
  total: number
  scores: Record<string, string>
}

export default function Search() {
  const [q, setQ] = useState('')
  const [type, setType] = useState('')
  const [mode, setMode] = useState<Mode>('keyword')
  const [hits, setHits] = useState<SearchHit[] | null>(null)
  const [semantic, setSemantic] = useState<SemanticResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function runKeyword() {
    const res = await api.get<{ hits: SearchHit[] }>('/api/search', { q, type, limit: 200 })
    setHits(res.hits)
    setSemantic(null)
  }

  async function runSemantic() {
    const res = await api.get<SemanticResult>('/api/photos/semantic', { q, limit: 60 })
    setSemantic(res)
    setHits(null)
  }

  async function run(e?: React.FormEvent) {
    e?.preventDefault()
    if (!q.trim()) return
    setBusy(true)
    setError(null)
    try {
      if (mode === 'semantic') await runSemantic()
      else await runKeyword()
    } catch (err) {
      setError(err instanceof Error ? err.message : '搜索失败')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    if (!q.trim()) {
      setHits(null)
      setSemantic(null)
      return
    }
    const t = setTimeout(() => void run(), 350)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, type, mode])

  const count = mode === 'semantic' ? (semantic?.total ?? 0) : (hits?.length ?? 0)

  return (
    <div>
      <form className="toolbar" onSubmit={run}>
        <div className="seg">
          <button
            type="button"
            className={`seg-btn${mode === 'keyword' ? ' on' : ''}`}
            onClick={() => setMode('keyword')}
          >
            关键字
          </button>
          <button
            type="button"
            className={`seg-btn${mode === 'semantic' ? ' on' : ''}`}
            onClick={() => setMode('semantic')}
          >
            语义
          </button>
        </div>
        <input
          style={{ minWidth: 280 }}
          placeholder={
            mode === 'semantic'
              ? '用自然语言描述照片，如：海边玩水 / 两个白发老人 / 卡通插画'
              : '搜索文件名 / 标签（FTS5 + 子串回退）'
          }
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        {mode === 'keyword' && (
          <select value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">全部</option>
            <option value="photo">照片</option>
            <option value="file">文件</option>
          </select>
        )}
        <button type="submit" disabled={busy}>
          搜索
        </button>
        {(hits || semantic) && <span className="muted">命中 {count} 条</span>}
      </form>

      {error && <div className="error">{error}</div>}

      {mode === 'semantic' && semantic && semantic.items.length === 0 && (
        <div className="empty">没有语义匹配的照片（需要照片先完成语义索引）</div>
      )}

      {mode === 'semantic' && semantic && semantic.items.length > 0 && (
        <div className="card">
          <div className="photo-grid">
            {semantic.items.map((p) => (
              <div key={p.id} className="person-tile">
                {p.thumb_url ? (
                  <img src={p.thumb_url} alt={p.name} loading="lazy" />
                ) : (
                  <div className="empty" style={{ aspectRatio: '1' }}>
                    无缩略图
                  </div>
                )}
                {p.media_kind === 'video' && (
                  <span className="video-badge">
                    {p.duration_ms ? formatDuration(p.duration_ms) : '视频'}
                  </span>
                )}
                <span className="tile-caption">
                  {p.name}
                  {semantic.scores[p.id] && (
                    <span className="muted"> · {semantic.scores[p.id]}</span>
                  )}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {mode === 'keyword' && hits && hits.length === 0 && <div className="empty">没有匹配结果</div>}

      {mode === 'keyword' && hits && hits.length > 0 && (
        <div className="card">
          <table>
            <thead>
              <tr>
                <th style={{ width: 64 }} />
                <th>名称</th>
                <th>类型</th>
                <th>路径</th>
                <th>摘要</th>
              </tr>
            </thead>
            <tbody>
              {hits.map((h) => (
                <tr key={`${h.ftype}-${h.ref_id}`}>
                  <td>
                    {h.thumb_url ? (
                      <img
                        src={h.thumb_url}
                        alt=""
                        style={{ width: 44, height: 44, objectFit: 'cover', borderRadius: 6 }}
                        loading="lazy"
                      />
                    ) : (
                      <div
                        style={{
                          width: 44,
                          height: 44,
                          borderRadius: 6,
                          background: 'var(--bg)',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontSize: 11,
                        }}
                      >
                        文件
                      </div>
                    )}
                  </td>
                  <td>{h.title}</td>
                  <td>
                    <span className="badge">{h.ftype === 'photo' ? '照片' : '文件'}</span>
                  </td>
                  <td className="muted">{h.rel_path}</td>
                  <td className="muted">{h.snippet ? renderSnippet(h.snippet) : null}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card">
        <h2>说明</h2>
        <ul className="muted" style={{ fontSize: 13, lineHeight: 1.9 }}>
          <li>关键字：照片文件名、标签（物体/场景）、目录名，以及所有已登记目录中的文件名；英文/数字走 FTS5 前缀匹配，中文自动回退子串匹配。</li>
          <li>语义：中文自然语言搜照片（Chinese-CLIP），如「海边玩水」「小朋友骑自行车」；新照片入库后由后台生成语义索引。</li>
        </ul>
      </div>
    </div>
  )
}
