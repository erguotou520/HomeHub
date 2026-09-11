import { useEffect } from 'react'
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './state/auth'
import { useAutoLabelIds } from './hooks/useAutoLabelIds'
import Icon, { type IconName } from './components/Icon'
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

const NAV: { to: string; label: string; ico: IconName }[] = [
  { to: '/', label: '概览', ico: 'home' },
  { to: '/album', label: '相册', ico: 'image' },
  { to: '/files', label: '文件管理', ico: 'folder' },
  { to: '/search', label: '全局搜索', ico: 'search' },
  { to: '/dirs', label: '目录管理', ico: 'folderTree' },
  { to: '/tasks', label: '任务中心', ico: 'tasks' },
  { to: '/peers', label: '身份审计', ico: 'users' },
  { to: '/trash', label: '回收站', ico: 'trash' },
  { to: '/monitor', label: '监控', ico: 'monitor' },
  { to: '/system', label: '系统信息', ico: 'info' },
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
              <Icon name={item.ico} size={17} className="nav-ico" />
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
