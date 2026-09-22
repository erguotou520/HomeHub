import { useCallback, useEffect, useRef, useState } from 'react'
import L from 'leaflet'
import type { GeoPoint } from '../api/types'

/**
 * AMap (高德) raster tile endpoints.
 *
 * Photo GPS comes from EXIF in WGS-84 while AMap tiles are drawn in GCJ-02,
 * so every coordinate is converted before it is handed to Leaflet — otherwise
 * markers drift a few hundred metres away from the roads they were taken on.
 */
const TILE_STYLES = {
  road: {
    label: '路网',
    url: 'https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
  },
  satellite: {
    label: '影像',
    url: 'https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',
  },
} as const

type TileStyle = keyof typeof TILE_STYLES

/** Two markers closer than this *on screen* collapse into one bubble. */
const CLUSTER_RADIUS_PX = 56
/** Bubble diameter range; the count scales a bubble between these bounds. */
const BUBBLE_MIN_PX = 28
const BUBBLE_MAX_PX = 54
/** Mirrors the `ids` filter cap on the list endpoint, so a bubble click can always reveal every photo it claims. */
const MAX_IDS_PER_BUBBLE = 1000

const GCJ_A = 6378245.0
const GCJ_EE = 0.00669342162296594323

function outOfChina(lat: number, lng: number): boolean {
  return !(lng > 72.004 && lng < 137.8347 && lat > 0.8293 && lat < 55.8271)
}

function transformLat(x: number, y: number): number {
  let ret =
    -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0
  ret += ((20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin((y / 3.0) * Math.PI)) * 2.0) / 3.0
  ret += ((160.0 * Math.sin((y / 12.0) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30.0)) * 2.0) / 3.0
  return ret
}

function transformLng(x: number, y: number): number {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0
  ret += ((20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin((x / 3.0) * Math.PI)) * 2.0) / 3.0
  ret += ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300 * Math.sin((x / 30.0) * Math.PI)) * 2.0) / 3.0
  return ret
}

/** WGS-84 -> GCJ-02 (the "火星坐标" offset used by Chinese map providers). */
export function wgs84ToGcj02(lat: number, lng: number): [number, number] {
  if (outOfChina(lat, lng)) return [lat, lng]
  let dLat = transformLat(lng - 105.0, lat - 35.0)
  let dLng = transformLng(lng - 105.0, lat - 35.0)
  const radLat = (lat / 180.0) * Math.PI
  let magic = Math.sin(radLat)
  magic = 1 - GCJ_EE * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  dLat = (dLat * 180.0) / (((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic)) * Math.PI)
  dLng = (dLng * 180.0) / ((GCJ_A / sqrtMagic) * Math.cos(radLat) * Math.PI)
  return [lat + dLat, lng + dLng]
}

/**
 * Map zoom -> server aggregation grid, in degrees.
 *
 * The grid is deliberately finer than one on-screen bubble (roughly 14 px of
 * ground distance at this zoom), so the server only trims the payload while
 * the client owns the visual clustering. Zooming in therefore both fetches a
 * finer grid *and* spreads the remaining points apart, so bubbles keep
 * splitting instead of jumping. Floored at ~22 m, below which GPS noise
 * dominates.
 */
export function precisionForZoom(zoom: number): number {
  const deg = 16.1 / 2 ** zoom
  return Math.max(0.0002, Number(deg.toPrecision(2)))
}

/** Compact Chinese count for bubble labels: 12845 -> "1.3万". */
function formatCount(n: number): string {
  if (n < 10000) return String(n)
  return `${(n / 10000).toFixed(1).replace(/\.0$/, '')}万`
}

/**
 * Bubble diameter for a photo count.
 *
 * Deliberately absolute rather than relative to the largest cluster on screen:
 * with a relative scale a lone photo would balloon to the maximum size whenever
 * every visible marker happens to be a single, which makes a dense street of
 * "1"s overlap. Log-scaled so 1000 photos is 2x the diameter of one, not 1000x
 * the area, and capping at 1000 keeps "1.3万" labels inside their bubble.
 */
function bubbleSize(count: number): number {
  const t = Math.min(1, Math.log10(Math.max(1, count)) / 3)
  return Math.round(BUBBLE_MIN_PX + (BUBBLE_MAX_PX - BUBBLE_MIN_PX) * t)
}

interface Cluster {
  /** GCJ-02 position used for drawing. */
  lat: number
  lng: number
  /** WGS-84, count-weighted centroid handed back to the caller on click. */
  src: { lat: number; lng: number }
  count: number
  photo_ids: number[]
  thumb_url?: string | null
}

