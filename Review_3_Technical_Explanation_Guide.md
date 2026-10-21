# Smart Home IoT Data Engine — Review 3 Technical Implementation & Viva Guide

This guide is designed as an end-to-end technical reference for **Review 3**. It breaks down **14 brand-new core functionalities** (7 Application-Tier and 7 Database/NoSQL-Tier) that extend beyond Review 2, focusing on **distributed cluster topology, horizontal sharding architecture, dynamic BSON arrays, temporal time-series slicing, life-safety fault isolation, and disaster recovery**.

Every functionality details **What** each feature does, **Why** it was architected this way (engineering rationale), **How** it is implemented in the actual codebase (exact files, methods, collections, and components), and **How to defend it in front of the teacher/examiner (Viva Q&A)**.

---

## 🏛️ System Architecture Overview (Review 3 Focus)

```
 [IoT Telemetry / Simulator] ─────── Dynamic Automation Masking ──────┐
           │                                                          │
           │ HTTP POST (Sensor Readings / Events)                     │
           ▼                                                          ▼
 ┌────────────────────────────────────────────────────────────────────────┐
 │                      Spring Boot Backend Tier                          │
 │                                                                        │
 │  • Distributed Admin:  ReplicaSetTopologyService (hello command)       │
 │  • Sharding Admin:     ShardingAdminService (enableSharding / hashing) │
 │  • Temporal History:   SensorController ($gte date-range filtering)    │
 │  • Life-Safety Fault:  LockdownService (FAILED_TO_UNLOCK_EXIT handler) │
 │  • Disaster Recovery:  DeviceController (/api/devices/reset protocol)  │
 └─────────────────┬──────────────────────────────▲───────────────────────┘
                   │                              │
            Writes │ Reads                        │ Change Stream
            & ACID │                              │ (Native Push)
                   ▼                              │
 ┌────────────────────────────────────────────────────────────────────────┐
 │                      MongoDB Atlas Database Tier                       │
 │                                                                        │
 │  • Dynamic Arrays:     home_status.lockedInteriorDoors ($push/$unset)  │
 │  • Topology Admin:     admin.hello (Distributed Quorum & Voting)       │
 │  • Cluster Sharding:   sensor_data (Hashed Key Chunk Distribution)     │
 │  • Compound Filtering: (sensorId: 1, timestamp: -1) Range Queries      │
 │  • Singleton Locking:  home_status (_id: "singleton" Idempotency)      │
 │  • Safety Bypass Log:  audit_logs (RATE_LIMIT_SAFETY_OVERRIDE Routing) │
 └────────────────────────────────────────────────┬───────────────────────┘
                                                  │
                             Live Event Broadcast │ WebSocket (/ws/live)
                                                  ▼
 ┌────────────────────────────────────────────────────────────────────────┐
 │                         React + Vite Frontend                          │
 │                                                                        │
 │  • Interactive 30-Min Bezier Temperature Trendline (TemperatureChart)  │
 │  • Armed Breach Klaxon Banner (UNAUTHORIZED_UNLOCK Alert)              │
 │  • Life-Safety Actuator Failure Banner (EXIT_LOCK_FAILURE Alert)       │
 │  • Multi-Cell SVG Energy Level Readout (AnimatedBattery Widget)        │
 │  • Network Silence Disconnection Indicator (15s Silence Timeout)       │
 │  • Live Cluster Topology Inspector (Deployment Topology Module)        │
 └────────────────────────────────────────────────────────────────────────┘
```

---

# Part 1: User-Defined Functionalities (Application Tier)

---

### Feature 1: Interactive Temporal Thermal History Charting & Trend Analysis

#### 1. What It Is
An interactive, SVG-based temporal analytics component that visually tracks temperature evolution over a rolling 30-minute window for any selected room. Users can click between different rooms, inspect dynamic bezier curves, and view chronological thermal trends.

#### 2. Why We Built It (Engineering Rationale)
- **Trend Visibility Over Instantaneous Readings:** A single temperature readout ($72^\circ\text{F}$) only tells you current state. It does not reveal whether a room is cooling down or experiencing a dangerous thermal spike over time.
- **Client-Side Vector Rendering (Zero External Chart Bloat):** Rather than importing bloated third-party charting packages that degrade mobile responsiveness, the component renders raw SVG path vectors (`M`, `L`, `C`) with spring easing.

