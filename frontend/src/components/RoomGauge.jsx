import { useEffect, useState } from 'react'
import AnimatedBattery from './AnimatedBattery'

const MIN_T = 60
const MAX_T = 160

function angleFor(temp) {
  const clamped = Math.max(MIN_T, Math.min(MAX_T, temp))
  const fraction = (clamped - MIN_T) / (MAX_T - MIN_T)
  return -90 + fraction * 180
}

function needlePoint(angleDeg, length, cx, cy) {
  const rad = (angleDeg * Math.PI) / 180
  return { x: cx + length * Math.sin(rad), y: cy - length * Math.cos(rad) }
}

export default function RoomGauge({ roomId, label, reading, onGenerate, onSimulateFire, autoEnabled, onToggleAuto, onSetPower, onSetBattery, isRateLimited, quarantineCount, onRelease }) {
  const [customTemp, setCustomTemp] = useState('')
  const [displayTemp, setDisplayTemp] = useState(reading?.temperature ?? 70)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (reading?.temperature == null) return
    const target = reading.temperature
    const start = displayTemp
    const startTime = performance.now()
    const duration = 500

    let raf
    function step(now) {
      const t = Math.min(1, (now - startTime) / duration)
      const eased = 1 - Math.pow(1 - t, 3)
      setDisplayTemp(start + (target - start) * eased)
      if (t < 1) raf = requestAnimationFrame(step)
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reading?.temperature, reading?.timestamp])

  const isFire = displayTemp > 100
  const cx = 90, cy = 92, radius = 68
  const needle = needlePoint(angleFor(displayTemp), radius - 14, cx, cy)

  async function handleGenerate() {
    setBusy(true)
    try {
      const t = customTemp.trim() !== '' ? parseFloat(customTemp) : null
      await onGenerate(roomId, t)
      setCustomTemp('')
    } finally {
      setBusy(false)
    }
  }

  async function handleFire() {
    setBusy(true)
    try {
      await onSimulateFire(roomId)
    } finally {
      setBusy(false)
    }
  }

  const batteryLevel = reading?.batteryLevel ?? 100
  const isSilent = reading?.timestamp && (Date.now() - new Date(reading.timestamp).getTime() > 15000)
  const isOffline = batteryLevel < 10 || isSilent
  const isBlocked = isRateLimited || isOffline

  return (
    <div className={`module gauge-module ${isFire ? 'module-alarm' : ''} ${isOffline ? 'module-offline' : ''} ${isRateLimited ? 'module-rate-limited' : ''}`}>
      <div className="module-label-row">
        <span className="module-label">{label}</span>
        <div className="module-icons">
          {isRateLimited && <span className="rate-limited-badge">⚡ RATE LIMITED</span>}
          {isOffline && !isRateLimited && <span className="offline-icon" title="Device Offline">⚠️</span>}
          {quarantineCount > 0 && <span className="quarantine-stats-pill" title="Times quarantined">🔒×{quarantineCount}</span>}
          <span className={`lamp ${autoEnabled ? 'lamp-on' : ''}`} title="Auto-mode participation"
            onClick={() => onToggleAuto(roomId, !autoEnabled)} />
        </div>
      </div>

      <svg viewBox="0 0 180 110" className="dial">
        <path d={`M 22 92 A 68 68 0 0 1 158 92`} className="dial-track" />
        <path d={`M 22 92 A 68 68 0 0 1 158 92`} className="dial-track-safe" pathLength="100"
          strokeDasharray={`${((Math.max(MIN_T, Math.min(MAX_T, displayTemp)) - MIN_T) / (MAX_T - MIN_T)) * 100} 100`} />
        <line x1={cx} y1={cy} x2={needle.x} y2={needle.y} className="dial-needle" />
        <circle cx={cx} cy={cy} r="4.5" className="dial-hub" />
      </svg>

      <div className="readout">
        {isRateLimited
          ? <span className="readout-value rate-limited-readout">QUARANTINED</span>
          : <>
              <span className="readout-value">{isOffline ? '--' : displayTemp.toFixed(1)}</span>
              <span className="readout-unit">°F</span>
            </>
        }
      </div>

      <div className="module-controls">
        <input
          type="number"
          placeholder="set °F"
          value={customTemp}
          onChange={(e) => setCustomTemp(e.target.value)}
          className="temp-input"
          disabled={isBlocked}
        />
        <button className="btn btn-primary" disabled={busy || isBlocked} onClick={handleGenerate}>
          Send reading
        </button>
      </div>
      <button className="btn btn-danger btn-fire" disabled={busy || isOffline} onClick={handleFire}>
        Simulate fire
      </button>

      {isRateLimited && (
        <button
          className="btn btn-release"
          onClick={() => onRelease(roomId)}
          title="This sensor was quarantined for writing too fast. Click to restore."
        >
          ✓ Release Sensor
        </button>
      )}
      
      <div className="debug-controls">
        <button onClick={() => onSetPower(roomId, isSilent)}>{isSilent ? '🔌 Restore Power' : '⚡ Kill Power'}</button>
        <button onClick={() => onSetBattery(roomId, 5)}>🔋 Drain to 5%</button>
        <button onClick={() => onSetBattery(roomId, 100)}>🔌 Recharge</button>
        <AnimatedBattery level={batteryLevel} />
      </div>
    </div>
  )
}

