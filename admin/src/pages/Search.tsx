import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SearchHit } from '../api/types'

export default function Search() {
  const [q, setQ] = useState('')
  const [type, setType] = useState('')
  const [hits, setHits] = useState<SearchHit[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run(e?: React.FormEvent) {
    e?.preventDefault()
    if (!q.trim()) return
    setBusy(true)
    setError(null)
    try {
      const res = await api.get<{ hits: SearchHit[] }>('/api/search', { q, type, limit: 200 })
      setHits(res.hits)
    } catch (err) {
      setError(err instanceof Error ? err.message : '搜索失败')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    const t = setTimeout(() => void run(), 350)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, type])

  return (
    <div>
      <form className="toolbar" onSubmit={run}>
        <input
          style={{ minWidth: 280 }}
          placeholder="搜索文件名 / 标签（FTS5 + 子串回退）"
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        <select value={type} onChange={(e) => setType(e.target.value)}>
          <option value="">全部</option>
          <option value="photo">照片</option>
          <option value="file">文件</option>
        </select>
        <button type="submit" disabled={busy}>
          搜索
        </button>
        {hits && <span className="muted">命中 {hits.length} 条</span>}
      </form>

      {error && <div className="error">{error}</div>}

      {hits && hits.length === 0 && <div className="empty">没有匹配结果</div>}

      {hits && hits.length > 0 && (
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
                  <td
                    className="muted"
                    dangerouslySetInnerHTML={{ __html: h.snippet ?? '' }}
                  />
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card">
        <h2>说明</h2>
        <ul className="muted" style={{ fontSize: 13, lineHeight: 1.9 }}>
          <li>索引覆盖：照片文件名、标签（物体/场景）、目录名；以及所有已登记目录中的文件名。</li>
          <li>英文/数字走 FTS5 前缀匹配；中文等无法被 unicode61 分词的场景自动回退为子串匹配。</li>
          <li>新文件在扫描入库时即写入索引，无需额外操作。</li>
        </ul>
      </div>
    </div>
  )
}