#### 3. How It Is Implemented in Code
- **Frontend Component:**
  - `frontend/src/components/TemperatureChart.jsx`: Computes SVG viewBox coordinates dynamically based on min/max temperatures, rendering smooth polyline paths and time-stamped horizontal axes.
  - `frontend/src/App.jsx`: Maintains `selectedRoom` state; triggers historical telemetry refetching whenever the user switches rooms.
- **Backend Endpoint:**
  - `controller/SensorController.java`:
    ```java
    @GetMapping("/sensors/{sensorId}/history")
    public List<Document> history(@PathVariable String sensorId,
                                   @RequestParam(defaultValue = "30") int minutes) {
        Date since = Date.from(Instant.now().minusSeconds(minutes * 60L));
        return collection.find(Filters.and(Filters.eq("sensorId", sensorId), 
                                           Filters.gte("timestamp", since)))
                .sort(Sorts.ascending("timestamp"))
                .into(new ArrayList<>());
    }
    ```

#### 4. Teacher Viva Questions & Answers
- **Q: How does the chart avoid lagging when the database contains millions of readings?**
  - **Answer:** The query uses a compound index on `(sensorId: 1, timestamp: -1)`. MongoDB performs an index-bounded range scan using the `since` timestamp threshold, reading only the exact records within the 30-minute window rather than scanning the entire collection.

![Interactive Temporal Thermal History Charting](images/review3_feature1.png)

---

### Feature 2: Unauthorized Security Breach Alerting (`UNAUTHORIZED_UNLOCK`)

#### 1. What It Is
An active intrusion detection feature that detects when any door (exterior exit route or protected interior door) is unlocked while the security system is in `AWAY` mode. It instantly generates an amber/yellow warning banner on the dashboard and logs a high-severity security incident into MongoDB.

#### 2. Why We Built It (Engineering Rationale)
- **Perimeter Arming Invariant:** When a home is armed, opening any door without disarming indicates unauthorized physical entry (lock picking, forced entry, or unauthorized guest access).
- **Sub-Second Alarm Dispatch:** By routing the security check through MongoDB Change Streams, the security warning fires across all connected devices in sub-second latency without relying on periodic polling.

#### 3. How It Is Implemented in Code
- **Backend Detection Logic (`ChangeStreamListener.java`):**
  - Inspects door unlock events.
  - If `mode == "AWAY"` and `locked == false`:
    - Checks whether `lockdownTriggeredAt` is null (ensuring fire evacuation unlocks do not trigger burglar sirens).
    - If unauthorized, writes to `audit_logs` with `eventType: "UNAUTHORIZED_UNLOCK"`.
    - Broadcasts `{ type: "UNAUTHORIZED_UNLOCK", lockId: sensorId }` over WebSocket.
- **Frontend Banner (`AlertBanner.jsx`):**
  - Displays `SECURITY ALERT — Door was unlocked while system is ARMED` with quick action buttons: "Force Lock All" and "Dismiss & Disarm".

#### 4. Teacher Viva Questions & Answers
- **Q: How does the system distinguish between a burglar opening the door vs. the automated fire protocol opening it?**
  - **Answer:** When the fire protocol unlocks exit doors, it stamps the BSON document with `lockdownTriggeredAt: ISODate()`. `ChangeStreamListener` explicitly checks `if (doc.get("lockdownTriggeredAt") == null)`. If the timestamp exists, it knows the unlock was system-authorized for evacuation and suppresses the burglar alarm.

---

### Feature 3: Life-Safety Hardware Fault Isolation (`EXIT_LOCK_FAILURE`)

#### 1. What It Is
A fail-operational safety mechanism that detects if an exit route door (e.g., Front Door or Garage Door) is unpowered, dead, or offline during a fire evacuation. Rather than crashing the transaction, it isolates the hardware failure, records a specialized failure audit entry, and broadcasts a critical emergency alert.

