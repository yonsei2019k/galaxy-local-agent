import type { ReactNode } from 'react'
import { useVisibleMotion } from './motion'

/** A wrapper whose decorative animations pause while it is off screen (and never run under reduced motion). */
export function MotionField({ className, children }: { className: string; children: ReactNode }) {
  const { ref, reduced, visible } = useVisibleMotion<HTMLDivElement>()
  return <div ref={ref} className={className} data-motion={reduced ? 'off' : 'on'} data-paused={!visible}>{children}</div>
}
