import { useEffect, useState } from 'react'

export default function AnimatedBattery({ level }) {
  const [displayLevel, setDisplayLevel] = useState(level ?? 100)

  useEffect(() => {
    if (level == null) return
    const target = level
    // If we're already very close, just snap to it (e.g., normal tiny drains)
    if (Math.abs(displayLevel - target) < 0.1) {
      setDisplayLevel(target)
      return
    }

    const start = displayLevel
    const startTime = performance.now()
    // Make the animation duration a bit longer (e.g. 1 second) for a dramatic recharge effect
    const duration = 1000 
    let raf

    function step(now) {
      const t = Math.min(1, (now - startTime) / duration)
      // Ease out cubic
      const eased = 1 - Math.pow(1 - t, 3)
      setDisplayLevel(start + (target - start) * eased)
      
      if (t < 1) {
        raf = requestAnimationFrame(step)
      } else {
        setDisplayLevel(target)
      }
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [level])

  return <span className="bat-level">{displayLevel.toFixed(0)}%</span>
}
