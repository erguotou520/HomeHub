import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'

/**
 * Form fields are rendered as `<div class="field"><label>…</label><input/></div>`
 * throughout the app. The label is not bound with `htmlFor`, so screen readers
 * announce the input as unnamed and clicking the label does not focus it.
 *
 * Instead of touching every call site this hook pairs orphan labels with the
 * following control on every route change, which is where new fields appear.
 */
export function useAutoLabelIds(): void {
  const { pathname } = useLocation()

  useEffect(() => {
    let seq = 0
    const pair = () => {
      document.querySelectorAll<HTMLLabelElement>('label:not([for])').forEach((label) => {
        const host = label.closest('.field') ?? label.parentElement
        const control = (
          host?.querySelector<HTMLElement>('input, select, textarea') ??
          label.nextElementSibling
        ) as HTMLElement | null
        if (!control || control.tagName === 'LABEL') return
        if (control.id && label.htmlFor === control.id) return
        const id = control.id || `f-${pathname.replace(/\W/g, '') || 'root'}-${seq++}`
        control.id = id
        label.htmlFor = id
      })
    }

    pair()
    // Fields rendered after async data arrives get paired on the next frame.
    const raf = requestAnimationFrame(pair)
    const timer = window.setTimeout(pair, 400)
    return () => {
      cancelAnimationFrame(raf)
      window.clearTimeout(timer)
    }
  }, [pathname])
}
