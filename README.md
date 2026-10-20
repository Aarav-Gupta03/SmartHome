# Smart Home IoT Data Engine ⚡🏠

<div align="center">

![Java](https://img.shields.io/badge/Java-11-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-2.7+-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![MongoDB](https://img.shields.io/badge/MongoDB-Atlas_%7C_6.0+-47A248?style=for-the-badge&logo=mongodb&logoColor=white)
![React](https://img.shields.io/badge/React-18-61DAFB?style=for-the-badge&logo=react&logoColor=black)
![Vite](https://img.shields.io/badge/Vite-5-646CFF?style=for-the-badge&logo=vite&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-Live_Streams-010101?style=for-the-badge&logo=socketdotio&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)

**An event-driven, full-stack Smart Home IoT telemetry platform and analog-breaker control dashboard.**  
Engineered on advanced MongoDB distributed primitives — featuring multi-document ACID transactions, native Change Streams, TTL-based sliding-window rate limiting, and wildcard full-text indexing.

[Explore Features](#-core-features) • [System Architecture](#-system-architecture) • [Database Showcase](#-advanced-nosql--database-tier) • [Quick Start](#-quick-start) • [API Reference](#-api-reference)

</div>

---

## 📸 The Panel Dashboard

The frontend is modeled after a physical industrial breaker box with brushed-graphite panels, analog amber dial gauges, mechanical toggle switches, and sub-second reactive WebSocket telemetry.

![The Panel - Interactive Dashboard](images/media_1790077580478.png)

---

## 🏛️ System Architecture

The engine bridges real-world or simulated IoT hardware sensors to a modern web client using an event-driven, push-based model:

```
 [IoT Hardware / Auto-Mode Telemetry Engine]
                     │
                     │ HTTP POST (Sensor Readings / Events)
                     ▼
 ┌─────────────────────────────────────────────────────────────┐
 │                 Spring Boot Backend Tier                    │
 │                                                             │
 │  • REST Controllers: Device, Sensor, System, Analytics      │
 │  • Safety Services:  LockdownService, RateLimiterService   │
 │  • Continuous Analytics: $setWindowFields + $merge Engine   │
 │  • ChangeStreamListener: Daemon pushing events to WebSocket │
 └──────────────┬──────────────────────────────▲───────────────┘
                │                              │
         Writes │ Reads                        │ Change Stream
         & ACID │                              │ (Native Push)
                ▼                              │
 ┌─────────────────────────────────────────────────────────────┐
 │                 MongoDB Atlas Database Tier                 │
 │                                                             │
 │  • sensor_data           (BSON Schema, Compound Index, TTL)│
 │  • audit_logs            (Wildcard $** Text Index)         │
 │  • home_status           (Singleton document: HOME / AWAY) │
 │  • sensor_rate_events    (10s TTL Index Sliding Window)    │
 │  • sensor_averages_daily (Continuous Window Aggregations)  │
 └─────────────────────────────────────────────┬───────────────┘
                                               │
                          Live Event Broadcast │ WebSocket (/ws/live)
                                               ▼
 ┌─────────────────────────────────────────────────────────────┐
 │                    React + Vite Dashboard                   │
 │                                                             │
 │  • Analog dials, status lamps, breaker toggles              │
 │  • Fire Evacuation Klaxon & Away Mode perimeter arming      │
 │  • Amber glowing sensor quarantine cards                    │
 │  • Searchable full-text audit log timeline modal            │
 └─────────────────────────────────────────────────────────────┘
```

---

## 🚀 Core Features

### 1. Dual Safety Protocol: Fire Evacuation vs. Intrusion Lockdown
- **Fire Response (`handleFireEvent`)**: Life safety takes precedence over perimeter security. All designated exit routes (`isExitRoute: true`, such as Front Door and Garage) are **automatically unlocked**, while non-exit interior doors (e.g., wine cellar) remain secured.
- **Intrusion Lockdown (`handleIntrusionEvent`)**: When the system is in `AWAY` mode and unauthorized motion is detected, all perimeter and internal doors are locked immediately.
- **Transactional Guarantee**: Executed with `WriteConcern.MAJORITY` and `ReadConcern.SNAPSHOT` multi-document ACID transactions to prevent partial lock states.

![Fire Evacuation Protocol](images/media_1790098406932.png)

---

### 2. Intelligent Sliding-Window Rate Limiting & Quarantine
- Malfunctioning, babbling, or malicious sensors attempting to flood the engine are isolated automatically.
- **Zero-Cron Sliding Window**: Every sensor event writes a lightweight record to `sensor_rate_events` with a MongoDB **TTL Index** of 10 seconds.
- If a sensor exceeds the threshold within the active sliding window, it is placed in **Quarantine Mode** (amber pulsing UI) and its non-critical writes are rejected.
- **Safety Bypass Override**: High-priority safety events (fire-level temperatures or intrusion triggers) automatically bypass the rate limiter so critical emergency alerts are never suppressed.

| Normal Operation | Quarantined Sensor (Pulsing Glow) |
|---|---|
| ![Interactive Dashboard](images/media_1790077580478.png) | ![Intelligent Sensor Quarantine](images/media_1790098874649.png) |

---

### 3. Full-Text Searchable Audit Trail
- Every safety trigger, lock switch, evacuation, and quarantine event is written to `audit_logs`.
- Powered by a MongoDB **Wildcard `$**` Text Index**, allowing instant sub-millisecond search across historical security logs with zero regex table-scan overhead.

![Full-Text Audit Log](images/media_1790098942611.png)

---

### 4. "Away Mode" Security Perimeter
- System-wide security state managed through a singleton document (`home_status`).
- Toggling between `HOME` and `AWAY` arms or disarms motion detection rules across the house and propagates instantly to all connected clients over WebSockets.

![Away Mode](images/media_1790077720508.png)

---

### 5. Hardware Diagnostics & Battery Tracking
- Monitors device battery levels and communication heartbeats.
- Sensors falling below critical voltage or failing to report within the freshness window are marked `OFFLINE` and excluded from false alarms.

![Debug Controls Offline](images/media_1790077791336.png)

---

### 6. Autonomous Background Telemetry Engine
- Built-in simulation scheduler simulating ambient daily thermal cycles, intermittent human movement, and battery discharge across multiple rooms without manual intervention.

![Auto Mode Engine](images/media_1790099003634.png)

---

## ⚡ Advanced NoSQL & Database Tier

This project implements enterprise distributed database patterns across MongoDB:

| MongoDB Feature | Implementation Location | Engineering Rationale |
|---|---|---|
| **Time-Series Collections** | `sensor_data` | Columnar compression and append-only architecture optimized for millions of telemetry data points. |
| **Multi-Document ACID Transactions** | `LockdownService.java` (`WriteConcern.MAJORITY`, `ReadConcern.SNAPSHOT`) | Guarantees atomic lock state changes during emergency evacuations; eliminates partial locks. |
| **Change Streams** | `ChangeStreamListener.java` | Native push-based reactive architecture replacing resource-draining database polling loops. |
| **TTL Indexes** | `sensor_rate_events` (10s) & `sensor_data` (7 days) | Self-pruning sliding rate window and data retention with zero application cron overhead. |
| **Wildcard Text Index (`$**`)** | `audit_logs` | Sub-millisecond full-text search across audit event messages without O(N) regex full collection scans. |
| **`$setWindowFields` + `$merge`** | `AnalyticsEngine.java` | Continuous rolling daily average calculations materialized directly into `sensor_averages_daily`. |
| **Strict BSON Schema Validation** | `DatabaseInitializer.java` (`$jsonSchema`) | Enforces data typing and field constraints on write at the database level. |
| **Singleton Document Pattern** | `home_status` | Clean, low-overhead atomic state for system mode (`HOME` vs `AWAY`). |
| **Compound Indexes** | `sensor_data`, `sensor_averages_daily` | High-performance multi-key queries on `(metadata.sensorId, timestamp)`. |

---

## 🛠️ Tech Stack

- **Backend**: Java 11, Spring Boot 2.7+, Maven, MongoDB Java Reactive/Sync Driver, Spring WebSocket.
- **Database**: MongoDB Atlas (or local MongoDB Replica Set `rs0`, version 6.0+).
- **Frontend**: React 18, Vite 5, Vanilla CSS Design System ("The Panel"), Native WebSockets, HTML5 Canvas Gauges.

---

## 📦 Project Structure

```
SmartHome/
├── backend/
│   ├── src/main/java/com/smarthome/
│   │   ├── analytics/        # Rolling averages & $setWindowFields engine
│   │   ├── config/           # Mongo, WebSocket, and Rate Limiter beans
│   │   ├── controller/       # REST API endpoints (Devices, Sensors, System)
│   │   ├── db/               # BSON schemas, seeders, topology & sharding
│   │   ├── events/           # MongoDB ChangeStreamListener daemon
│   │   ├── ratelimit/        # Sliding window rate limiter & quarantine
│   │   ├── simulator/        # Telemetry generator & device health monitor
│   │   ├── transactions/     # ACID dual fire/intrusion lockdown handlers
│   │   └── websocket/        # Real-time WebSocket broadcasting handler
│   └── src/main/resources/
│       └── application.properties
├── frontend/
│   ├── src/
│   │   ├── components/       # Panel UI components (Gauges, Breakers, Timeline, Modals)
│   │   ├── api.js            # Axios REST client
│   │   ├── useLiveSocket.js  # Real-time WebSocket hook with auto-reconnect
│   │   ├── styles.css        # "The Panel" custom industrial styling
│   │   └── App.jsx           # Main orchestrator dashboard
│   ├── index.html
│   └── vite.config.js
├── images/                   # UI screenshots and architecture visuals
├── DBMS_Abstract.md          # Academic abstract & technical highlights
├── Project_Functionalities.md# Detailed feature breakdown & review documentation
├── Review_2_Technical_Explanation_Guide.md # Comprehensive viva defense guide
├── plan.md                   # Phased engineering roadmap
├── rules.md                  # Safety invariants & boundaries
└── README.md
```

---

## 🏁 Quick Start

### Prerequisites
- **JDK 11+** & **Maven 3.6+**
- **Node.js 18+** & **npm**
- **MongoDB Atlas Cluster** (or a local MongoDB 6.0+ instance configured as a replica set `rs0` for Change Streams and Transactions support).

### 1. Database Setup
Ensure your MongoDB Atlas cluster is running and copy your connection URI.

### 2. Run the Backend
```bash
cd backend

# Set connection string (Windows PowerShell)
$env:MONGODB_URI="mongodb+srv://<username>:<password>@cluster0.xxxxx.mongodb.net/?retryWrites=true&w=majority"

# Set connection string (Bash/Linux/macOS)
# export MONGODB_URI="mongodb+srv://<username>:<password>@cluster0.xxxxx.mongodb.net/?retryWrites=true&w=majority"

# Build and run
mvn spring-boot:run
```
The backend initializes the database collections, indexes, and BSON validators automatically on startup and binds to `http://localhost:8080`.

### 3. Run the Frontend
```bash
cd frontend

# Install dependencies
npm install

# Start Vite development server
npm run dev
```
Open **`http://localhost:5173`** in your browser.

---

## 📡 API Reference

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/devices/catalog` | Get catalog of rooms, locks, and sensors |
| `POST` | `/api/devices/rooms/{id}/reading` | Insert simulated temperature reading |
| `POST` | `/api/devices/rooms/{id}/simulate-fire` | Trigger emergency fire condition (150°F) |
| `POST` | `/api/devices/locks/{id}` | Manually engage/disengage a smart lock |
| `POST` | `/api/devices/motion/{id}` | Record an active motion event |
| `POST` | `/api/devices/auto-mode/start` \| `/stop` | Toggle background autonomous telemetry |
| `GET` | `/api/sensors/latest` | Retrieve latest reading per sensor |
| `GET` | `/api/sensors/{id}/history?minutes=30` | Query historical time-series data points |
| `GET` | `/api/analytics/averages` | Fetch materialized `$setWindowFields` rolling averages |
| `POST` | `/api/analytics/run` | Trigger on-demand aggregation recomputation |
| `GET` | `/api/alerts?limit=20&q={term}` | Full-text search across `audit_logs` |
| `GET` | `/api/rate-limits` | Get currently quarantined sensors |
| `DELETE` | `/api/rate-limits/{id}` | Manually release a sensor from quarantine |
| `GET` | `/api/rate-limits/stats` | View historical quarantine statistics |
| `GET` | `/api/system/topology` | Inspect replica set configuration via MongoDB `hello` command |
| `POST` | `/api/system/sharding/enable` | Test cluster sharding configuration |
| `WS` | `/ws/live` | Real-time WebSocket feed (`READING`, `FIRE`, `INTRUSION`, `QUARANTINE`) |

---

## 🛡️ Safety Invariants

1. **Life Safety Always Trumps Perimeter Defense**:  
   During any detected fire condition (`> 100°F`), exit doors (`isExitRoute: true`) are unconditionally unlocked. Under no circumstances may an automated system trap occupants inside a burning structure.
2. **Rate Limiting Never Blocks Life-Safety Alerts**:  
   A babbling or malfunctioning sensor placed in quarantine is still permitted to trigger fire evacuation or intrusion alerts.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
