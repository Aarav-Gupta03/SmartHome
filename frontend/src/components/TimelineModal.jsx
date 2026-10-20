import { useState, useEffect } from 'react'
import { api } from '../api'

function formatTime(ts) {
  if (!ts) return ''
  const date = typeof ts === 'string' ? new Date(ts) : new Date(ts.$date || ts)
  return isNaN(date.getTime()) ? String(ts) : date.toLocaleString()
}

export default function TimelineModal({ onClose }) {
  const [logs, setLogs] = useState([])
  const [skip, setSkip] = useState(0)
  const [loading, setLoading] = useState(true)
  const [hasMore, setHasMore] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')

  const limit = 20

  useEffect(() => {
    loadMore(true)
  }, [])

  async function loadMore(reset = false) {
    setLoading(true)
    try {
      const currentSkip = reset ? 0 : skip
      const fetched = await api.alerts(currentSkip, limit, searchQuery)
      if (fetched.length < limit) {
        setHasMore(false)
      } else {
        setHasMore(true)
      }
      setLogs(prev => reset ? fetched : [...prev, ...fetched])
      setSkip(currentSkip + limit)
    } catch (err) {
      console.error('Failed to load audit logs', err)
    } finally {
      setLoading(false)
    }
  }

  const handleSearch = (e) => {
    e.preventDefault()
    loadMore(true)
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-content timeline-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2 className="section-heading">Historical Event Timeline</h2>
          <button className="btn btn-ghost" onClick={onClose}>Close</button>
        </div>

        <form onSubmit={handleSearch} className="timeline-search-form" style={{ display: 'flex', gap: '8px', marginBottom: '16px', padding: '0 16px' }}>
          <input 
            type="text" 
            placeholder="Search logs (e.g. FIRE, GARAGE)..." 
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            className="panel-select"
            style={{ flex: 1, padding: '8px 12px', fontSize: '14px' }}
          />
          <button type="submit" className="btn btn-primary" style={{ padding: '8px 16px' }}>Search</button>
        </form>
        
        <div className="timeline-list">
          {logs.length === 0 && !loading && (
            <p className="logbook-empty">No events found in history.</p>
          )}
          {logs.map((entry, i) => (
            <div key={i} className="timeline-entry">
              <div className="timeline-time">{formatTime(entry.timestamp)}</div>
              <div className="timeline-detail">
                <span className="logbook-time">{entry.eventType}</span> — {entry.triggeringSensorId || entry.sensorId || entry.lockId}
                {entry.triggeringTemperature ? ` @ ${entry.triggeringTemperature.toFixed(1)}°F` : ''}
              </div>
            </div>
          ))}
        </div>
        
        {hasMore && (
          <div className="modal-footer" style={{ marginTop: '16px' }}>
            <button 
              className="btn btn-primary" 
              onClick={() => loadMore(false)} 
              disabled={loading}
              style={{ width: '100%', padding: '12px' }}
            >
              {loading ? 'Loading...' : 'Load More'}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
