export interface PhotoItem {
  id: number
  dir_id: number
  dir_name: string
  rel_path: string
  name: string
  taken_at: number
  width?: number | null
  height?: number | null
  size?: number
  orientation?: number
  gps_lat?: number | null
  gps_lng?: number | null
  camera_make?: string | null
  camera_model?: string | null
  file_hash?: string | null
  pixel_hash?: string | null
  media_kind?: 'photo' | 'video'
  duration_ms?: number | null
  video_codec?: string | null
  url: string
  thumb_url: string
  tags: PhotoTag[]
}

export interface PhotoTag {
  tag: string
  kind: string
  confidence: number
}

export interface TimelineGroup {
  key: string
  label: string
  year: number
  month?: number | null
  count: number
  items: PhotoItem[]
}

/**
 * One page of `/api/photos/timeline`.
 *
 * Only sent when the request carries `limit`; without it the server answers
 * with the whole library and `has_more` false.
 */
export interface TimelinePage {
  groups: TimelineGroup[]
  has_more: boolean
  /** `(taken_at, id)` of the page's oldest item — pass both back to continue. */
  next_before: number | null
  next_before_id: number | null
}

export interface TreeGroup {
  dir_id: number
  dir_name: string
  path: string
  count: number
  items: PhotoItem[]
}

export interface TagSummary {
  tag: string
  kind: string
  photo_count: number
  cover_url?: string | null
}

export interface PersonGroupSummary {
  id: number
  name?: string | null
  face_count: number
  photo_count: number
  cover_url?: string | null
}

export interface GeoPoint {
  lat: number
  lng: number
  count: number
  thumb_url?: string | null
  photo_ids: number[]
}

export interface DirStat {
  id: number
  name: string
  path: string
  marks: string[]
  ignore_rules: string[]
  enabled: boolean
  file_count: number
  photo_count: number
  tagged_count: number
  bytes_saved: number
  total_bytes: number
}

export interface DirInput {
  name: string
  path: string
  marks: string[]
  ignore: string[]
  enabled: boolean
}

export interface QueueStatus {
  kind: string
  pending: number
  running: number
  failed: number
  done: number
  concurrency: number
}

export interface AlbumProgress {
  name: string
  dir_id: number
  total: number
  embedded: number
}

export interface TaskStatus {
  queues: QueueStatus[]
  running: number
  throughput_5min: number
  progress?: AlbumProgress[]
  recognition_pending?: number
  recognition_failed?: number
}

export interface FailedTask {
  id: number
  kind: string
  payload: string
  attempts: number
  error?: string | null
  updated_at: number
}

export interface WgPeer {
  id: number
  public_key?: string | null
  name: string
  tunnel_ip: string
  first_seen: number
  last_seen: number
  enabled: number
  source: string
}

export interface AuditLog {
  id: number
  peer_id?: number | null
  peer_ip?: string | null
  method: string
  path: string
  status: number
  bytes: number
  created_at: number
}

export interface PeerTraffic {
  peer_id: number
  peer_name: string
  tunnel_ip: string
  requests: number
  bytes: number
  last_seen?: number | null
}

export interface TrashEntry {
  id: number
  dir_id?: number | null
  dir_name: string
  rel_path: string
  trash_path: string
  is_dir: number
  size: number
  trashed_at: number
  purged_due: number
}

export interface DuplicateGroup {
  fingerprint: string
  reason: string
  items: PhotoItem[]
}

export interface SearchHit {
  ftype: string
  ref_id: string
  title: string
  snippet?: string | null
  photo_id?: number | null
  dir_id?: number | null
  rel_path?: string | null
  thumb_url?: string | null
}

export interface Alert {
  id: number
  level: string
  kind: string
  message: string
  created_at: number
  resolved_at?: number | null
  notified: number
}

export interface BackupInfo {
  name: string
  bytes: number
  created_at: number
}

export interface BackupStatus {
  enabled: boolean
  interval_hours: number
  keep: number
  dir: string
  items: BackupInfo[]
  total_bytes: number
  last_backup_at?: number | null
  last_error?: string | null
}