/**
 * Fold points that would overlap on screen into single clusters.
 *
 * Distances are measured in projected pixel space at the current zoom, so the
 * merge radius is a *visual* one: two photos 2 km apart sit in one bubble while
 * zoomed out and separate once they are more than {@link CLUSTER_RADIUS_PX}
 * pixels apart. Points are visited largest-first so a bubble anchors on the
 * busiest spot, and the centroid keeps the merged bubble over its members.
 */
function clusterPoints(
  project: (latlng: L.LatLngExpression) => L.Point,
  points: GeoPoint[],
): Cluster[] {
  const cell = CLUSTER_RADIUS_PX
  const buckets = new Map<string, Cluster[]>()
  const out: Cluster[] = []
  const bucketKey = (x: number, y: number) => `${x}:${y}`
  const ordered = [...points].sort((a, b) => b.count - a.count)

  for (const point of ordered) {
    const [lat, lng] = wgs84ToGcj02(point.lat, point.lng)
    const px = project([lat, lng])
    const cx = Math.floor(px.x / cell)
    const cy = Math.floor(px.y / cell)

    let hit: Cluster | undefined
    for (let dx = -1; dx <= 1 && !hit; dx++) {
      for (let dy = -1; dy <= 1 && !hit; dy++) {
        const neighbours = buckets.get(bucketKey(cx + dx, cy + dy))
        if (!neighbours) continue
        for (const candidate of neighbours) {
          if (project([candidate.lat, candidate.lng]).distanceTo(px) <= cell) {
            hit = candidate
            break
          }
        }
      }
    }

    if (!hit) {
      hit = {
        lat,
        lng,
        src: { lat: point.lat, lng: point.lng },
        count: point.count,
        photo_ids: point.photo_ids.slice(0, MAX_IDS_PER_BUBBLE),
        thumb_url: point.thumb_url ?? null,
      }
      out.push(hit)
      const bucket = buckets.get(bucketKey(cx, cy))
      if (bucket) bucket.push(hit)
      else buckets.set(bucketKey(cx, cy), [hit])
      continue
    }

    // Count-weighted centroid: the bubble stays over the densest part.
    const total = hit.count + point.count
    hit.lat = (hit.lat * hit.count + lat * point.count) / total
    hit.lng = (hit.lng * hit.count + lng * point.count) / total
    hit.src.lat = (hit.src.lat * hit.count + point.lat * point.count) / total
    hit.src.lng = (hit.src.lng * hit.count + point.lng * point.count) / total
    hit.count = total
    const room = MAX_IDS_PER_BUBBLE - hit.photo_ids.length
    if (room > 0) hit.photo_ids.push(...point.photo_ids.slice(0, room))
  }

  return out
}

/**
 * Div icon carrying a real <button>, so bubbles are keyboard reachable for free.
 *
 * There is no separate "single photo" marker: a lone photo is just a cluster of
 * one and reads as a small bubble labelled "1". One marker shape means the map
 * needs no legend — the number always means the same thing.
 */
function markerIcon(count: number): L.DivIcon {
  const size = bubbleSize(count)
  const hint = count === 1 ? '1 张照片' : `${count} 张照片的地点`
  return L.divIcon({
    className: 'geo-marker',
    html:
      `<button type="button" class="geo-hit geo-bubble" style="--d:${size}px" ` +
      `title="${hint}，点击查看" aria-label="${hint}，点击查看">` +
      `<span class="geo-bubble-n">${formatCount(count)}</span></button>`,
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  })
}

interface GeoMapProps {
  points: GeoPoint[]
  /** Current server aggregation grid; the parent refetches when it changes. */
  precision: number
  /** The parent is re-aggregating for a new zoom level. */
  loading?: boolean
  onSelect: (point: GeoPoint) => void
  onPrecisionChange: (precision: number) => void
}

