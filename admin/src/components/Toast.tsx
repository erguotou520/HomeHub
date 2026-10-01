import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import Icon from './Icon'

/**
 * 全局轻提示。
 *
 * 来历：保存设置、发送测试告警这类"做完了，说一声"的反馈，原来用的是浏览器
 * 原生的 `alert()` —— 它不属于本页文档流，样式改不了、层级由浏览器定、还会
 * 抢焦点阻塞整个标签页（弹出期间连切页签都可能被拦）。Files / PhotoViewer 里
 * 则各自用 useState + setTimeout 手搓了一份，没有错误态，也没有自动清理。
 *
 * 这里收敛成一份：底部居中堆叠、自动消失、错误留更久、可以手动关掉。
 *
 * 用色遵守 DESIGN.md 的「强调色 ≠ 状态色」：只有一枚状态圆点着色，胶囊本身
 * 仍是中性表面 —— 一屏里可能同时有提示和错误，整体铺色会吵。
 */

export type ToastTone = 'ok' | 'warn' | 'err' | 'info'

interface ToastItem {
  id: number
  tone: ToastTone
  text: string
}

interface ToastApi {
  /** 操作成功。 */
  ok: (text: string) => void
  /** 做成了，但有保留（部分失败、功能不支持）；不是失败，别用红。 */
  warn: (text: string) => void
  /** 操作失败，停留更久；屏幕阅读器会立即播报。 */
  err: (text: string) => void
  /** 中性说明 / 进度提示。 */
  info: (text: string) => void
  dismiss: (id: number) => void
}

/** 出错的要留够阅读时间，成功一闪而过即可。 */
const LIFETIME: Record<ToastTone, number> = { ok: 2600, warn: 4200, err: 5200, info: 3400 }

/** 再多就盖住内容了；超出的从最旧的开始丢。 */
const MAX_VISIBLE = 4

const ToastContext = createContext<ToastApi | null>(null)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const seq = useRef(0)
  const timers = useRef(new Map<number, number>())

  const dismiss = useCallback((id: number) => {
    const timer = timers.current.get(id)
    if (timer !== undefined) {
      window.clearTimeout(timer)
      timers.current.delete(id)
    }
    setItems((list) => list.filter((i) => i.id !== id))
  }, [])

  const push = useCallback(
    (text: string, tone: ToastTone) => {
      const id = (seq.current += 1)
      timers.current.set(
        id,
        window.setTimeout(() => dismiss(id), LIFETIME[tone]),
      )
      setItems((list) => {
        const next = [...list, { id, tone, text }]
        // 被挤掉的那些连同定时器一起清掉，别让它们留着空跑。
        for (const dropped of next.slice(0, Math.max(0, next.length - MAX_VISIBLE))) {
          const timer = timers.current.get(dropped.id)
          if (timer !== undefined) {
            window.clearTimeout(timer)
            timers.current.delete(dropped.id)
          }
        }
        return next.slice(-MAX_VISIBLE)
      })
    },
    [dismiss],
  )

  // 卸载时收音：StrictMode 下本组件会挂载两次，漏掉这里的清理会留下
  // 指着已卸载 state 的定时器。
  useEffect(() => {
    const pending = timers.current
    return () => {
      pending.forEach((timer) => window.clearTimeout(timer))
      pending.clear()
    }
  }, [])

  const api = useMemo<ToastApi>(
    () => ({
      ok: (text) => push(text, 'ok'),
      warn: (text) => push(text, 'warn'),
      err: (text) => push(text, 'err'),
      info: (text) => push(text, 'info'),
      dismiss,
    }),
    [push, dismiss],
  )

  return (
    <ToastContext.Provider value={api}>
      {children}
      {/*
        这个容器**始终挂在 DOM 里**，条目是后加进去的 —— aria-live 区域若与
        内容同时出现，多数读屏器不会播报。错误条目另带 role="alert"，
        好在出现时抢到注意力。
      */}
      <div className="toast-stack" aria-live="polite">
        {items.map((item) => (
          <div
            key={item.id}
            className={`toast-item ${item.tone}`}
            role={item.tone === 'err' ? 'alert' : 'status'}
          >
            <span className="toast-dot" aria-hidden="true" />
            <span className="toast-text">{item.text}</span>
            <button
              type="button"
              className="toast-close"
              onClick={() => dismiss(item.id)}
              aria-label="关闭提示"
            >
              <Icon name="x" size={12} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used inside ToastProvider')
  return ctx
}