export interface SystemInfo {
  version: string
  uptime_secs: number
  db_bytes: number
  disk_usage_percent?: number | null
  started_at: number
  alerts: Alert[]
  ml_backend: string
  data_dir: string
  backup?: BackupStatus | null
}

export interface Stats {
  dirs: DirStat[]
  photos: number
  videos?: number
  tagged: number
  faces: number
  people: number
  trash: { count: number; bytes: number }
  originals: { count: number; bytes: number }
}

export interface FileEntry {
  name: string
  path: string
  is_dir: boolean
  size?: number | null
  modified?: number | null
  mime_type?: string | null
  media_kind: string
}

export interface RuntimeSettings {
  tasks: {
    concurrency: { cpu: number; io: number }
    'rate-limit-per-sec': number
    'file-timeout-secs': number
    'max-attempts': number
    'max-workers': number
    'work-window': { enabled: boolean; start: string; end: string }
    'full-rescan': string
    'full-rescan-hour': number
  }
  ml: {
    backend: string
    enabled: boolean
    object: { enabled: boolean; model: string; threshold: number; labels: string }
    scene: { enabled: boolean; model: string; labels: string; threshold: number }
    face: { enabled: boolean; model: string; threshold: number; 'cluster-threshold': number }
    'min-image-size': number
    'exclude-tags': string[]
    'onnx-threads': number
  }
  compression: {
    enabled: boolean
    'min-saving-percent': number
    'png-level': number
    'jpegtran-path': string
  }
  originals: { enabled: boolean; 'retention-days': number; 'max-usage-percent': number }
  trash: { 'retention-days': number; dir?: string | null }
  audit: { 'retention-days': number; 'batch-size': number; 'flush-interval-secs': number }
  backup: { enabled: boolean; 'interval-hours': number; keep: number; dir?: string | null }
  alerts: {
    enabled: boolean
    'disk-usage-percent': number
    'task-failure-threshold': number
    'cooldown-secs': number
    serverchan: { enabled: boolean; 'send-key': string }
  }
  nvr?: {
    enabled: boolean
    'record-dir'?: string | null
    timezone: string
    'segment-secs': number
    'expire-interval-mins': number
    'retain-days': number
    'max-gb': number
    'maintain-interval-secs': number
    video: {
      source: string
      scale: string
      fps: number
      crf: number
      maxrate: string
      bufsize: string
      encoder: string
      preset: string
      audio: boolean
    }
  }
}

// ─────────────────────────────── NVR ────────────────────────────────────

export interface NvrCamera {
  id: number
  name: string
  label: string | null
  /** 主码流。**管理端返回的是带凭据的原始地址**（设备面才打码）。 */
  rtsp_url: string
  /** 子码流；null = 由主码流把 /stream1 换成 /stream2 推导。 */
  rtsp_sub_url: string | null
  enabled: boolean
  record_stream: number
  record_audio: boolean
  /** 实时预览中继拉哪一路：'sub'（默认）| 'main'。 */
  preview_stream: 'sub' | 'main'
  status: string
  restart_count: number
  last_error: string | null
  last_segment_at: number | null
  today_segments: number
  total_size_bytes: number
  total_segments: number
}

export interface NvrSegment {
  id: number
  camera_id: number
  path: string
  start_time: number
  end_time: number
  duration: number
  size_bytes: number
  video_codec: string | null
  width: number | null
  height: number | null
  /** 0 = unknown, 1 = still, 2 = active (motion detected) */
  motion: number
  /** fraction of frames flagged as scene changes, 0..1 */
  motion_score: number
}

export interface NvrSegmentPage {
  segments: NvrSegment[]
  next_before: number | null
  limit: number
}

export interface NvrTimelineHour {
  hour_start: number
  hour_end: number
  segments: number
  bytes: number
}

export interface NvrSettings {
  enabled: boolean
  record_dir: string
  timezone: string
  segment_secs: number
  expire_interval_mins: number
  retain_days: number
  max_gb: number
  maintain_interval_secs: number
  video: {
    source: string
    scale: string
    fps: number
    crf: number
    maxrate: string
    bufsize: string
    encoder: string
    preset: string
    audio: boolean
  }
  total_size_bytes: number
  ffmpeg_available: boolean
}
