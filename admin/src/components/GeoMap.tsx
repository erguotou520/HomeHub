import { useEffect, useRef, useState } from 'react'
import L from 'leaflet'
import type { GeoPoint } from '../api/types'

/**
 * AMap (高德) raster tile endpoints.
 *
 * Photo GPS comes from EXIF in WGS-84 while AMap tiles are drawn in GCJ-02,
 * so every coordinate is converted before it is handed to Leaflet — otherwise
 * markers drift a few hundred metres away from the roads they were taken on.
 */
const TILE_STYLES: Record<string, { label: string; url: string }> = {
  road: {
    label: '路网',
    url: 'https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
  },
  satellite: {
    label: '影像',
    url: 'https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',
  },
}

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
  ret += ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300.0 * Math.sin((x / 30.0) * Math.PI)) * 2.0) / 3.0
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

interface GeoMapProps {
  points: GeoPoint[]
  onSelect: (point: GeoPoint) => void
}

export default function GeoMap({ points, onSelect }: GeoMapProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<L.Map | null>(null)
  const layerRef = useRef<L.LayerGroup | null>(null)
  const [style, setStyle] = useState<'road' | 'satellite'>('road')

  // Create the map once.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return
    const map = L.map(containerRef.current, { zoomControl: true }).setView([35.0, 105.0], 4)
    mapRef.current = map
    layerRef.current = L.layerGroup().addTo(map)
    return () => {
      map.remove()
      mapRef.current = null
      layerRef.current = null
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

  // Redraw the aggregated markers.
  useEffect(() => {
    const map = mapRef.current
    const layer = layerRef.current
    if (!map || !layer) return
    layer.clearLayers()

    const max = points.reduce((m, p) => Math.max(m, p.count), 1)
    const bounds = L.latLngBounds([])
    for (const point of points) {
      const [lat, lng] = wgs84ToGcj02(point.lat, point.lng)
      const radius = 10 + 14 * Math.sqrt(point.count / max)
      const marker = L.circleMarker([lat, lng], {
        radius,
        color: '#4f8cff',
        weight: 2,
        fillColor: '#4f8cff',
        fillOpacity: 0.35,
      })
      marker.bindTooltip(
        `${point.lat.toFixed(4)}, ${point.lng.toFixed(4)}<br/>${point.count} 张`,
        { direction: 'top' },
      )
      marker.on('click', () => onSelect(point))
      marker.addTo(layer)
      bounds.extend([lat, lng])
    }
    if (points.length > 0) {
      map.fitBounds(bounds, { padding: [40, 40], maxZoom: 13 })
    }
  }, [points, onSelect])

  return (
    <div>
      <div className="row" style={{ alignItems: 'center', marginBottom: 8 }}>
        {Object.entries(TILE_STYLES).map(([key, value]) => (
          <button
            key={key}
            className={style === key ? 'small' : 'small ghost'}
            onClick={() => setStyle(key as 'road' | 'satellite')}
          >
            {value.label}
          </button>
        ))}
        <span className="muted" style={{ fontSize: 12 }}>
          点击圆点查看该地点的照片（共 {points.length} 个地点）
        </span>
      </div>
      <div className="map" ref={containerRef} />
    </div>
  )
}