export default function GeoMap({
  points,
  precision,
  loading = false,
  onSelect,
  onPrecisionChange,
}: GeoMapProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<L.Map | null>(null)
  const layerRef = useRef<L.LayerGroup | null>(null)
  /** Fit bounds only on the very first draw — never fight the user's zoom. */
  const didFitRef = useRef(false)
  /** Bounds of the last draw, so "重置视野" can re-frame everything. */
  const boundsRef = useRef<L.LatLngBounds | null>(null)
  /** Latest values for the zoom handler, so it never needs re-binding. */
  const precisionRef = useRef(precision)
  const onPrecisionChangeRef = useRef(onPrecisionChange)
  /** Latest click handler, so markers are not rebuilt when the parent re-renders. */
  const onSelectRef = useRef(onSelect)
  const [style, setStyle] = useState<TileStyle>('road')
  const [zoom, setZoom] = useState(4)
  /** Visible bubble count + how many photos they cover, for the status line. */
  const [stats, setStats] = useState({ bubbles: 0, photos: 0 })

  useEffect(() => {
    precisionRef.current = precision
  }, [precision])
  useEffect(() => {
    onPrecisionChangeRef.current = onPrecisionChange
  }, [onPrecisionChange])
  useEffect(() => {
    onSelectRef.current = onSelect
  }, [onSelect])

  // Create the map once.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return
    // Anchor/zoom animations are the only motion here; skip them on request.
    const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    const map = L.map(containerRef.current, {
      zoomControl: true,
      minZoom: 2,
      maxZoom: 18,
      worldCopyJump: true,
      fadeAnimation: !reduceMotion,
      zoomAnimation: !reduceMotion,
      markerZoomAnimation: !reduceMotion,
    }).setView([35.0, 105.0], 4)
    mapRef.current = map
    layerRef.current = L.layerGroup().addTo(map)
    // Crossing a precision step while zooming asks the parent to re-aggregate.
    // Panning alone never re-clusters: pixel distances are pan-invariant.
    const syncView = () => {
      const nextZoom = map.getZoom()
      setZoom(nextZoom)
      const next = precisionForZoom(nextZoom)
      if (next !== precisionRef.current) onPrecisionChangeRef.current(next)
    }
    map.on('zoomend', syncView)
    return () => {
      map.remove()
      mapRef.current = null
      layerRef.current = null
      didFitRef.current = false
      boundsRef.current = null
    }
  }, [])

  // Swap the AMap tile layer when the style changes.
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    const tiles = L.tileLayer(TILE_STYLES[style].url, {
      subdomains: ['1', '2', '3', '4'],
      maxZoom: 18,
      minZoom: 2,
      attribution: '&copy; 高德地图',
    })
    tiles.addTo(map)
    return () => {
      tiles.remove()
    }
  }, [style])

  // Redraw: cluster the sample points by screen distance for the current zoom.
  useEffect(() => {
    const map = mapRef.current
    const layer = layerRef.current
    if (!map || !layer) return
    layer.clearLayers()

    if (points.length === 0) {
      boundsRef.current = null
      setStats({ bubbles: 0, photos: 0 })
      return
    }

    const project = (latlng: L.LatLngExpression) => map.project(latlng, zoom)
    const clusters = clusterPoints(project, points)
    const bounds = L.latLngBounds([])

    for (const cluster of clusters) {
      const marker = L.marker([cluster.lat, cluster.lng], {
        icon: markerIcon(cluster.count),
        // The inner <button> is the focus target; keep Leaflet from adding a second one.
        keyboard: false,
        riseOnHover: true,
      })
      marker.on('click', () =>
        onSelectRef.current({
          lat: cluster.src.lat,
          lng: cluster.src.lng,
          count: cluster.count,
          photo_ids: cluster.photo_ids,
          thumb_url: cluster.thumb_url,
        }),
      )
      marker.addTo(layer)
      bounds.extend([cluster.lat, cluster.lng])
    }

    boundsRef.current = bounds.isValid() ? bounds : null
    setStats({ bubbles: clusters.length, photos: points.reduce((a, p) => a + p.count, 0) })

    if (!didFitRef.current) {
      didFitRef.current = true
      map.fitBounds(bounds, { padding: [48, 48], maxZoom: 15 })
    }
  }, [points, zoom])

  /** Re-frame every marker (and let the auto-expand steps take over again). */
  const fitAll = useCallback(() => {
    const map = mapRef.current
    const bounds = boundsRef.current
    if (!map || !bounds) return
    didFitRef.current = true
    map.fitBounds(bounds, { padding: [48, 48], maxZoom: 15 })
  }, [])

  return (
    <div>
      <div className="geo-bar">
        <span className="seg" role="group" aria-label="底图样式">
          {(Object.keys(TILE_STYLES) as TileStyle[]).map((key) => (
            <button
              key={key}
              type="button"
              className={`seg-btn${style === key ? ' on' : ''}`}
              aria-pressed={style === key}
              onClick={() => setStyle(key)}
            >
              {TILE_STYLES[key].label}
            </button>
          ))}
        </span>
        <button
          type="button"
          className="small ghost"
          onClick={fitAll}
          disabled={stats.bubbles === 0}
        >
          重置视野
        </button>
        <span className="geo-meta" aria-live="polite">
          {loading ? '聚合中…' : `${stats.bubbles} 个地点 · 共 ${stats.photos} 张照片`}
          <span className="muted"> · 缩放地图自动展开</span>
        </span>
      </div>
      <div className="geo-map">
        {/* Leaflet makes the container focusable; a named region keeps that
            focus stop meaningful instead of an unlabelled div. */}
        <div
          className="map"
          ref={containerRef}
          role="region"
          aria-label="照片地点分布地图"
        />
      </div>
    </div>
  )
}
