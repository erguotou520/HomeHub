import { useCallback, useEffect, useRef, useState } from 'react'
import Hls from 'hls.js'
import { api, API_BASE, formatBytes, formatTime, getToken } from '../api/client'
import type { NvrCamera, NvrSegmentPage, NvrSettings, NvrTimelineHour } from '../api/types'

const STATUS_LABEL: Record<string, { text: string; cls: string }> = {
  recording: { text: '录制中', cls: 'ok' },
  disabled: { text: '未启用', cls: 'off' },
  error: { text: '异常', cls: 'err' },
  stopped: { text: '已停止', cls: 'off' },
}

export default function Monitor() {
  const [cameras, setCameras] = useState<NvrCamera[]>([])
  const [settings, setSettings] = useState<NvrSettings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const [form, setForm] = useState({
    name: '',
    label: '',
    rtsp_url: '',
    // 子码流：留空则按主码流的 /stream1 → /stream2 推导（老行为），
    // 填了就用它 —— 这是「主/子码流闭环」的落点。
    rtsp_sub_url: '',
    record_stream: 1,
    record_audio: false,
    preview_stream: 'sub' as 'main' | 'sub',
    enabled: true,
  })
  const [editingId, setEditingId] = useState<number | null>(null)
  const [cameraFormOpen, setCameraFormOpen] = useState(false)

  const [segmentsCam, setSegmentsCam] = useState<number | null>(null)
  const [segments, setSegments] = useState<NvrSegmentPage | null>(null)
  const [nextBefore, setNextBefore] = useState<number | null>(null)
  const [timeline, setTimeline] = useState<NvrTimelineHour[]>([])
  // 录像列表默认只显示"有变化"的分片（motion=2）；切换后可看全量。
  const [motionOnly, setMotionOnly] = useState(true)

  const [playing, setPlaying] = useState<{ id: number; url: string } | null>(null)

  // 预览弹层：实时（RTSP→HLS，hls.js）/ 历史（60s 分片直出）
  const [previewCam, setPreviewCam] = useState<NvrCamera | null>(null)
  const [previewTab, setPreviewTab] = useState<'live' | 'history'>('live')
  const [liveMsg, setLiveMsg] = useState('')
  const [liveReady, setLiveReady] = useState(false)
  const [previewSegs, setPreviewSegs] = useState<NvrSegmentPage | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const hlsRef = useRef<Hls | null>(null)

  const [settingsOpen, setSettingsOpen] = useState(false)
  const [settingsForm, setSettingsForm] = useState({
    retain_days: 30,
    max_gb: 0,
    segment_secs: 60,
    timezone: 'Asia/Shanghai',
    enabled: true,
  })
  const [savedFlash, setSavedFlash] = useState(false)

  // 手机端访问令牌：服务端只回掩码，明文只在「生成」那一次出现。
  const [tokenState, setTokenState] = useState<{ configured: boolean; length: number } | null>(null)
  const [tokenInput, setTokenInput] = useState('')
  const [freshToken, setFreshToken] = useState('')
  const [tokenMsg, setTokenMsg] = useState('')
  const previewBlobRef = useRef<string | null>(null)
  const playingBlobRef = useRef<string | null>(null)
  const pollRef = useRef<number | null>(null)

  const openPreview = useCallback(
    (c: NvrCamera, tab: 'live' | 'history' = 'live') => {
      setPreviewCam(c)
      setPreviewTab(tab)
      setLiveMsg('')
      setLiveReady(false)
      setPreviewSegs(null)
      if (tab === 'history') {
        api
          .get<NvrSegmentPage>('/api/admin/nvr/segments', {
            camera_id: c.id,
            limit: 50,
            motion: 2,
          })
          .then(setPreviewSegs)
          .catch((err) => setLiveMsg(err instanceof Error ? err.message : '加载录像失败'))
      }
    },
    [],
  )

  const closePreview = useCallback(() => {
    hlsRef.current?.destroy()
    hlsRef.current = null
    if (previewBlobRef.current) {
      URL.revokeObjectURL(previewBlobRef.current)
      previewBlobRef.current = null
    }
    setPreviewCam(null)
  }, [])

  // 实时 tab：按当前摄像头启停 hls.js
  useEffect(() => {
    if (!previewCam || previewTab !== 'live') return
    const video = videoRef.current
    if (!video) return
    // No trailing slash: the server rewrites segment URIs to absolute paths
    // (`/api/nvr/live/{cam}/seg.m4s`), so hls.js no longer needs a base path
    // to resolve against — and axum does not match trailing-slash routes, so
    // a trailing slash falls through to the SPA fallback (HTML →
    // ManifestParsingError).
    const base = `${API_BASE}/api/nvr/live/${encodeURIComponent(previewCam.name)}`
    setLiveReady(false)
    setLiveMsg('拉流中（首次需几秒启动转码）…')
    let destroyed = false
    const dispose = () => {
      hlsRef.current?.destroy()
      hlsRef.current = null
    }
    if (Hls.isSupported()) {
      const hls = new Hls({
        lowLatencyMode: true,
        manifestLoadingMaxRetry: 6,
        manifestLoadingRetryDelay: 1000,
        // The query token only rides on the playlist URL; fragments are
        // fetched by hls.js without it. Attach the JWT to every request.
        xhrSetup: (xhr: XMLHttpRequest) => {
          const t = getToken()
          if (t) xhr.setRequestHeader('Authorization', `Bearer ${t}`)
        },
      })
      hlsRef.current = hls
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (data.fatal) {
          setLiveMsg(`直播暂不可用：${data.type}（${data.details}）`)
          hls.destroy()
          hlsRef.current = null
        }
      })
      hls.on(Hls.Events.FRAG_CHANGED, () => {
        if (!destroyed) {
          setLiveReady(true)
          setLiveMsg('')
        }
      })
      hls.loadSource(base)
      hls.attachMedia(video)
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = base
    } else {
      setLiveMsg('当前浏览器不支持 HLS 播放')
    }
    return () => {
      destroyed = true
      dispose()
    }
  }, [previewCam, previewTab])

  /**
   * 取回一段录像并交给 `<video>` 播放。
   *
   * 数据面要凭据（`X-Device-Token` 或管理员 JWT），而 `<video src>` 设不了请求头
   * —— 所以先带着 Authorization 把分片取回来，再喂一个 blob URL。这样 URL 里
   * 不必再挂 `?token=`（凭据会漏进代理日志与浏览器历史）。代价是整段先落内存
   * （60s 分片约几 MB，内网无感），换来的是没有再依赖服务端接受 query 凭据。
   */
  async function fetchSegmentBlobUrl(id: number) {
    const t = getToken()
    const url = new URL(`${API_BASE}/api/nvr/segments/${id}`, window.location.origin)
    const res = await fetch(url.toString(), {
      headers: t ? { Authorization: `Bearer ${t}` } : {},
    })
    if (!res.ok) throw new Error(`取回录像失败：HTTP ${res.status}`)
    return URL.createObjectURL(await res.blob())
  }

  async function playPreviewSegment(id: number) {
    const video = videoRef.current
    if (!video) {
      // 弹层没开着（例如从别处调用）：用底部的回放弹层兜底。
      try {
        const url = await fetchSegmentBlobUrl(id)
        if (playingBlobRef.current) URL.revokeObjectURL(playingBlobRef.current)
        playingBlobRef.current = url
        setPlaying({ id, url })
      } catch (err) {
        setError(err instanceof Error ? err.message : '播放失败')
      }
      return
    }
    hlsRef.current?.destroy()
    hlsRef.current = null
    setLiveMsg('缓冲中…')
    try {
      const url = await fetchSegmentBlobUrl(id)
      if (previewBlobRef.current) URL.revokeObjectURL(previewBlobRef.current)
      previewBlobRef.current = url
      video.removeAttribute('src')
      video.load()
      video.src = url
      video.play().catch(() => {})
      setLiveMsg('')
    } catch (err) {
      setLiveMsg(err instanceof Error ? err.message : '播放失败')
    }
  }

  function closePlaying() {
    if (playingBlobRef.current) {
      URL.revokeObjectURL(playingBlobRef.current)
      playingBlobRef.current = null
    }
    setPlaying(null)
  }

  const loadCameras = useCallback(async () => {
    try {
      const r = await api.get<{ cameras: NvrCamera[] }>('/api/admin/nvr/cameras')
      setCameras(r.cameras)
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }, [])

  const loadSettings = useCallback(async () => {
    try {
      const s = await api.get<NvrSettings>('/api/admin/nvr/settings')
      setSettings(s)
      setSettingsForm({
        retain_days: s.retain_days,
        max_gb: s.max_gb,
        segment_secs: s.segment_secs,
        timezone: s.timezone,
        enabled: s.enabled,
      })
    } catch {
      // 设置拉取失败不阻塞主界面
    }
  }, [])

  useEffect(() => {
    loadCameras()
    loadSettings()
    // 状态轮询：有录制中/异常时 5s，否则 15s
    const tick = async () => {
      await loadCameras()
      const cams = await safeCameras()
      const active = cams.some((c) => c.status === 'recording' || c.status === 'error')
      const delay = active ? 5000 : 15000
      pollRef.current = window.setTimeout(tick, delay)
    }
    void tick()
    return () => {
      if (pollRef.current) window.clearTimeout(pollRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadCameras])

  async function safeCameras(): Promise<NvrCamera[]> {
    try {
      const r = await api.get<{ cameras: NvrCamera[] }>('/api/admin/nvr/cameras')
      return r.cameras
    } catch {
      return []
    }
  }

  const loadDeviceToken = useCallback(async () => {
    try {
      const r = await api.get<{ configured: boolean; length: number; masked: string }>(
        '/api/admin/nvr/device-token',
      )
      setTokenState({ configured: r.configured, length: r.length })
      // 掩码原样回传 = 「没改」；服务端认这个约定，所以误点保存不会清掉令牌。
      setTokenInput(r.masked)
    } catch {
      // 服务端没有这个接口（旧版本）时不要卡住整页
      setTokenState(null)
    }
  }, [])

  useEffect(() => {
    void loadDeviceToken()
  }, [loadDeviceToken])

  async function saveDeviceToken() {
    const value = tokenInput.trim()
    if (value === '' && tokenState?.configured) {
      if (!window.confirm('留空 = 关闭校验：同网段任何设备都能看监控。确定关闭？')) return
    }
    setBusy(true)
    setTokenMsg('')
    setError(null)
    try {
      await api.put('/api/admin/nvr/device-token', { token: value })
      setFreshToken('')
      await loadDeviceToken()
      setTokenMsg(value === '' ? '已关闭校验' : '已保存')
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存令牌失败')
    } finally {
      setBusy(false)
    }
  }

  async function generateDeviceToken() {
    if (tokenState?.configured && !window.confirm('重新生成后旧令牌立即失效，手机需要重新填写。继续？')) {
      return
    }
    setBusy(true)
    setTokenMsg('')
    setError(null)
    try {
      const r = await api.post<{ token: string }>('/api/admin/nvr/device-token/generate')
      setFreshToken(r.token)
      await loadDeviceToken()
      setTokenMsg('已生成并启用')
    } catch (err) {
      setError(err instanceof Error ? err.message : '生成令牌失败')
    } finally {
      setBusy(false)
    }
  }

  async function saveCamera(e: React.FormEvent) {
    e.preventDefault()
    if (!form.name.trim() || !form.rtsp_url.trim()) {
      setError('名称和 RTSP 地址必填')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const body = {
        name: form.name.trim(),
        label: form.label.trim() || null,
        rtsp_url: form.rtsp_url.trim(),
        // '' 是「清空 → 回退到推导」的约定，服务端认这个值。
        rtsp_sub_url: form.rtsp_sub_url.trim(),
        record_stream: form.record_stream,
        record_audio: form.record_audio,
        preview_stream: form.preview_stream,
        enabled: form.enabled,
      }
      if (editingId === null) {
        await api.post('/api/admin/nvr/cameras', body)
      } else {
        const { name, ...rest } = body
        await api.put(`/api/admin/nvr/cameras/${editingId}`, rest)
      }
      resetForm()
      setCameraFormOpen(false)
      await loadCameras()
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败')
    } finally {
      setBusy(false)
    }
  }

  function resetForm() {
    setForm({
      name: '',
      label: '',
      rtsp_url: '',
      rtsp_sub_url: '',
      record_stream: 1,
      record_audio: false,
      preview_stream: 'sub',
      enabled: true,
    })
    setEditingId(null)
  }

  function openCameraForm(c: NvrCamera | null) {
    if (c) {
      setEditingId(c.id)
      setForm({
        name: c.name,
        label: c.label ?? '',
        rtsp_url: c.rtsp_url,
        rtsp_sub_url: c.rtsp_sub_url ?? '',
        record_stream: c.record_stream,
        record_audio: c.record_audio,
        preview_stream: c.preview_stream === 'main' ? 'main' : 'sub',
        enabled: c.enabled,
      })
    } else {
      resetForm()
    }
    setError(null)
    setCameraFormOpen(true)
  }

  async function removeCamera(c: NvrCamera) {
    if (!window.confirm(`删除摄像头「${c.label || c.name}」？其录像文件保留，仅停止录制并移除配置。`)) return
    setBusy(true)
    setError(null)
    try {
      await api.del(`/api/admin/nvr/cameras/${c.id}`)
      if (segmentsCam === c.id) setSegmentsCam(null)
      await loadCameras()
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败')
    } finally {
      setBusy(false)
    }
  }

  async function restartCamera(c: NvrCamera) {
    setBusy(true)
    try {
      await api.post(`/api/admin/nvr/cameras/${c.id}/restart`)
      await loadCameras()
    } catch (err) {
      setError(err instanceof Error ? err.message : '重启失败')
    } finally {
      setBusy(false)
    }
  }

  async function openSegments(c: NvrCamera, useMotionFilter?: boolean) {
    const filter = useMotionFilter ?? motionOnly
    setSegmentsCam(c.id)
    setSegments(null)
    setNextBefore(null)
    setTimeline([])
    try {
      const [page, tl] = await Promise.all([
        api.get<NvrSegmentPage>('/api/admin/nvr/segments', {
          camera_id: c.id,
          limit: 50,
          motion: filter ? 2 : undefined,
        }),
        api.get<{ hours: NvrTimelineHour[] }>(`/api/admin/nvr/segments/${encodeURIComponent(c.name)}/timeline`, {}),
      ])
      setSegments(page)
      setNextBefore(page.next_before)
      setTimeline(tl.hours)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载录像失败')
    }
  }

  async function loadMoreSegments() {
    if (segmentsCam === null || nextBefore === null) return
    try {
      const page = await api.get<NvrSegmentPage>('/api/admin/nvr/segments', {
        camera_id: segmentsCam,
        limit: 50,
        before: nextBefore,
        motion: motionOnly ? 2 : undefined,
      })
      setSegments((prev) => (prev ? { ...prev, segments: [...prev.segments, ...page.segments] } : page))
      setNextBefore(page.next_before)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载更多失败')
    }
  }

  async function playSegment(id: number) {
    try {
      const url = await fetchSegmentBlobUrl(id)
      if (playingBlobRef.current) URL.revokeObjectURL(playingBlobRef.current)
      playingBlobRef.current = url
      setPlaying({ id, url })
    } catch (err) {
      setError(err instanceof Error ? err.message : '播放失败')
    }
  }

  async function removeSegment(segId: number) {
    if (!window.confirm('删除该录像分片（文件+记录）？')) return
    try {
      await api.del(`/api/admin/nvr/segments/${segId}`)
      if (segmentsCam !== null) {
        const c = cameras.find((x) => x.id === segmentsCam)
        if (c) openSegments(c)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败')
    }
  }

  async function saveSettings(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.put('/api/admin/nvr/settings', settingsForm)
      setSavedFlash(true)
      setTimeout(() => setSavedFlash(false), 2500)
      await loadSettings()
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败')
    } finally {
      setBusy(false)
    }
  }

  async function runCleanup() {
    if (!window.confirm('立即执行一次保留策略清理（删除过期分片）？')) return
    setBusy(true)
    setError(null)
    try {
      const r = await api.post<{ removed: number; remaining: number }>('/api/admin/nvr/cleanup')
      alert(`清理完成：删除 ${r.removed} 个分片，剩余 ${r.remaining} 个`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '清理失败')
    } finally {
      setBusy(false)
    }
  }

  if (error && !cameras.length && !settings) {
    return (
      <div className="card">
        <h2>监控</h2>
        <div className="error">{error}</div>
        <p className="muted">若提示 nvr disabled，请先在 server/config.yaml 中启用 nvr 节并重启服务。</p>
      </div>
    )
  }

  const segCam = cameras.find((c) => c.id === segmentsCam) ?? null
  const recordingCount = cameras.filter((c) => c.status === 'recording').length
  const totalSize = cameras.reduce((s, c) => s + c.total_size_bytes, 0)

  return (
    <div>
      {/* 顶栏：状态概览 + 操作 */}
      <div className="cam-page-head">
        <div className="cam-page-title">
          <h2>监控</h2>
          <span className="muted small">
            {cameras.length > 0 && (
              <>
                <span className={`cam-head-dot ${recordingCount > 0 ? 'on' : ''}`} />
                {recordingCount}/{cameras.length} 台录制中 · 已占用 {formatBytes(totalSize)} · 保留{' '}
                {settings?.retain_days ?? '-'} 天
              </>
            )}
          </span>
        </div>
        <div className="row">
          <button type="button" onClick={() => setSettingsOpen(true)}>
            设置
          </button>
          <button type="button" className="primary" onClick={() => openCameraForm(null)}>
            + 添加摄像头
          </button>
        </div>
      </div>

      {settings && !settings.ffmpeg_available && (
        <div className="error" style={{ marginBottom: 12 }}>
          ffmpeg 不可用：NVR 已停用，请确认服务器安装了 ffmpeg/ffprobe。
        </div>
      )}

      {/* 摄像头卡片网格 */}
      {cameras.length > 0 ? (
        <div className="cam-grid">
          {cameras.map((c) => {
            const st = STATUS_LABEL[c.status] ?? { text: c.status, cls: 'off' }
            return (
              <div
                key={c.id}
                className={`cam-card st-${st.cls}`}
                onClick={() => openPreview(c)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => e.key === 'Enter' && openPreview(c)}
              >
                <div className="cam-thumb">
                  <svg viewBox="0 0 24 24" className="cam-thumb-icon" aria-hidden>
                    <path d="M17 10.5V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-3.5l4 4v-11l-4 4Z" />
                  </svg>
                  {c.status === 'recording' && <span className="cam-rec-dot" title="录制中" />}
                  <div className="cam-thumb-hover">
                    <span>点击预览</span>
                  </div>
                </div>
                <div className="cam-info">
                  <div className="cam-name-row">
                    <strong>{c.label || c.name}</strong>
                    <span className={`badge ${st.cls}`}>{st.text}</span>
                  </div>
                  <div className="cam-meta">
                    {c.total_segments > 0 ? (
                      <>
                        今日 {c.today_segments} 片 · {formatBytes(c.total_size_bytes)}
                        {c.last_segment_at ? ` · ${formatTime(c.last_segment_at)}` : ''}
                      </>
                    ) : (
                      '暂无录像'
                    )}
                  </div>
                  <div className="cam-meta">
                    录制 {c.record_stream === 2 ? '子码流' : '主码流'} · 预览{' '}
                    {c.preview_stream === 'main' ? '主码流' : '子码流'}
                    {c.rtsp_sub_url ? '' : ' · 子码流自动推导'}
                  </div>
                  {c.last_error && <div className="cam-error">⚠ {c.last_error}</div>}
                </div>
                <div className="cam-actions" onClick={(e) => e.stopPropagation()}>
                  <button type="button" onClick={() => openPreview(c)}>
                    预览
                  </button>
                  <button type="button" onClick={() => openSegments(c)}>
                    录像
                  </button>
                  <button type="button" onClick={() => openCameraForm(c)}>
                    编辑
                  </button>
                  <button type="button" onClick={() => restartCamera(c)} disabled={busy}>
                    重启
                  </button>
                  <button
                    type="button"
                    className="danger"
                    onClick={() => removeCamera(c)}
                    disabled={busy}
                  >
                    删除
                  </button>
                </div>
              </div>
            )
          })}
        </div>
      ) : (
        <div className="card cam-empty">
          <div className="cam-empty-icon" aria-hidden>
            <svg viewBox="0 0 24 24">
              <path d="M17 10.5V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-3.5l4 4v-11l-4 4Z" />
            </svg>
          </div>
          <p>还没有摄像头</p>
          <button type="button" className="primary" onClick={() => openCameraForm(null)}>
            添加第一台 RTSP 摄像头
          </button>
        </div>
      )}

      {/* 录像详情：时间轴 + 分片列表 */}
      {segCam && (
        <div className="card cam-detail">
          <div className="cam-detail-head">
            <button type="button" className="cam-back" onClick={() => setSegmentsCam(null)} aria-label="返回">
              ←
            </button>
            <h3>
              {segCam.label || segCam.name}
              <span className="muted"> · 录像</span>
            </h3>
            <span className="muted small cam-detail-count">{segments?.segments.length ?? 0} 片</span>
            <div className="row cam-detail-actions">
              <button
                type="button"
                className={motionOnly ? 'primary' : ''}
                title="只列出检测到画面变化的分片"
                onClick={() => {
                  const next = !motionOnly
                  setMotionOnly(next)
                  if (segCam) openSegments(segCam, next)
                }}
              >
                {motionOnly ? '只看动态' : '全部分片'}
              </button>
              <button type="button" onClick={() => openPreview(segCam, 'history')}>
                预览
              </button>
            </div>
          </div>

          {timeline.length > 0 && (
            <div className="timeline-ruler" aria-label="近 7 天每小时覆盖">
              {timeline.map((h) => (
                <div
                  key={h.hour_start}
                  title={`${new Date(h.hour_start * 1000).toLocaleString('zh-CN', { hour12: false })} · ${h.segments} 片 · ${formatBytes(h.bytes)}`}
                  className={`timeline-cell ${h.segments > 0 ? 'on' : 'off'}`}
                />
              ))}
            </div>
          )}

          {segments ? (
            <div className="clip-list">
              {segments.segments.map((s) => (
                <div key={s.id} className="clip-row">
                  <button type="button" className="clip-play" onClick={() => playSegment(s.id)} title="播放">
                    <svg viewBox="0 0 24 24" aria-hidden>
                      <path d="M8 5v14l11-7L8 5Z" />
                    </svg>
                  </button>
                  <div className="clip-time">
                    <div>
                      {formatTime(s.start_time)}
                      {s.motion === 2 && <span className="badge ok clip-motion">动态</span>}
                      {s.motion === 1 && <span className="badge off clip-motion">静态</span>}
                    </div>
                    <div className="muted small">
                      {s.duration.toFixed(0)}s
                      {s.width ? ` · ${s.width}×${s.height}` : ''}
                      {s.video_codec ? ` · ${s.video_codec}` : ''}
                    </div>
                  </div>
                  <div className="clip-size">{formatBytes(s.size_bytes)}</div>
                  <button
                    type="button"
                    className="clip-del"
                    onClick={() => removeSegment(s.id)}
                    title="删除"
                    aria-label="删除"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden>
                      <path d="M9 3h6l1 2h4v2H4V5h4l1-2Zm-3 6h12l-1 12H7L6 9Z" />
                    </svg>
                  </button>
                </div>
              ))}
              {segments.segments.length === 0 && (
                <div className="empty">
                  <p>暂无录像</p>
                </div>
              )}
            </div>
          ) : (
            <div className="empty">
              <p>加载中…</p>
            </div>
          )}
          {nextBefore !== null && (
            <div className="clip-more">
              <button type="button" onClick={loadMoreSegments}>
                加载更早
              </button>
            </div>
          )}
        </div>
      )}

      {/* 预览弹层：实时 / 历史 */}
      {previewCam && (
        <div className="modal-overlay" onClick={closePreview}>
          <div className="modal preview-modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <h3>{previewCam.label || previewCam.name}</h3>
              <button type="button" className="modal-close" onClick={closePreview} aria-label="关闭">
                ×
              </button>
            </div>

            <div className="preview-stage">
              <video ref={videoRef} controls autoPlay muted playsInline />
              {previewTab === 'live' && (
                <span className={`preview-live-badge ${liveReady ? 'on' : ''}`}>
                  {liveReady ? 'LIVE' : '连接中'}
                </span>
              )}
              {previewTab === 'live' && !liveReady && (
                <div className="preview-hint">{liveMsg || '正在建立直播…'}</div>
              )}
            </div>

            <div className="preview-tabs" role="tablist" aria-label="预览模式">
              <button
                type="button"
                role="tab"
                aria-selected={previewTab === 'live'}
                className={previewTab === 'live' ? 'primary' : ''}
                onClick={() => setPreviewTab('live')}
              >
                实时
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={previewTab === 'history'}
                className={previewTab === 'history' ? 'primary' : ''}
                onClick={() => setPreviewTab('history')}
              >
                历史
              </button>
              {previewTab === 'live' && (
                <span className="preview-note">按需 RTSP→HLS 转码 · 延迟约 4–6s</span>
              )}
            </div>

            {previewTab === 'history' && (
              <div className="preview-history">
                {previewSegs && previewSegs.segments.length > 0 ? (
                  <table className="table">
                    <thead>
                      <tr>
                        <th>开始时间</th>
                        <th>时长</th>
                        <th>大小</th>
                        <th>分辨率</th>
                        <th>操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {previewSegs.segments.slice(0, 30).map((s) => (
                        <tr key={s.id}>
                          <td className="small">{formatTime(s.start_time)}</td>
                          <td>{s.duration.toFixed(0)}s</td>
                          <td className="small">{formatBytes(s.size_bytes)}</td>
                          <td className="small">{s.width ? `${s.width}×${s.height}` : '-'}</td>
                          <td>
                            <button type="button" onClick={() => playPreviewSegment(s.id)}>
                              播放
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                ) : (
                  <div className="empty">
                    <p>{liveMsg || '暂无历史录像'}</p>
                  </div>
                )}
                <p className="preview-note">点击播放后分片显示在上方播放区；更多录像请在「录像」中查看。</p>
              </div>
            )}
          </div>
        </div>
      )}

      {/* 添加 / 编辑摄像头 */}
      {cameraFormOpen && (
        <div className="modal-overlay" onClick={() => setCameraFormOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <span className="truncate">{editingId === null ? '添加摄像头' : '编辑摄像头'}</span>
              <button type="button" className="modal-close" onClick={() => setCameraFormOpen(false)} aria-label="关闭">
                ×
              </button>
            </div>
            <div className="modal-body">
              <form onSubmit={saveCamera} className="grid-4">
                <div className="field">
                  <label>名称（a-z A-Z 0-9 _ -）</label>
                  <input
                    value={form.name}
                    onChange={(e) => setForm({ ...form, name: e.target.value })}
                    placeholder="front_door"
                    disabled={editingId !== null}
                  />
                </div>
                <div className="field">
                  <label>显示名</label>
                  <input
                    value={form.label}
                    onChange={(e) => setForm({ ...form, label: e.target.value })}
                    placeholder="前门"
                  />
                </div>
                <div className="field span-2">
                  <label>主码流 RTSP 地址</label>
                  <input
                    value={form.rtsp_url}
                    onChange={(e) => setForm({ ...form, rtsp_url: e.target.value })}
                    placeholder="rtsp://user:pass@192.168.x.x/stream1"
                  />
                </div>
                <div className="field span-2">
                  <label>子码流 RTSP 地址（留空 = 由主码流把 /stream1 换成 /stream2）</label>
                  <input
                    value={form.rtsp_sub_url}
                    onChange={(e) => setForm({ ...form, rtsp_sub_url: e.target.value })}
                    placeholder="rtsp://user:pass@192.168.x.x/stream2"
                  />
                </div>
                <div className="field">
                  <label>录制码流</label>
                  <select
                    value={form.record_stream}
                    onChange={(e) => setForm({ ...form, record_stream: Number(e.target.value) })}
                  >
                    <option value={1}>主码流</option>
                    <option value={2}>子码流</option>
                  </select>
                </div>
                <div className="field">
                  <label>预览码流（手机/网页实时预览）</label>
                  <select
                    value={form.preview_stream}
                    onChange={(e) =>
                      setForm({ ...form, preview_stream: e.target.value === 'main' ? 'main' : 'sub' })
                    }
                  >
                    <option value="sub">子码流（省流量，推荐）</option>
                    <option value="main">主码流（清晰）</option>
                  </select>
                </div>
                <div className="field">
                  <label>音频</label>
                  <select
                    value={form.record_audio ? '1' : '0'}
                    onChange={(e) => setForm({ ...form, record_audio: e.target.value === '1' })}
                  >
                    <option value="0">关闭</option>
                    <option value="1">AAC 32k</option>
                  </select>
                </div>
                <div className="field">
                  <label>启用</label>
                  <select
                    value={form.enabled ? '1' : '0'}
                    onChange={(e) => setForm({ ...form, enabled: e.target.value === '1' })}
                  >
                    <option value="1">是</option>
                    <option value="0">否</option>
                  </select>
                </div>
                <div className="modal-foot form-actions">
                  <button type="submit" disabled={busy}>
                    {editingId === null ? '添加' : '保存'}
                  </button>
                  {error && <span className="muted small">{error}</span>}
                </div>
              </form>
            </div>
          </div>
        </div>
      )}

      {/* 设置 */}
      {settingsOpen && (
        <div className="modal-overlay" onClick={() => setSettingsOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <span className="truncate">NVR 设置</span>
              <button type="button" className="modal-close" onClick={() => setSettingsOpen(false)} aria-label="关闭">
                ×
              </button>
            </div>
            <div className="modal-body">
              <form onSubmit={saveSettings} className="grid-4">
                <div className="field">
                  <label>启用 NVR</label>
                  <select
                    value={settingsForm.enabled ? '1' : '0'}
                    onChange={(e) => setSettingsForm({ ...settingsForm, enabled: e.target.value === '1' })}
                  >
                    <option value="1">是</option>
                    <option value="0">否</option>
                  </select>
                </div>
                <div className="field">
                  <label>保留天数</label>
                  <input
                    type="number"
                    min={1}
                    max={365}
                    value={settingsForm.retain_days}
                    onChange={(e) => setSettingsForm({ ...settingsForm, retain_days: Number(e.target.value) })}
                  />
                </div>
                <div className="field">
                  <label>磁盘上限 (GB，0=不限)</label>
                  <input
                    type="number"
                    min={0}
                    value={settingsForm.max_gb}
                    onChange={(e) => setSettingsForm({ ...settingsForm, max_gb: Number(e.target.value) })}
                  />
                </div>
                <div className="field">
                  <label>分片秒数 (10-600)</label>
                  <input
                    type="number"
                    min={10}
                    max={600}
                    value={settingsForm.segment_secs}
                    onChange={(e) => setSettingsForm({ ...settingsForm, segment_secs: Number(e.target.value) })}
                  />
                </div>
                <div className="field">
                  <label>时区</label>
                  <input
                    value={settingsForm.timezone}
                    onChange={(e) => setSettingsForm({ ...settingsForm, timezone: e.target.value })}
                  />
                </div>
                <div className="field field-readonly">
                  <label>已占用</label>
                  <span className="field-value">{formatBytes(settings?.total_size_bytes)}</span>
                </div>

                {/* 手机端访问令牌：数据面（摄像头/实时/回放）的共享凭据 */}
                <div className="field" style={{ gridColumn: '1 / -1' }}>
                  <label>手机访问令牌（App 的「设置 → 服务器 → 访问令牌」填同一个值）</label>
                  <input
                    value={tokenInput}
                    onChange={(e) => setTokenInput(e.target.value)}
                    placeholder="留空 = 关闭校验（同网段任何设备都能看监控）"
                  />
                  <div className="row" style={{ marginTop: 8, alignItems: 'center', gap: 8 }}>
                    <button type="button" onClick={saveDeviceToken} disabled={busy}>
                      保存
                    </button>
                    <button type="button" onClick={generateDeviceToken} disabled={busy}>
                      生成随机令牌
                    </button>
                    {tokenState?.configured ? (
                      <span className="badge ok">已启用 {tokenState.length} 位</span>
                    ) : (
                      <span className="badge off">未设置 · 接口开放</span>
                    )}
                    {tokenMsg && <span className="muted small">{tokenMsg}</span>}
                  </div>
                  <p className="muted small" style={{ marginTop: 6 }}>
                    手机端的监控只读接口（摄像头列表 / 实时 / 回放）都要带上它；管理员 JWT
                    同样有效，所以本页的预览不受影响。留空即关闭校验，回到「谁都能看」的旧行为。
                  </p>
                  {freshToken && (
                    <div className="row" style={{ marginTop: 6, alignItems: 'center', gap: 8 }}>
                      <code className="mono">{freshToken}</code>
                      <button
                        type="button"
                        onClick={() => {
                          void navigator.clipboard?.writeText(freshToken)
                          setTokenMsg('已复制')
                        }}
                      >
                        复制
                      </button>
                      <span className="muted small">只显示这一次，请立刻抄到手机上</span>
                    </div>
                  )}
                </div>
                <div className="modal-foot form-actions">
                  <button type="submit" disabled={busy}>
                    保存
                  </button>
                  <button type="button" onClick={runCleanup} disabled={busy}>
                    立即清理
                  </button>
                  <span className="form-actions-spacer" />
                  {savedFlash && <span className="badge ok">已保存</span>}
                </div>
              </form>
            </div>
          </div>
        </div>
      )}

      {/* 回放弹层 */}
      {playing && (
        <div className="modal-overlay" onClick={closePlaying}>
          <div className="modal preview-modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <span className="truncate">分片 #{playing.id}</span>
              <button type="button" className="modal-close" onClick={closePlaying} aria-label="关闭">
                ×
              </button>
            </div>
            <div className="modal-body">
              <div className="preview-stage" style={{ marginBottom: 10 }}>
                <video src={playing.url} controls autoPlay />
              </div>
              <p className="muted small">
                带 Authorization 取回整段后用 blob 播放（`&lt;video&gt;` 设不了请求头，
                也避免把凭据放进 URL）。moov 在文件尾，首次播放会先请求尾部。
              </p>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