#### 2. Why We Built It (Engineering Rationale)
- **Fail-Operational Safety Design:** In real-world emergencies, hardware fails (wires melt, batteries die). An automated safety engine must never assume physical actuations always succeed.
- **Immediate Human Intervention:** Occupants must be warned instantly if a specific evacuation route failed to unlock so they do not run toward a deadlocked door in smoke.

#### 3. How It Is Implemented in Code
- **Transactional Fault Handling (`LockdownService.java`):**
  ```java
  if (!deviceHealthService.isDeviceOnline(lockId)) {
      if (isExitRoute) {
          offlineExitLocks.add(lockId);
          auditLogs.insertOne(session, new Document()
                  .append("eventType", "FAILED_TO_UNLOCK_EXIT")
                  .append("lockId", lockId)
                  .append("reason", "DEVICE_OFFLINE")
                  .append("timestamp", Date.from(Instant.now())));
      }
      continue;
  }
  ```
- **Post-Commit Dispatch:**
  - After the transaction commits, `LockdownService` loops through `offlineExitLocks` and broadcasts:
    `{ type: "EXIT_LOCK_FAILURE", lockId: offlineLockId, message: "Exit route could not be unlocked — device offline" }`.
- **Frontend Alert (`AlertBanner.jsx`):**
  - Renders the high-priority banner: `🚨 EXIT ROUTE BLOCKED — [DOOR] is an exit route but the device is OFFLINE`.

#### 4. Teacher Viva Questions & Answers
- **Q: Why is the `FAILED_TO_UNLOCK_EXIT` log inserted inside the transaction while the WebSocket broadcast is outside?**
  - **Answer:** Database state must be atomic. The failure audit record is committed together with the emergency evacuation record. The WebSocket broadcast occurs *after* successful commit so the UI never alarms the user if the transaction aborted.

---

### Feature 4: Dynamic Battery Voltage Modeling & Real-Time Animated Gauges

#### 1. What It Is
An continuous battery degradation model that simulates electrochemical discharge across all sensors and renders dynamic, multi-cell SVG battery indicators that transition visually from green ($100\%$) to amber ($50\%$) to flashing red ($5\%$).

#### 2. Why We Built It (Engineering Rationale)
- **Predictive Maintenance:** IoT sensors run on lithium cells. Visualizing battery drain allows building managers to service expiring hardware before an unexpected outage occurs.
- **Realistic Energy Modeling:** In production IoT networks, wireless transmission consumes power. Simulating battery drain accurately mirrors real-world telemetry behavior.

#### 3. How It Is Implemented in Code
- **Scheduled Discharge Model (`DeviceSimulationService.java`):**
  - In `heartbeatTick()`, every active device's battery drains gradually:
    ```java
    double currentBattery = batteryLevels.getOrDefault(sensorId, 100.0);
    if (currentBattery > 0) {
        currentBattery -= 0.5; // Drain battery
        batteryLevels.put(sensorId, Math.max(0.0, currentBattery));
    }
    ```
- **Interactive Energy Controls:**
  - `POST /api/devices/{deviceId}/battery` with `{ "battery": 5 }` or `{ "battery": 100 }`.
- **Frontend Component (`AnimatedBattery.jsx`):**
  - Renders a multi-bar SVG terminal with responsive fill colors:
    - Level $> 50\%$: `#22c55e` (Emerald Green)
    - Level $20\% - 50\%$: `#f59e0b` (Amber)
    - Level $< 20\%$: `#ef4444` (Alarm Red with pulse animation)

#### 4. Teacher Viva Questions & Answers
- **Q: What happens when a device's battery reaches 0%?**
  - **Answer:** In `DeviceSimulationService`, when battery hits 0%, the heartbeat ceases inserting new telemetry into MongoDB. `DeviceHealthService` detects this silence ($>15$s) and flags the device as `OFFLINE`, excluding it from subsequent decision-making.

---

### Feature 5: Network Silence & Partition Detection (Heartbeat Staleness Timeout)

#### 1. What It Is
A health-monitoring heuristic that identifies network partitions or severed sensor connections by detecting the absence of expected heartbeats ($>15$ seconds of silence). It dims the sensor's dashboard card to grey, flags it as `OFFLINE`, and displays `--` for readouts.

