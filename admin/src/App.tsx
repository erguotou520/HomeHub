import { useEffect } from 'react'
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './state/auth'
import { useAutoLabelIds } from './hooks/useAutoLabelIds'
import Login from './pages/Login'
import Home from './pages/Home'
import Dirs from './pages/Dirs'
import Files from './pages/Files'
import Tasks from './pages/Tasks'
import Peers from './pages/Peers'
import Album from './pages/Album'
import Search from './pages/Search'
import Trash from './pages/Trash'
import Monitor from './pages/Monitor'
import SystemPage from './pages/System'

function Ico({ d }: { d: string }) {
  return (
    <svg
      className="nav-ico"
      viewBox="0 0 24 24"
      width="17"
      height="17"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d={d} />
    </svg>
  )
}

const NAV = [
  { to: '/', label: '概览', ico: 'M3 12l9-8 9 8M5 10v10a1 1 0 001 1h4v-6h4v6h4a1 1 0 001-1V10' },
  { to: '/album', label: '相册', ico: 'M4 5h16v14H4zM4 15l4-4 3 3 4-5 5 6M8.5 8.5h.01' },
  { to: '/files', label: '文件管理', ico: 'M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8zM14 3v5h5M9 13h6M9 17h4' },
  { to: '/search', label: '全局搜索', ico: 'M11 4a7 7 0 100 14 7 7 0 000-14zM21 21l-5-5' },
  { to: '/dirs', label: '目录管理', ico: 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z' },
  { to: '/tasks', label: '任务中心', ico: 'M4 6h16M4 12h16M4 18h10' },
  { to: '/peers', label: '身份审计', ico: 'M12 3a5 5 0 100 10 5 5 0 000-10zM4 21c0-4 3.5-6 8-6s8 2 8 6' },
  { to: '/trash', label: '回收站', ico: 'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6' },
  { to: '/monitor', label: '监控', ico: 'M2 8a2 2 0 012-2h12a2 2 0 012 2v8a2 2 0 01-2 2H4a2 2 0 01-2-2zM18 10l4-3v10l-4-3' },
  { to: '/system', label: '系统信息', ico: 'M12 2a10 10 0 100 20 10 10 0 000-20zM12 8h.01M11 12h1v4h1' },
]

const TITLES: Record<string, string> = {
  '/': '概览',
  '/album': '相册浏览',
  '/files': '文件管理',
  '/search': '全局搜索',
  '/dirs': '目录管理',
  '/tasks': '任务中心',
  '/peers': '身份审计',
  '/trash': '回收站',
  '/monitor': '监控',
  '/system': '系统信息',
}

export default function App() {
  const { token, ready, logout } = useAuth()
  const location = useLocation()
  const title = TITLES[location.pathname] ?? 'HomeHub'

  useAutoLabelIds()

  useEffect(() => {
    document.title = `${title} · HomeHub`
  }, [title])

  if (!ready) return <div className="spinner">加载中…</div>
  if (!token) return <Login />

  return (
    <div className="app">
      <a className="skip-link" href="#main">
        跳到主要内容
      </a>
      <aside className="sidebar">
        <div className="brand">
          Home<span>Hub</span>
          <div className="brand-sub">家庭数据中心</div>
        </div>
        <nav className="nav">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
            >
              <Ico d={item.ico} />
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer">
          <button className="ghost small" onClick={logout}>
            退出登录
          </button>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <h1 id="page-title">{title}</h1>
        </header>
        <main className="content" id="main" tabIndex={-1} aria-labelledby="page-title">
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/album" element={<Album />} />
            <Route path="/files" element={<Files />} />
            <Route path="/search" element={<Search />} />
            <Route path="/dirs" element={<Dirs />} />
            <Route path="/tasks" element={<Tasks />} />
            <Route path="/peers" element={<Peers />} />
            <Route path="/trash" element={<Trash />} />
            <Route path="/monitor" element={<Monitor />} />
            <Route path="/system" element={<SystemPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
      </div>
    </div>
  )
}
