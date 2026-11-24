import { useEffect, useRef, useState, useSyncExternalStore } from 'react'

const motionQuery = '(prefers-reduced-motion: reduce)'
const reduceMotion = () => typeof matchMedia === 'function' && matchMedia(motionQuery).matches
function subscribeMotion(listener: () => void) {
  if (typeof matchMedia !== 'function') return () => {}
  const query = matchMedia(motionQuery)
  query.addEventListener?.('change', listener)
  return () => query.removeEventListener?.('change', listener)
}

const observers = new Map<Element, (isVisible: boolean) => void>()
let intersectionObserver: IntersectionObserver | undefined

export function useVisibleMotion<T extends Element>() {
  const ref = useRef<T>(null)
  const reduced = useSyncExternalStore(subscribeMotion, reduceMotion, () => true)
  const [visible, setVisible] = useState(true)

  useEffect(() => {
    const element = ref.current
    if (!element || reduced || typeof IntersectionObserver !== 'function') return
    intersectionObserver ??= new IntersectionObserver(entries => {
      for (const entry of entries) observers.get(entry.target)?.(entry.isIntersecting)
    })
    observers.set(element, setVisible)
    intersectionObserver.observe(element)
    return () => {
      intersectionObserver?.unobserve(element)
      observers.delete(element)
      if (observers.size === 0) {
        intersectionObserver?.disconnect()
        intersectionObserver = undefined
      }
    }
  }, [reduced])

  return { ref, reduced, visible }
}
