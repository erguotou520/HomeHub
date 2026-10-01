import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'

/**
 * 全局确认弹窗。
 *
 * 原来 6 处用的是原生 `confirm()`：同样是浏览器画的、样式与全站设计系统无关，
 * 而且它**同步阻塞**整个标签页 —— 弹窗开着时页面上的动画、轮询全部冻住。
 * 本文件把它换成 Promise 化的 `await confirm({...})`，读起来跟原来几乎一样：
 *
 *     if (!(await confirm({ title: '确定删除该目录登记吗？', danger: true }))) return
 *
 * 有两个刻意的选择：
 * · **危险动作默认聚焦「取消」**，不聚焦确认键 —— 回车不该是"同意销毁数据"。
 * · 上一个问题还没答完就被新问题顶掉时，旧的按「取消」结算，否则那个
 *   `await` 会永远吊着，调用方的后续代码再也不执行。
 */

export interface ConfirmOptions {
  title: string
  /** 补充说明，说清后果（比如"磁盘上的文件不会被删除"）。 */
  body?: ReactNode
  /** 默认「确定」。 */
  confirmText?: string
  /** 默认「取消」。 */
  cancelText?: string
  /** 不可逆动作：确认键用实心红（DESIGN.md 硬规则 3），并把初始焦点给「取消」。 */
  danger?: boolean
}

type ConfirmFn = (opts: ConfirmOptions) => Promise<boolean>

const ConfirmContext = createContext<ConfirmFn | null>(null)

interface Pending {
  opts: ConfirmOptions
  resolve: (value: boolean) => void
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null)
  // 同一份数据既在 state（渲染用）也在 ref（结算用）：结算发生在事件回调里，
  // 但要是从 state 里读，就得把 answer 变成依赖 pending 的函数，每次弹窗
  // 换一张 identity，Esc 的监听会跟着反复解绑重绑。
  const active = useRef<Pending | null>(null)
  const confirmBtn = useRef<HTMLButtonElement>(null)
  const cancelBtn = useRef<HTMLButtonElement>(null)
  const restoreTo = useRef<HTMLElement | null>(null)

  const confirm = useCallback<ConfirmFn>((opts) => {
    return new Promise<boolean>((resolve) => {
      active.current?.resolve(false)
      const item: Pending = { opts, resolve }
      active.current = item
      setPending(item)
    })
  }, [])

  const answer = useCallback((value: boolean) => {
    const item = active.current
    active.current = null
    setPending(null)
    item?.resolve(value)
  }, [])

  useEffect(() => {
    if (!pending) return
    restoreTo.current = document.activeElement as HTMLElement | null
    // 危险动作用户多半不是要确认，先把焦点放在出路（取消）上。
    const target = pending.opts.danger ? cancelBtn.current : confirmBtn.current
    target?.focus()

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        answer(false)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      restoreTo.current?.focus?.()
    }
  }, [pending, answer])

  const opts = pending?.opts

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {pending && opts && (
        <div className="modal-overlay" onClick={() => answer(false)}>
          <div
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="confirm-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 id="confirm-title">{opts.title}</h3>
            {opts.body && <p>{opts.body}</p>}
            <div className="modal-foot">
              <button ref={cancelBtn} className="ghost" onClick={() => answer(false)}>
                {opts.cancelText ?? '取消'}
              </button>
              <button
                ref={confirmBtn}
                className={opts.danger ? 'danger' : 'primary'}
                onClick={() => answer(true)}
              >
                {opts.confirmText ?? '确定'}
              </button>
            </div>
          </div>
        </div>
      )}
    </ConfirmContext.Provider>
  )
}

export function useConfirm(): ConfirmFn {
  const ctx = useContext(ConfirmContext)
  if (!ctx) throw new Error('useConfirm must be used inside ConfirmProvider')
  return ctx
}
