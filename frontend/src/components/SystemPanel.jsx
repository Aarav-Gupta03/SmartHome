import { useEffect, useState } from 'react'
import { api } from '../api'

export default function SystemPanel() {
  const [topology, setTopology] = useState(null)
  const [shardResult, setShardResult] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.topology().then(setTopology).catch(() => setTopology({ isReplicaSet: false, error: 'unreachable' }))
  }, [])

  async function tryShard() {
    setBusy(true)
    setShardResult(null)
    try {
      const result = await api.enableSharding()
      setShardResult(result)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="module logbook-module">
      <span className="module-label">DEPLOYMENT TOPOLOGY</span>

      {topology && (
        <div className="logbook-entry" style={{ borderBottom: 'none', flexDirection: 'column', gap: 4 }}>
          <span className="logbook-detail">
            Replica set: <strong style={{ color: topology.isReplicaSet ? 'var(--cyan)' : 'var(--alarm)' }}>
              {topology.isReplicaSet ? 'YES' : 'NO'}
            </strong> {topology.setName ? `(${topology.setName})` : ''}
          </span>
          {topology.memberCount != null && (
            <span className="logbook-detail">Members: {topology.memberCount} — Primary: {topology.primary}</span>
          )}
        </div>
      )}

      <button className="btn btn-ghost" disabled={busy} onClick={tryShard} style={{ marginTop: 10, width: '100%' }}>
        {busy ? 'Attempting...' : 'Attempt to enable sharding'}
      </button>

      {shardResult && (
        <p className="logbook-detail" style={{ marginTop: 8, fontSize: 11 }}>
          {shardResult.success ? '✅ ' : '⚠️ '}{shardResult.message}
        </p>
      )}
    </div>
  )
}
