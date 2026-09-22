import type { CSSProperties } from 'react'

/**
 * HomeHub 统一线性图标库。
 *
 * 所有图标统一 24×24 视口、1.8px 圆头描边（stroke 风格，与导航一致），
 * 颜色继承 currentColor，尺寸由 `size` 控制。命名与语义一一对应，
 * 新增图标请保持同规格绘制，禁止混入 emoji 或第三方位图。
 */

const PATHS = {
  // ── 导航 ──
  home: 'M3 12l9-8 9 8M5 10v10a1 1 0 001 1h4v-6h4v6h4a1 1 0 001-1V10',
  image: 'M4 5h16v14H4zM4 15l4-4 3 3 4-5 5 6M8.5 8.5h.01',
  folder: 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z',
  folderTree: 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z M3 13h6',
  search: 'M11 4a7 7 0 100 14 7 7 0 000-14zM21 21l-5-5',
  tasks: 'M4 6h10M4 12h16M4 18h7M17 4v4M15 6h4',
  users: 'M12 3a5 5 0 100 10 5 5 0 000-10zM4 21c0-4 3.5-6 8-6s8 2 8 6M17 4a4 4 0 010 8',
  trash: 'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6',
  monitor: 'M2 8a2 2 0 012-2h12a2 2 0 012 2v8a2 2 0 01-2 2H4a2 2 0 01-2-2zM18 10l4-3v10l-4-3',
  info: 'M12 2a10 10 0 100 20 10 10 0 000-20zM12 8h.01M11 12h1v4h1',

  // ── 文件类型 ──
  file: 'M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8zM14 3v5h5M9 13h6M9 17h4',
  video: 'M3 6a2 2 0 012-2h9a2 2 0 012 2v12a2 2 0 01-2 2H5a2 2 0 01-2-2zM16 10l5-3v10l-5-3M7.5 12h.01',
  music: 'M9 18V5l11-2v13M9 18a2.5 2.5 0 11-5 0 2.5 2.5 0 015 0zM20 16a2.5 2.5 0 11-5 0 2.5 2.5 0 015 0z',

  // ── 操作 ──
  upload: 'M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 001 1h14a1 1 0 001-1v-3',
  download: 'M12 4v12M7 11l5 5 5-5M4 17v2a1 1 0 001 1h14a1 1 0 001-1v-2',
  edit: 'M11 4H5a2 2 0 00-2 2v13a2 2 0 002 2h13a2 2 0 002-2v-6M18.5 2.5a2.1 2.1 0 013 3L12 15l-4 1 1-4z',
  copy: 'M9 9h10a1 1 0 011 1v10a1 1 0 01-1 1H9a1 1 0 01-1-1V10a1 1 0 011-1zM5 15H4a1 1 0 01-1-1V4a1 1 0 011-1h10a1 1 0 011 1v1',
  move: 'M5 12h13M13 6l6 6-6 6M3 4v16',
  x: 'M6 6l12 12M18 6L6 18',
  check: 'M4 12l5 5L20 7',
  retry: 'M21 12a9 9 0 11-2.6-6.4M21 3v6h-6',
  plus: 'M12 5v14M5 12h14',
  play: 'M7 5l12 7-12 7z',
  pause: 'M8 5v14M16 5v14',
  skip: 'M5 5l9 7-9 7zM18 5v14',
  chevronUp: 'M6 14l6-6 6 6',
  chevronDown: 'M6 10l6 6 6-6',
  chevronRight: 'M9 6l6 6-6 6',
  grid: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  list: 'M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01',
  sortAsc: 'M6 20V6M2 10l4-4 4 4M13 8h8M13 14h6M13 20h4',
  sortDesc: 'M6 4v14M2 14l4 4 4-4M13 4h4M13 10h6M13 16h8',
  clock: 'M12 3a9 9 0 100 18 9 9 0 000-18zM12 7v5l3 3',
  alert: 'M12 3l10 17H2zM12 10v4M12 17.5h.01',
  link: 'M10 14a5 5 0 007.07 0l2.12-2.12a5 5 0 00-7.07-7.07L10.9 6.02M14 10a5 5 0 00-7.07 0L4.8 12.12a5 5 0 007.07 7.07L13.1 17.98',
  mapPin: 'M12 21s-7-6.1-7-11a7 7 0 0114 0c0 4.9-7 11-7 11zM12 10a2 2 0 100-4 2 2 0 000 4z',
  tag: 'M4 4h7l9 9-7 7-9-9zM8.5 8.5h.01',
  layers: 'M12 3l9 5-9 5-9-5zM3 13l9 5 9-5',
  filmstrip: 'M4 5h16v14H4zM7 5v14M17 5v14M4 9.5h3M4 14.5h3M17 9.5h3M17 14.5h3',
  fullscreen: 'M4 9V5a1 1 0 011-1h4M15 4h4a1 1 0 011 1v4M20 15v4a1 1 0 01-1 1h-4M9 20H5a1 1 0 01-1-1v-4',
  rotateLeft: 'M4 9a8 8 0 108-8M4 9V3M4 9h6',
  rotateRight: 'M20 9A8 8 0 105 3.5M20 9V3M20 9h-6',
  flipH: 'M12 3v18M7 8l-5 4 5 4V8zM17 8l5 4-5 4V8z',
  flipV: 'M3 12h18M8 7l4-5 4 5H8zM8 17l4 5 4-5H8z',
  resize: 'M4 20v-6M4 20h6M20 4v6M20 4h-6M4 20L10 14M20 4l-6 6',
  zoomIn: 'M11 4a7 7 0 100 14 7 7 0 000-14zM21 21l-5-5M8 11h6M11 8v6',
  zoomOut: 'M11 4a7 7 0 100 14 7 7 0 000-14zM21 21l-5-5M8 11h6',
  logout: 'M10 4H6a2 2 0 00-2 2v12a2 2 0 002 2h4M17 8l4 4-4 4M9 12h12',

  // ── 品牌 ──
  // 层叠卡片：HomeHub 是「把散落的东西收拢成一层层归档」的意思。
  brand: 'M12 3l8.5 4.6-8.5 4.6L3.5 7.6zM3.5 12.3l8.5 4.6 8.5-4.6M3.5 16.7l8.5 4.6 8.5-4.6',
} as const

export type IconName = keyof typeof PATHS

interface IconProps {
  name: IconName
  /** Pixel size (width & height); defaults to 16. */
  size?: number
  /** stroke width override; defaults to 1.8 */
  strokeWidth?: number
  className?: string
  style?: CSSProperties
}

export default function Icon({ name, size = 16, strokeWidth = 1.8, className, style }: IconProps) {
  return (
    <svg
      className={className}
      style={style}
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      focusable="false"
    >
      <path d={PATHS[name]} />
    </svg>
  )
}