#### 2. Why We Built It (Engineering Rationale)
- **The "Silence is Not Safe" Principle:** In naive IoT systems, if a sensor stops transmitting, the UI simply keeps showing the last reading (e.g. $72^\circ\text{F}$). If a fire breaks out, the dead sensor leaves occupants uninformed. Silence must be actively surfaced as a failure.

#### 3. How It Is Implemented in Code
- **Backend Heuristic (`DeviceHealthService.java`):**
  ```java
  Date timestamp = latest.getDate("timestamp");
  if (timestamp != null) {
      long ageMillis = Instant.now().toEpochMilli() - timestamp.getTime();
      if (ageMillis > 15000) { // 15 seconds
          return false; // Hardware is silent -> offline
      }
  }
  ```
- **Frontend UI Degradation (`RoomGauge.jsx`, `LockSwitch.jsx`, `MotionLamp.jsx`):**
  - Calculates `isSilent = Date.now() - new Date(reading.timestamp).getTime() > 15000`.
  - Applies CSS class `module-offline` (dimmed grey, disabled buttons, `⚠️` warning icon).

#### 4. Teacher Viva Questions & Answers
- **Q: Why use a 15-second threshold when the heartbeat runs every 5 seconds?**
  - **Answer:** A 15-second window represents three missed consecutive heartbeats ($3 \times 5\text{s}$). This accounts for temporary network jitter or brief packet delays without triggering false offline warnings on a single delayed packet.

---

### Feature 6: Per-Room Simulation Masking & Granular Telemetry Participation

#### 1. What It Is
An administrative capability that allows the operator to selectively include or exclude individual rooms from background telemetry generation by clicking the indicator lamp on each gauge card.

#### 2. Why We Built It (Engineering Rationale)
- **Isolated Testing:** When testing a specific room (e.g., simulating a kitchen fire), random fluctuations in the bedroom or office introduce noise into the audit log. Masking allows focused diagnostics.

#### 3. How It Is Implemented in Code
- **State Management (`DeviceSimulationService.java`):**
  - Holds a `ConcurrentHashMap<String, Boolean> roomEnabled`.
  - In `autoTick()`, checks `if (Boolean.TRUE.equals(entry.getValue()))` before generating telemetry.
- **REST Controller (`DeviceController.java`):**
  - `POST /api/devices/rooms/{roomId}/auto-enabled` with `{ "enabled": false }`.
- **UI Interaction (`RoomGauge.jsx`):**
  - Clicking `.lamp` toggles the room's auto participation, switching between `.lamp-on` (bright green) and dark (unlit).

#### 4. Teacher Viva Questions & Answers
- **Q: Is room masking stored in MongoDB or memory?**
  - **Answer:** Room masking is stored in `ConcurrentHashMap` in memory because it is an active simulation control rather than a persistent security setting.

---

### Feature 7: One-Click Disaster Recovery & System State Reconciliation

#### 1. What It Is
A single-action master recovery protocol (`POST /api/devices/reset`) that disarms the house, unlocks all doors, normalizes ambient temperatures to $72^\circ\text{F}$, restores battery levels to $100\%$, and writes reconciling heartbeats to bring all hardware back online simultaneously.

#### 2. Why We Built It (Engineering Rationale)
- **Disaster State Recovery:** After an emergency (fire alarm or intrusion lockdown), returning dozens of locks, alarms, and quarantined sensors to normal manually is error-prone. A single reconciliation routine guarantees system baseline consistency.

#### 3. How It Is Implemented in Code
- **Reconciliation Protocol (`DeviceController.java`):**
  ```java
  @PostMapping("/reset")
  public Map<String, String> resetSystem() {
      // 1. Reset singleton home mode to HOME and clear locked interior door list
      homeStatus.updateOne(Filters.eq("_id", "singleton"),
              Updates.combine(Updates.set("mode", "HOME"), 
                              Updates.unset("lockedInteriorDoors")));
      // 2. Clear fire temperatures across all rooms
      for (String roomId : AppConfig.ROOM_SENSOR_IDS) {
          simulationService.generateRoomReading(roomId, 72.0, false);
          simulationService.setDeviceBattery(roomId, 100.0);
          simulationService.setDevicePower(roomId, true);
      }
      // 3. Unlock all doors
      for (String lockId : AppConfig.LOCK_SENSOR_IDS) {
          simulationService.setLockState(lockId, false);
          simulationService.setDeviceBattery(lockId, 100.0);
          simulationService.setDevicePower(lockId, true);
      }
      return Map.of("status", "reset_complete");
  }
  ```

