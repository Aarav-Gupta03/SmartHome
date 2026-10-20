import { useState } from 'react'
import AnimatedBattery from './AnimatedBattery'

export default function LockSwitch({ lockId, label, reading, onToggle, justLockedByAlarm, onSetPower, onSetBattery, isRateLimited, quarantineCount, onRelease }) {
  const [busy, setBusy] = useState(false)
  const locked = reading?.locked ?? true

  const batteryLevel = reading?.batteryLevel ?? 100
  const isSilent = reading?.timestamp && (Date.now() - new Date(reading.timestamp).getTime() > 15000)
  const isOffline = batteryLevel < 10 || isSilent
  const isBlocked = isRateLimited || isOffline

  async function flip() {
    setBusy(true)
    try {
      await onToggle(lockId, !locked)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={`module switch-module ${(justLockedByAlarm === 'all' || justLockedByAlarm === lockId) ? 'module-alarm-flash' : ''} ${isOffline ? 'module-offline' : ''} ${isRateLimited ? 'module-rate-limited' : ''}`}>
      <div className="module-label-row">
        <span className="module-label">{label}</span>
        <div className="module-icons">
          {isRateLimited && <span className="rate-limited-badge">⚡ RATE LIMITED</span>}
          {isOffline && !isRateLimited && <span className="offline-icon" title="Device Offline">⚠️</span>}
          {quarantineCount > 0 && <span className="quarantine-stats-pill" title="Times quarantined">🔒×{quarantineCount}</span>}
        </div>
      </div>
      <button
        className={`rocker ${locked ? 'rocker-locked' : 'rocker-unlocked'}`}
        onClick={flip}
        disabled={busy || isBlocked}
        aria-pressed={locked}
      >
        <span className="rocker-thumb" />
      </button>
      <span className="switch-state">
        {isRateLimited ? 'QUARANTINED' : isOffline ? 'OFFLINE' : (locked ? 'LOCKED' : 'UNLOCKED')}
      </span>

      {isRateLimited && (
        <button
          className="btn btn-release"
          onClick={() => onRelease(lockId)}
          title="This sensor was quarantined for writing too fast. Click to restore."
        >
          ✓ Release Sensor
        </button>
      )}

      <div className="debug-controls">
        <button onClick={() => onSetPower(lockId, isSilent)}>{isSilent ? '🔌 Restore Power' : '⚡ Kill Power'}</button>
        <button onClick={() => onSetBattery(lockId, 5)}>🔋 Drain to 5%</button>
        <button onClick={() => onSetBattery(lockId, 100)}>🔌 Recharge</button>
        <AnimatedBattery level={batteryLevel} />
      </div>
    </div>
  )
}
