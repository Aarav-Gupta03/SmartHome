import { useEffect, useState, useCallback } from 'react'
import { api } from './api'
import { useLiveSocket } from './useLiveSocket'
import ControlStrip from './components/ControlStrip'
import RoomGauge from './components/RoomGauge'
import LockSwitch from './components/LockSwitch'
import MotionLamp from './components/MotionLamp'
import AlertBanner from './components/AlertBanner'
import AuditLog from './components/AuditLog'
import TemperatureChart from './components/TemperatureChart'
import SystemPanel from './components/SystemPanel'

export default function App() {
  const [catalog, setCatalog] = useState({ rooms: [], locks: [], motionSensors: [] })
  const [latestBySensor, setLatestBySensor] = useState({})
  const [autoState, setAutoState] = useState({ autoRunning: false, intervalSeconds: 5, roomEnabled: {} })
  const [alert, setAlert] = useState(null)
  const [auditEntries, setAuditEntries] = useState([])
  const [selectedRoom, setSelectedRoom] = useState(null)
  const [history, setHistory] = useState([])
  const [flashLockId, setFlashLockId] = useState(null)
  const [homeMode, setHomeMode] = useState('HOME')
  const [rateLimitedSensors, setRateLimitedSensors] = useState(new Set())
  const [rateLimitStats, setRateLimitStats] = useState({})

  useEffect(() => {
    (async () => {
      const cat = await api.catalog()
      setCatalog(cat)
      setSelectedRoom(cat.rooms?.[0] ?? null)

      const latest = await api.latest()
      const map = {}
      latest.forEach((doc) => { map[doc.sensorId] = doc })
      setLatestBySensor(map)

      const state = await api.autoModeState()
      setAutoState(state)

      const alerts = await api.alerts(0, 20)  // skip=0, limit=20 (was passing 20 as skip, missing most recent entries)
      setAuditEntries(alerts)

      const modeRes = await api.getHomeMode()
      if (modeRes?.mode) setHomeMode(modeRes.mode)

      // Phase 8: hydrate rate limit state
      const rl = await api.getRateLimits().catch(() => ({ quarantined: [] }))
      setRateLimitedSensors(new Set(rl.quarantined || []))
      const stats = await api.getRateLimitStats().catch(() => [])
      const statsMap = {}
      stats.forEach(s => { statsMap[s.sensorId] = s.quarantineCount })
      setRateLimitStats(statsMap)
    })().catch(console.error)
  }, [])

  useEffect(() => {
    if (!selectedRoom) return
    api.history(selectedRoom, 30).then(setHistory).catch(console.error)
  }, [selectedRoom])

  const handleMessage = useCallback((msg) => {
    if (msg.type === 'NORMAL_READING' || msg.type === 'READING') {
      const doc = msg.payload
      setLatestBySensor((prev) => ({ ...prev, [doc.sensorId]: doc }))
      if (doc.sensorId === selectedRoom) {
        setHistory((prev) => [...prev.slice(-199), doc])
      }
    } else if (msg.type === 'FIRE') {
      const doc = msg.payload
      setAlert({ sensorId: doc.triggeringSensorId, temperature: doc.triggeringTemperature, eventType: 'FIRE_EVACUATION' })
      setAuditEntries((prev) => [doc, ...prev].slice(0, 20))
      setFlashLockId('all')
      setTimeout(() => setFlashLockId(null), 2500)
      api.latest().then(latest => {
        setLatestBySensor(prev => {
          const map = { ...prev }
          latest.forEach(d => { map[d.sensorId] = d })
          return map
        })
      })
    } else if (msg.type === 'INTRUSION') {
      const doc = msg.payload
      setAlert({ sensorId: doc.triggeringSensorId, eventType: 'INTRUSION_LOCKDOWN' })
      setAuditEntries((prev) => [doc, ...prev].slice(0, 20))
      setFlashLockId('all')
      setTimeout(() => setFlashLockId(null), 2500)
      api.latest().then(latest => {
        setLatestBySensor(prev => {
          const map = { ...prev }
          latest.forEach(d => { map[d.sensorId] = d })
          return map
        })
      })
    } else if (msg.type === 'HOME_STATUS_CHANGED') {
      setHomeMode(msg.mode)
    } else if (msg.type === 'UNAUTHORIZED_UNLOCK') {
      const lockId = msg.lockId
      setAlert({ sensorId: lockId, eventType: 'UNAUTHORIZED_UNLOCK' })
      setFlashLockId(lockId)
      setTimeout(() => setFlashLockId(null), 2500)
      setAuditEntries(prev => [{ eventType: 'UNAUTHORIZED_UNLOCK', triggeringSensorId: lockId, lockId, timestamp: msg.timestamp || new Date().toISOString() }, ...prev].slice(0, 20))
    } else if (msg.type === 'SENSOR_RATE_LIMITED') {
      const { sensorId, eventCount, windowSeconds } = msg
      setRateLimitedSensors(prev => new Set([...prev, sensorId]))
      setAlert({ sensorId, eventType: 'SENSOR_RATE_LIMITED', eventCount, windowSeconds })
      setAuditEntries(prev => [{ eventType: 'RATE_LIMIT_QUARANTINE', sensorId, timestamp: new Date().toISOString() }, ...prev].slice(0, 20))
    } else if (msg.type === 'SENSOR_RELEASED') {
      const { sensorId } = msg
      setRateLimitedSensors(prev => { const next = new Set(prev); next.delete(sensorId); return next })
      // Use functional update to avoid stale closure — `alert` is not in the dependency array.
      setAlert(prev =>
        prev?.eventType === 'SENSOR_RATE_LIMITED' && prev?.sensorId === sensorId ? null : prev
      )
      // Refresh stats
      api.getRateLimitStats().then(stats => {
        const statsMap = {}
        stats.forEach(s => { statsMap[s.sensorId] = s.quarantineCount })
        setRateLimitStats(statsMap)
      }).catch(() => {})
    } else if (msg.type === 'EXIT_LOCK_FAILURE') {
      // A fire exit route could not be unlocked because the device is offline.
      // Show as a critical supplementary alert.
      setAlert({ sensorId: msg.lockId, eventType: 'EXIT_LOCK_FAILURE', message: msg.message })
      setAuditEntries(prev => [{ eventType: 'FAILED_TO_UNLOCK_EXIT', triggeringSensorId: msg.lockId, timestamp: new Date().toISOString() }, ...prev].slice(0, 20))
    } else if (msg.type === 'LOCKDOWN_FAILED') {
      // Transaction rolled back — tell the user so they know the lockdown did NOT take effect.
      setAlert({ sensorId: 'SYSTEM', eventType: 'LOCKDOWN_FAILED', message: msg.message })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRoom])

  const { connected } = useLiveSocket(handleMessage)

  const generateReading = async (roomId, temp) => {
    const doc = await api.generateReading(roomId, temp)
    if (doc?.sensorId) {
      setLatestBySensor((prev) => ({ ...prev, [doc.sensorId]: doc }))
      if (doc.sensorId === selectedRoom) {
        setHistory((prev) => [...prev.slice(-199), doc])
      }
    }
    return doc
  }

  const simulateFire = async (roomId) => {
    const doc = await api.simulateFire(roomId)
    if (doc?.sensorId) {
      setLatestBySensor((prev) => ({ ...prev, [doc.sensorId]: doc }))
      if (doc.sensorId === selectedRoom) {
        setHistory((prev) => [...prev.slice(-199), doc])
      }
    }
    return doc
  }

  const toggleLock = async (lockId, locked) => {
    const doc = await api.setLock(lockId, locked)
    if (doc?.sensorId) {
      setLatestBySensor((prev) => ({ ...prev, [doc.sensorId]: doc }))
    }
    return doc
  }

  const triggerMotion = async (motionId, detected) => {
    const doc = await api.triggerMotion(motionId, detected)
    if (doc?.sensorId) {
      setLatestBySensor((prev) => ({ ...prev, [doc.sensorId]: doc }))
    }
    return doc
  }
  const toggleRoomAuto = async (roomId, enabled) => {
    const state = await api.setRoomAutoEnabled(roomId, enabled)
    setAutoState(state)
  }
  const startAuto = async () => setAutoState(await api.startAutoMode(autoState.intervalSeconds))
  const stopAuto = async () => setAutoState(await api.stopAutoMode())

  const toggleHomeMode = async () => {
    const newMode = homeMode === 'HOME' ? 'AWAY' : 'HOME'
    await api.setHomeMode(newMode)
  }

  const setPower = async (deviceId, powered) => {
    await api.setPower(deviceId, powered)
    if (!powered) {
      // Immediately mark the device as offline in the UI by backdating its timestamp
      // so isSilent becomes true right now, without waiting 15s for a real timeout.
      setLatestBySensor(prev => {
        if (!prev[deviceId]) return prev
        return {
          ...prev,
          [deviceId]: {
            ...prev[deviceId],
            timestamp: new Date(Date.now() - 20000).toISOString()
          }
        }
      })
    } else {
      // Restore: refetch real data from DB
      const latest = await api.latest()
      const map = {}
      latest.forEach((doc) => { map[doc.sensorId] = doc })
      setLatestBySensor(map)
    }
  }

  const setBattery = async (deviceId, battery) => {
    await api.setBattery(deviceId, battery)
    const latest = await api.latest()
    const map = {}
    latest.forEach((doc) => { map[doc.sensorId] = doc })
    setLatestBySensor(map)
  }

  const resetSystem = async () => {
    await api.resetSystem()
    setAlert(null)
    // Refetch latest sensor data to reflect reset state
    const latest = await api.latest()
    const map = {}
    latest.forEach((doc) => { map[doc.sensorId] = doc })
    setLatestBySensor(map)
  }

  const handleDisarm = async () => {
    await api.setHomeMode('HOME')
    setAlert(null)
  }

  const handleForceLock = async () => {
    if (alert?.sensorId) {
      await api.triggerLockdown(alert.sensorId)
    }
    setAlert(null)
  }

  const handleReleaseSensor = async (sensorId) => {
    await api.releaseSensor(sensorId).catch(console.error)
    setRateLimitedSensors(prev => { const next = new Set(prev); next.delete(sensorId); return next })
    if (alert?.eventType === 'SENSOR_RATE_LIMITED' && alert?.sensorId === sensorId) setAlert(null)
  }

  return (
    <div className="app-shell">
      <AlertBanner 
        alert={alert} 
        onDismiss={() => setAlert(null)} 
        onReset={resetSystem}
        onDisarm={handleDisarm}
        onForceLock={handleForceLock}
        onRelease={handleReleaseSensor}
      />

      <ControlStrip
        connected={connected}
        autoState={autoState}
        onStartAuto={startAuto}
        onStopAuto={stopAuto}
        homeMode={homeMode}
        onToggleHomeMode={toggleHomeMode}
        onReset={resetSystem}
      />

      <main className="panel-grid">
        <section className="panel-section">
          <h2 className="section-heading">Temperature Rooms</h2>
          <div className="module-row">
            {catalog.rooms.map((roomId) => (
              <RoomGauge
                key={roomId}
                roomId={roomId}
                label={roomId.replace('TEMP_', '').replace('_', ' ')}
                reading={latestBySensor[roomId]}
                onGenerate={generateReading}
                onSimulateFire={simulateFire}
                autoEnabled={autoState.roomEnabled?.[roomId] ?? true}
                onToggleAuto={toggleRoomAuto}
                onSetPower={setPower}
                onSetBattery={setBattery}
                isRateLimited={rateLimitedSensors.has(roomId)}
                quarantineCount={rateLimitStats[roomId] || 0}
                onRelease={handleReleaseSensor}
              />
            ))}
          </div>
        </section>

        <section className="panel-section">
          <h2 className="section-heading">Locks</h2>
          <div className="module-row">
            {catalog.locks.map((lockId) => (
              <LockSwitch
                key={lockId}
                lockId={lockId}
                label={lockId.replace('LOCK_', '').replace('_', ' ')}
                reading={latestBySensor[lockId]}
                onToggle={toggleLock}
                justLockedByAlarm={flashLockId === 'all'}
                onSetPower={setPower}
                onSetBattery={setBattery}
                isRateLimited={rateLimitedSensors.has(lockId)}
                quarantineCount={rateLimitStats[lockId] || 0}
                onRelease={handleReleaseSensor}
              />
            ))}
          </div>
        </section>

        <section className="panel-section">
          <h2 className="section-heading">Motion</h2>
          <div className="module-row">
            {catalog.motionSensors.map((motionId) => (
              <MotionLamp
                key={motionId}
                motionId={motionId}
                label={motionId.replace('MOTION_', '').replace('_', ' ')}
                reading={latestBySensor[motionId]}
                onTrigger={triggerMotion}
                onSetPower={setPower}
                onSetBattery={setBattery}
                isRateLimited={rateLimitedSensors.has(motionId)}
                quarantineCount={rateLimitStats[motionId] || 0}
                onRelease={handleReleaseSensor}
              />
            ))}
          </div>
        </section>

        <section className="panel-section panel-section-wide">
          {selectedRoom && (
            <TemperatureChart
              roomOptions={catalog.rooms}
              selectedRoom={selectedRoom}
              onSelectRoom={setSelectedRoom}
              history={history}
            />
          )}
          <AuditLog entries={auditEntries} />
          <SystemPanel />
        </section>
      </main>

      <footer className="panel-footer">
        MongoDB Time-Series &middot; Change Streams &middot; $setWindowFields &middot; ACID Transactions
      </footer>
    </div>
  )
}
