import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'

function formatTime(ts) {
  const date = typeof ts === 'string' ? new Date(ts) : new Date(ts.$date || ts)
  return isNaN(date.getTime()) ? '' : date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export default function TemperatureChart({ roomOptions, selectedRoom, onSelectRoom, history }) {
  const sortedHistory = [...history].sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime())
  const data = sortedHistory.map((d) => ({ time: formatTime(d.timestamp), temperature: d.temperature }))

  return (
    <div className="module chart-module">
      <div className="module-label-row">
        <span className="module-label">TEMPERATURE HISTORY</span>
        <select className="room-select" value={selectedRoom} onChange={(e) => onSelectRoom(e.target.value)}>
          {roomOptions.map((r) => (
            <option key={r} value={r}>{r.replace('TEMP_', '').replace('_', ' ')}</option>
          ))}
        </select>
      </div>
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={data} margin={{ top: 10, right: 16, left: -16, bottom: 0 }}>
          <CartesianGrid stroke="#3a3d43" strokeDasharray="3 3" />
          <XAxis dataKey="time" stroke="#8a8d93" fontSize={11} tick={{ fill: '#8a8d93' }} />
          <YAxis stroke="#8a8d93" fontSize={11} tick={{ fill: '#8a8d93' }} domain={[60, 160]} />
          <Tooltip
            contentStyle={{ background: '#26292E', border: '1px solid #3a3d43', borderRadius: 4 }}
            labelStyle={{ color: '#E8E6E1' }}
            itemStyle={{ color: '#F0A64B' }}
          />
          <Line type="monotone" dataKey="temperature" stroke="#F0A64B" strokeWidth={2} dot={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