#### 4. Teacher Viva Questions & Answers
- **Q: How does the reset ensure change streams don't trigger new false alarms?**
  - **Answer:** The reset updates `home_status` to `HOME` *first*. Because mode is now `HOME`, when subsequent door unlock and motion readings are written, `ChangeStreamListener` evaluates them under home rules and does not raise intrusion alerts.

---

# Part 2: NoSQL Functionalities (Database Tier)

---

### Feature 8: Live Replica Set Topology Inspection (`hello` Admin Command)

#### 1. What It Is
Direct programmatic inspection of MongoDB distributed consensus. The backend executes MongoDB's administrative `hello` command against Atlas to verify replica set election, primary node hostname, writable status, and quorum of active voting members.

#### 2. Why We Built It (Engineering Rationale)
- **Distributed Primitives Verification:** MongoDB Change Streams and multi-document ACID transactions require a replica set topology. Querying `hello` at runtime validates that the underlying database provides high-availability guarantees before safety-critical routines execute.

#### 3. How It Is Implemented in Code
- **Administrative Command Execution (`ReplicaSetTopologyService.java`):**
  ```java
  Document result = database.runCommand(new Document("hello", 1));
  String setName = result.getString("setName");
  List<String> hosts = result.getList("hosts", String.class, List.of());
  String primary = result.getString("primary");
  Boolean isWritablePrimary = result.getBoolean("isWritablePrimary");
  ```
- **REST Endpoint & UI:**
  - `GET /api/system/topology` exposes the topology map.
  - `SystemPanel.jsx` renders `Replica set: YES (rs0)`, member counts, and active primary hostname.

#### 4. Teacher Viva Questions & Answers
- **Q: What is the difference between the legacy `isMaster` command and `hello` in MongoDB?**
  - **Answer:** In MongoDB 5.0+, the legacy `isMaster` command was deprecated in favor of `hello` to align with inclusive nomenclature while returning richer cluster topology information, including `isWritablePrimary` and election metadata.

---

### Feature 9: Horizontal Cluster Sharding Administration & Hash Key Partitioning

#### 1. What It Is
Demonstrates horizontal distributed database scaling by executing real administrative `enableSharding` and `shardCollection` commands with a hashed partition key (`{ sensorId: "hashed" }`), illustrating how high-velocity IoT streams partition across cluster chunks.

#### 2. Why We Built It (Engineering Rationale)
- **Scale-Out Distributed Architecture:** A single MongoDB node can only handle finite write throughput. Sharding partitions the `sensor_data` collection across multiple mongod instances, scaling storage and write IOPS horizontally.
- **Hashed Shard Key for Even Write Distribution:** Sequential keys (like `timestamp`) cause monotonic insertion bottlenecks where all writes hit the same shard. Hashing `sensorId` distributes writes uniformly across all cluster shards.

#### 3. How It Is Implemented in Code
- **Sharding Administration (`ShardingAdminService.java`):**
  ```java
  adminDatabase.runCommand(new Document("enableSharding", DATABASE_NAME));
  adminDatabase.runCommand(new Document("shardCollection", DATABASE_NAME + "." + SENSOR_DATA_COLLECTION)
          .append("key", new Document("sensorId", "hashed")));
  ```
- **Graceful Tier Handling:** Catches `MongoCommandException` when run against non-sharded tiers (e.g., M0 free tier) and returns an educational diagnostic explaining sharding infrastructure requirements (M10+ or mongos cluster).

