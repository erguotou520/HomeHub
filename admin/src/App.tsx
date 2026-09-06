import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './state/auth'
import Login from './pages/Login'
import Dirs from './pages/Dirs'
import Tasks from './pages/Tasks'
import Peers from './pages/Peers'
import Album from './pages/Album'
import Search from './pages/Search'
import Trash from './pages/Trash'
import Monitor from './pages/Monitor'
import SystemPage from './pages/System'

const NAV = [
  { to: '/album', label: '相册' },
  { to: '/search', label: '全局搜索' },
  { to: '/dirs', label: '目录管理' },
  { to: '/tasks', label: '任务中心' },
  { to: '/peers', label: '身份审计' },
  { to: '/trash', label: '回收站' },
  { to: '/monitor', label: '监控' },
  { to: '/system', label: '系统信息' },
]

const TITLES: Record<string, string> = {
  '/album': '相册浏览',
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

  if (!ready) return <div className="spinner">加载中…</div>
  if (!token) return <Login />

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          Home<span>Hub</span>
        </div>
        {NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
          >
            {item.label}
          </NavLink>
        ))}
        <div className="sidebar-footer">
          <button className="ghost small" onClick={logout}>
            退出登录
          </button>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <h1>{TITLES[location.pathname] ?? 'HomeHub'}</h1>
        </header>
        <div className="content">
          <Routes>
            <Route path="/" element={<Navigate to="/album" replace />} />
            <Route path="/album" element={<Album />} />
            <Route path="/search" element={<Search />} />
            <Route path="/dirs" element={<Dirs />} />
            <Route path="/tasks" element={<Tasks />} />
            <Route path="/peers" element={<Peers />} />
            <Route path="/trash" element={<Trash />} />
            <Route path="/monitor" element={<Monitor />} />
            <Route path="/system" element={<SystemPage />} />
            <Route path="*" element={<Navigate to="/album" replace />} />
          </Routes>
        </div>
      </div>
    </div>
  )
}
