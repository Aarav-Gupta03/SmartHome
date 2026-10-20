import { useState } from 'react'
import TimelineModal from './TimelineModal'

function formatTime(ts) {
  if (!ts) return ''
  const date = typeof ts === 'string' ? new Date(ts) : new Date(ts.$date || ts)
  return isNaN(date.getTime()) ? String(ts) : date.toLocaleTimeString()
}

export default function AuditLog({ entries }) {
  const [showModal, setShowModal] = useState(false)

  return (
    <>
      <div className="module logbook-module">
        <div className="module-label-row">
          <span className="module-label">AUDIT LOG</span>
          <button className="btn btn-ghost" onClick={() => setShowModal(true)} style={{ padding: '2px 6px', fontSize: '10px' }}>Full Timeline</button>
        </div>
        <div className="logbook">
          {entries.length === 0 && <p className="logbook-empty">No lockdown events recorded yet.</p>}
          {entries.map((entry, i) => (
            <div key={i} className="logbook-entry">
              <span className="logbook-time">{formatTime(entry.timestamp)}</span>
              <span className="logbook-detail">
                {entry.eventType} — {entry.triggeringSensorId || entry.sensorId || entry.lockId} {entry.triggeringTemperature ? `@ ${entry.triggeringTemperature.toFixed(1)}°F` : ''}
              </span>
            </div>
          ))}
        </div>
      </div>
      {showModal && <TimelineModal onClose={() => setShowModal(false)} />}
    </>
  )
}