#### 4. Teacher Viva Questions & Answers
- **Q: Why did you pick `{ sensorId: "hashed" }` instead of `{ timestamp: 1 }` as the shard key?**
  - **Answer:** Using `{ timestamp: 1 }` creates a "hot-spotting" anti-pattern where every write goes to the shard holding the highest timestamp chunk. Hashing `sensorId` hashes values pseudo-randomly, distributing concurrent writes across all shards evenly.

---

### Feature 10: Dynamic BSON Array Manipulation via `$push` and `$unset` Operators

#### 1. What It Is
Targeted in-place array updates on BSON documents. When the security mode is `AWAY`, locking any interior door dynamically pushes its ID into `home_status.lockedInteriorDoors` using `$push`. When disarmed, the entire array is cleared atomically using `$unset`.

#### 2. Why We Built It (Engineering Rationale)
- **Eliminating Read-Modify-Write Race Conditions:** Naively reading a document, modifying an array in Java, and writing it back introduces race conditions under concurrent requests. MongoDB's `$push` and `$unset` operators execute atomically on the database server.

#### 3. How It Is Implemented in Code
- **Dynamic Array Ingestion (`ChangeStreamListener.java`):**
  ```java
  homeStatusColl.updateOne(
          Filters.eq("_id", "singleton"),
          com.mongodb.client.model.Updates.push("lockedInteriorDoors", sensorId)
  );
  ```
- **Disaster Recovery Clear (`SystemController.java`):**
  ```java
  Updates.combine(
          Updates.set("mode", "HOME"),
          Updates.unset("lockedInteriorDoors")
  );
  ```

#### 4. Teacher Viva Questions & Answers
- **Q: What is the performance benefit of `$push` vs replacing the document?**
  - **Answer:** `$push` modifies only the array field directly in the BSON byte buffer without transferring the entire document across the network, reducing bandwidth and locking overhead.

---

### Feature 11: Multi-Key Compound Range Filtering & Dynamic Projection (`$gte` + `$project`)

#### 1. What It Is
Sub-millisecond temporal telemetry extraction. Queries `sensor_data` with combined exact-match and date-range filters (`Filters.and(Filters.eq("sensorId", id), Filters.gte("timestamp", since))`) backed by the compound index `(sensorId: 1, timestamp: -1)`.

#### 2. Why We Built It (Engineering Rationale)
- **Predictable Query Latency:** IoT time-series databases accumulate millions of records. Compound range queries guarantee that query latency remains $O(\log N)$ regardless of how many millions of historical records exist.

#### 3. How It Is Implemented in Code
- **Query Pipeline (`SensorController.java`):**
  ```java
  collection.find(Filters.and(
          Filters.eq("sensorId", sensorId), 
          Filters.gte("timestamp", since)
  )).sort(Sorts.ascending("timestamp"));
  ```
- **Compound Index Support (`DatabaseInitializer.java`):**
  `Indexes.compoundIndex(Indexes.ascending("sensorId"), Indexes.descending("timestamp"))`.

#### 4. Teacher Viva Questions & Answers
- **Q: What is the Equality-Sort-Range (ESR) rule and how does this query adhere to it?**
  - **Answer:** The ESR rule dictates placing Equality fields first (`sensorId`), followed by Sort fields, then Range fields (`timestamp`). Our compound index satisfies ESR by putting `sensorId` first, enabling MongoDB to eliminate non-matching sensors before evaluating the timestamp threshold.

---

### Feature 12: Atomic Singleton Document Concurrency Pattern with Optimistic Idempotency

#### 1. What It Is
An architectural pattern that stores system-wide operational mode in a dedicated singleton collection with a deterministic fixed primary key (`_id: "singleton"`). Guarantees that multiple threads and distributed backend nodes read and mutate the exact same record without creating phantom duplicate rows.

#### 2. Why We Built It (Engineering Rationale)
- **Eliminating Multi-Node Split-Brain:** In a distributed setup with multiple Spring Boot containers behind a load balancer, storing security state in memory causes desynchronization. Forcing `_id: "singleton"` guarantees persistence and single-source-of-truth semantics.

#### 3. How It Is Implemented in Code
- **Idempotent Seeder (`DatabaseInitializer.java`):**
  ```java
  Document existing = homeStatus.find(Filters.eq("_id", "singleton")).first();
  if (existing == null) {
      homeStatus.insertOne(new Document("_id", "singleton").append("mode", "HOME"));
  }
  ```
