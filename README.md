# The Panel — IoT Smart Home Control (Full Stack)

A full-stack MongoDB IoT engine: a Spring Boot backend (REST + WebSocket,
built on time-series/change-streams/transactions) and a React dashboard
styled like a physical breaker panel with analog gauges. Every reading in
the database is the direct result of a user action in the UI unless
"AUTO MODE" is switched on (off by default).

See `plan.md` for the phased upgrade roadmap and `rules.md` for the
boundaries any assistant (chat or IDE) should follow while working on this
codebase.

```
smart-home-fullstack/
  backend/    Spring Boot app (Java 11, Maven) — REST API + WebSocket + Mongo engine
  frontend/   React + Vite dashboard ("The Panel")
  plan.md     Phased implementation plan (Phase 0 done, Phases 1-9 planned)
  rules.md    Boundaries/rules for any assistant working on this codebase
```

## Prerequisites
- JDK 11+, Maven 3.6+
- Node 18+, npm
- A MongoDB Atlas cluster (M0 free tier works) — Atlas clusters are replica
  sets, which change streams and transactions require.

## 1. Run the backend
```bash
cd backend
export MONGODB_URI="mongodb+srv://<user>:<password>@cluster0.xxxxx.mongodb.net/?retryWrites=true&w=majority"
mvn spring-boot:run
```

## 2. Run the frontend
```bash
cd frontend
cp .env.example .env
npm install
npm run dev
```
Open `http://localhost:5173`.

## API reference (backend)

| Method | Path | What it does |
|---|---|---|
| GET | `/api/devices/catalog` | Room/lock/motion sensor IDs |
| POST | `/api/devices/rooms/{id}/reading` | Insert one temperature reading |
| POST | `/api/devices/rooms/{id}/simulate-fire` | Insert a 150°F reading |
| POST | `/api/devices/locks/{id}` | Set lock state |
| POST | `/api/devices/motion/{id}` | Record a motion event |
| POST | `/api/devices/auto-mode/start` \| `/stop` | Toggle the background auto-generator |
| POST | `/api/devices/rooms/{id}/auto-enabled` | Include/exclude a room from auto mode |
| GET | `/api/sensors/latest` | Latest document per sensor |
| GET | `/api/sensors/{id}/history?minutes=30` | Recent readings for the chart |
| GET | `/api/analytics/averages` | Rolling-average materialized view rows |
| POST | `/api/analytics/run` | Recompute rolling averages now |
| GET | `/api/alerts?limit=20` | Recent lockdown/audit events (Supports Text Search via `?q=`) |
| GET | `/api/rate-limits` | Get currently quarantined sensors |
| DELETE | `/api/rate-limits/{id}` | Manually release a quarantined sensor |
| GET | `/api/rate-limits/stats` | View historical quarantine counts |
| WS | `/ws/live` | Live envelope: `{type: READING\|ALERT\|LOCKDOWN\|LOCKDOWN_FAILED, ...}` |
| GET | `/api/system/topology` | Real replica-set inspection via the `hello` admin command |
| POST | `/api/system/sharding/enable` | Attempts real `enableSharding`/`shardCollection` commands |


## Version caveats
- Change streams on time-series collections require MongoDB 6.0+.
- `updateMany()` against time-series measurement fields is version-sensitive (5.1 → 6.3+).
- Sharding only succeeds on Atlas M10+ or a self-hosted sharded cluster; M0 returns a handled failure.
