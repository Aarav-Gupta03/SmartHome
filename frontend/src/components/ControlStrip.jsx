export default function ControlStrip({ connected, autoState, onStartAuto, onStopAuto, homeMode, onToggleHomeMode, onReset }) {
  return (
    <header className="control-strip">
      <div className="rivet" />
      <div className="strip-title-block">
        <h1 className="strip-title">THE PANEL</h1>
        <span className="strip-subtitle">Smart Home Control &amp; Monitoring</span>
      </div>

      <div className="strip-status">
        <span className={`status-chip ${connected ? 'status-online' : 'status-offline'}`}>
          {connected ? 'LIVE FEED CONNECTED' : 'RECONNECTING…'}
        </span>
      </div>

      <div className="master-switch-block">
        <span className="module-label">SECURITY MODE</span>
        <button
          className={`rocker rocker-large ${homeMode === 'AWAY' ? 'rocker-locked' : 'rocker-unlocked'}`}
          onClick={onToggleHomeMode}
          aria-pressed={homeMode === 'AWAY'}
        >
          <span className="rocker-thumb" />
        </button>
        <span className="switch-state">{homeMode === 'AWAY' ? 'AWAY — armed' : 'HOME — unarmed'}</span>
      </div>

      <div className="master-switch-block">
        <span className="module-label">AUTO MODE</span>
        <button
          className={`rocker rocker-large ${autoState?.autoRunning ? 'rocker-locked' : 'rocker-unlocked'}`}
          onClick={() => (autoState?.autoRunning ? onStopAuto() : onStartAuto())}
          aria-pressed={autoState?.autoRunning}
        >
          <span className="rocker-thumb" />
        </button>
        <span className="switch-state">{autoState?.autoRunning ? `RUNNING (${autoState.intervalSeconds}s)` : 'OFF — manual'}</span>
      </div>

      <div className="master-switch-block">
        <span className="module-label">BATTERY</span>
        <button
          className="btn btn-primary"
          onClick={onReset}
          style={{ width: '100%', height: '40px', marginTop: '4px' }}
        >
          Recharge All
        </button>
      </div>

      <div className="rivet" />
    </header>
  )
}