- **Deterministic Updates (`SystemController.java`):**
  `homeStatus.updateOne(Filters.eq("_id", "singleton"), Updates.set("mode", mode));`

#### 4. Teacher Viva Questions & Answers
- **Q: Why not use a regular collection without a fixed `_id` and query by `find().limit(1)`?**
  - **Answer:** Querying without a fixed `_id` is susceptible to race conditions where two simultaneous startup routines could insert two mode documents. Using `_id: "singleton"` enforces a primary key constraint where any duplicate insert throws a duplicate key exception.

---

### Feature 13: Time-Series Contextual Tagging & Append-Only Immutability

#### 1. What It Is
Enforces true time-series data hygiene. Rather than mutating existing lock documents in-place, the system stamps new immutable telemetry documents with contextual tags (`deviceType: "SMART_LOCK"`, `isExitRoute: bool`, `batteryLevel: double`, and `lockdownTriggeredAt: Date`).

#### 2. Why We Built It (Engineering Rationale)
- **Forensic Auditability & Write Performance:** Modifying documents in-place causes BSON re-allocation on disk and destroys history. Appending tagged immutable records preserves full state lineage for post-incident security analysis.

#### 3. How It Is Implemented in Code
- **Immutable Document Construction (`LockdownService.java`):**
  ```java
  Document lockDoc = new Document()
          .append("sensorId", lockId)
          .append("timestamp", Date.from(Instant.now()))
          .append("locked", locked)
          .append("deviceType", "SMART_LOCK")
          .append("isExitRoute", isExitRoute)
          .append("batteryLevel", simulationService.getDeviceBattery(lockId))
          .append("lockdownTriggeredAt", Date.from(Instant.now()));
  sensorData.insertOne(session, lockDoc);
  ```

#### 4. Teacher Viva Questions & Answers
- **Q: How does the dashboard find the current state if documents are append-only?**
  - **Answer:** In `SensorController.java`, the `/sensors/latest` endpoint runs an aggregation pipeline using `$sort: { timestamp: -1 }`, `$group: { _id: "$sensorId", doc: { $first: "$$ROOT" } }`, and `$replaceRoot` to retrieve the latest state across all sensors in a single round-trip.

---

### Feature 14: Safety-Critical Priority Bypass & Dedicated Audit Routing (`RATE_LIMIT_SAFETY_OVERRIDE`)

#### 1. What It Is
Database-level write prioritization. When a quarantined sensor exceeds its rate limit, ordinary readings are rejected, but emergency fire readings bypass rejection and write a specialized `RATE_LIMIT_SAFETY_OVERRIDE` record to `audit_logs`.

#### 2. Why We Built It (Engineering Rationale)
- **Fail-Safe Emergency Invariant:** Blocking a malfunctioning smoke detector from reporting a genuine fire because it flooded the network earlier could lead to loss of life. Safety-critical events must always override rate limits.

#### 3. How It Is Implemented in Code
- **Bypass Decision Path (`RateLimiterService.java`):**
  ```java
  if (count >= RATE_LIMIT_MAX_EVENTS) {
      if (isSafetyCritical) {
          // Safety override: allow event through and write dedicated audit entry
          rateEventsCollection.insertOne(new Document().append("sensorId", sensorId).append("createdAt", Date.from(Instant.now())));
          auditLogsCollection.insertOne(new Document()
                  .append("eventType", "RATE_LIMIT_SAFETY_OVERRIDE")
                  .append("sensorId", sensorId)
                  .append("eventCount", count)
                  .append("timestamp", Date.from(Instant.now())));
          return true; // ALLOW WRITE
      }
      return false; // BLOCK WRITE
  }
  ```

#### 4. Teacher Viva Questions & Answers
- **Q: Why log a `RATE_LIMIT_SAFETY_OVERRIDE` event if you are allowing the write anyway?**
  - **Answer:** For accountability and auditability. The safety override log proves to system administrators that the sensor is malfunctioning and requires maintenance, even though its life-saving fire signal was permitted through.
