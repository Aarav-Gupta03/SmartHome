export default function AlertBanner({ alert, onDismiss, onReset, onDisarm, onForceLock, onRelease }) {
  if (!alert) return null

  const isFire = alert.eventType === 'FIRE_EVACUATION'
  const isUnlock = alert.eventType === 'UNAUTHORIZED_UNLOCK'
  const isRateLimit = alert.eventType === 'SENSOR_RATE_LIMITED'
  const isExitFailure = alert.eventType === 'EXIT_LOCK_FAILURE'

  const title = isFire
    ? `FIRE CONDITION — ${alert.sensorId}`
    : isUnlock
    ? `SECURITY ALERT — ${alert.sensorId?.replace('LOCK_', '').replace(/_/g, ' ')}`
    : isRateLimit
    ? `⚡ SENSOR QUARANTINED — ${alert.sensorId}`
    : isExitFailure
    ? `🚨 EXIT ROUTE BLOCKED — ${alert.sensorId?.replace('LOCK_', '').replace(/_/g, ' ')}`
    : `INTRUSION DETECTED — ${alert.sensorId}`

  const description = isFire
    // Fix #17: Phase 2 actually LOCKS interior doors and only UNLOCKS exit routes.
    ? `${alert.temperature?.toFixed(1)}°F recorded. Evacuation triggered: Exit routes UNLOCKED, interior doors SECURED.`
    : isUnlock
    ? `Door was unlocked while the system is ARMED. Verify this was intentional.`
    : isRateLimit
    ? `${alert.sensorId} exceeded ${alert.eventCount} writes in ${alert.windowSeconds}s. It has been rate-limited and quarantined.`
    : isExitFailure
    ? `${alert.sensorId?.replace('LOCK_', '').replace(/_/g, ' ')} is an exit route but the device is OFFLINE — it could NOT be unlocked during fire evacuation. Check this door immediately.`
    : `Motion detected while AWAY. Lockdown triggered: ALL doors LOCKED.`

  return (
    <div className={`alert-banner ${isUnlock ? 'alert-banner-warn' : ''} ${isRateLimit ? 'alert-banner-rate' : ''} ${isExitFailure ? 'alert-banner-exit-fail' : ''}`} role="alert">
      <div className="alert-banner-inner">
        <span className="alert-klaxon" />
        <div className="alert-text">
          <strong>{title}</strong>
          <span>{description}</span>
        </div>
        {!isUnlock && !isRateLimit && !isExitFailure && <button className="btn btn-ghost alert-dismiss" onClick={onReset}>Reset System</button>}
        {!isUnlock && !isRateLimit && !isExitFailure && <button className="btn btn-ghost alert-dismiss" onClick={onDismiss}>Dismiss</button>}
        {isUnlock && <button className="btn btn-ghost alert-dismiss" onClick={onForceLock}>Force Lock All</button>}
        {isUnlock && <button className="btn btn-ghost alert-dismiss" onClick={onDisarm}>Dismiss &amp; Disarm</button>}
        {isRateLimit && <button className="btn btn-ghost alert-dismiss" onClick={() => { onRelease(alert.sensorId) }}>✓ Release Sensor</button>}
        {isRateLimit && <button className="btn btn-ghost alert-dismiss" onClick={onDismiss}>Dismiss</button>}
        {isExitFailure && <button className="btn btn-ghost alert-dismiss" onClick={onDismiss}>Acknowledge</button>}
      </div>
    </div>
  )
}
