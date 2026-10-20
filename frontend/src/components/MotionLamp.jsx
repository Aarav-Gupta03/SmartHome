import { useState } from 'react'
import AnimatedBattery from './AnimatedBattery'

export default function MotionLamp({ motionId, label, reading, onTrigger, onSetPower, onSetBattery, isRateLimited, quarantineCount, onRelease }) {
  const [busy, setBusy] = useState(false)
  const [triggerError, setTriggerError] = useState(null)
  const detected = reading?.motionDetected ?? false

  const batteryLevel = reading?.batteryLevel ?? 100
  const isSilent = reading?.timestamp && (Date.now() - new Date(reading.timestamp).getTime() > 15000)
  const isOffline = batteryLevel < 10 || isSilent
  const isBlocked = isRateLimited || isOffline

  async function trigger(value) {
    setBusy(true)
    setTriggerError(null)
    try {
      await onTrigger(motionId, value)
    } catch (err) {
      // Surface the error to the user — e.g. 429 rate-limited, or network error.
      setTriggerError(err?.message || 'Trigger failed')
      setTimeout(() => setTriggerError(null), 4000)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={`module lamp-module ${isOffline ? 'module-offline' : ''} ${isRateLimited ? 'module-rate-limited' : ''}`}>
      <div className="module-label-row">
        <span className="module-label">{label}</span>
        <div className="module-icons">
          {isRateLimited && <span className="rate-limited-badge">⚡ RATE LIMITED</span>}
          {isOffline && !isRateLimited && <span className="offline-icon" title="Device Offline">⚠️</span>}
          {quarantineCount > 0 && <span className="quarantine-stats-pill" title="Times quarantined">🔒×{quarantineCount}</span>}
        </div>
      </div>
      <div className={`indicator ${detected && !isRateLimited ? 'indicator-lit' : ''}`} />
      <span className="switch-state">
        {isRateLimited ? 'QUARANTINED' : isOffline ? 'OFFLINE' : (detected ? 'MOTION' : 'CLEAR')}
      </span>
      <div className="lamp-buttons">
        <button className="btn btn-ghost" disabled={busy || isBlocked} onClick={() => trigger(true)}>Trigger</button>
        <button className="btn btn-ghost" disabled={busy || isBlocked} onClick={() => trigger(false)}>Clear</button>
      </div>

      {triggerError && (
        <span className="trigger-error" style={{ color: 'var(--amber)', fontSize: '0.72rem', marginTop: '4px', display: 'block' }}>
          ⚠ {triggerError}
        </span>
      )}

      {isRateLimited && (
        <button
          className="btn btn-release"
          onClick={() => onRelease(motionId)}
          title="This sensor was quarantined for writing too fast. Click to restore."
        >
          ✓ Release Sensor
        </button>
      )}

      <div className="debug-controls">
        <button onClick={() => onSetPower(motionId, isSilent)}>{isSilent ? '🔌 Restore Power' : '⚡ Kill Power'}</button>
        <button onClick={() => onSetBattery(motionId, 5)}>🔋 Drain to 5%</button>
        <button onClick={() => onSetBattery(motionId, 100)}>🔌 Recharge</button>
        <AnimatedBattery level={batteryLevel} />
      </div>
    </div>
  )
}
