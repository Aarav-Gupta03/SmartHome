# Smart Home IoT Data Engine — Review 2 Technical Implementation & Viva Guide

This guide is designed as an end-to-end technical reference for **Review 2**. It breaks down all 14 core functionalities (7 Application-Tier and 7 Database/NoSQL-Tier), explaining **What** each feature does, **Why** it was architected this way (engineering rationale), **How** it is implemented in the actual codebase (exact files, methods, collections, and components), and **How to defend it in front of the teacher/examiner (Viva Q&A)**.

---

## 🏛️ System Architecture Overview

```
 [IoT Hardware / Simulator]
          │
          │ HTTP POST / Telemetry Stream
          ▼
┌─────────────────────────────────────────────────────────────┐
│                 Spring Boot Backend Tier                    │
│                                                             │
│  Controllers: DeviceController, SensorController, System    │
│  Services:    LockdownService, RateLimiterService, Health   │
│  Analytics:   AnalyticsEngine ($setWindowFields + $merge)   │
│  Events:      ChangeStreamListener (Dedicated Daemon)       │
└──────────────┬──────────────────────────────▲───────────────┘
               │                              │
        Writes │ Reads                        │ Change Stream
        & ACID │                              │ (Native Push)
               ▼                              │
┌─────────────────────────────────────────────────────────────┐
│                 MongoDB Atlas Database Tier                 │
│                                                             │
│  Collections:                                               │
│   • sensor_data           (BSON Schema, Compound Index, TTL)│
│   • audit_logs            (Wildcard $** Text Index)         │
│   • home_status           (Singleton document: HOME/AWAY)   │
│   • sensor_rate_events    (10s TTL Index Sliding Window)    │
│   • sensor_averages_daily (Aggregated Windowed Analytics)   │
└─────────────────────────────────────────────┬───────────────┘
                                              │
                         Live Event Broadcast │ WebSocket (/ws/live)
                                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    React + Vite Frontend                    │
│                                                             │
│  Dashboard ("The Panel" Breaker Aesthetic):                 │
│   • Animated Room Gauges & Amber Quarantine Glow            │
│   • Interactive Smart Lock Breakers with Exit Route Badges  │
│   • Full-Text Searchable Audit Log Timeline Modal           │
│   • Fire Klaxon Red Alert Banner & Security Arming Toggles  │
└─────────────────────────────────────────────────────────────┘
```

---

# Part 1: User-Defined Functionalities (Application Tier)

---

### Feature 1: Interactive Dashboard Monitoring ("The Breaker Panel")

#### 1. What It Is
A centralized, real-time visualization dashboard styled like an industrial electrical breaker panel. It displays live temperatures across five rooms with animated SVG gauges, smart lock states with emergency exit route designations, motion detection indicators, device battery levels, and connectivity statuses.

#### 2. Why We Built It (Engineering Rationale)
- **Sub-Second Latency:** Traditional smart home dashboards rely on HTTP polling (e.g., polling every 2–5 seconds). In safety-critical systems (like fire or break-ins), a 5-second polling delay can be dangerous.
- **Physical Affordance ("The Breaker Panel"):** Rather than generic card widgets, adopting an electrical breaker aesthetic gives immediate visual hierarchy: switches physically look locked/tripped, emergency routes are highlighted, and power cuts feel visceral.

#### 3. How It Is Implemented in Code
- **Frontend Components:**
  - `frontend/src/App.jsx`: Master dashboard container orchestrating live state updates.
  - `frontend/src/components/RoomGauge.jsx`: Renders circular SVG gauges that dynamically calculate SVG stroke offsets (`strokeDashoffset`) to smoothly animate temperature changes.
  - `frontend/src/components/LockSwitch.jsx`: Renders industrial toggles with green/red states and "EXIT ROUTE" badges.
  - `frontend/src/useLiveSocket.js`: Manages the resilient WebSocket client, automatically handling reconnects and distributing incoming JSON packets to React state.
- **Backend Flow:**
  - `websocket/LiveUpdateWebSocketHandler.java`: Tracks active client sessions in a concurrent set (`CopyOnWriteArraySet`). Broadcasts new sensor updates to all open web browser sessions instantaneously.

#### 4. Teacher Viva Questions & Answers
- **Q: How does the dashboard avoid re-rendering the entire page on every single sensor update?**
  - **Answer:** In `App.jsx`, state is partitioned by sensor ID (`sensors` dictionary). Updates received over the WebSocket dispatch targeted state updates using sensor keys. Child components like `RoomGauge` and `LockSwitch` are parameterized to only re-render when their specific device props change.
- **Q: What happens if the WebSocket connection drops?**
  - **Answer:** `useLiveSocket.js` implements an exponential backoff reconnection loop. While disconnected, the frontend displays an offline status indicator and automatically flushes and resynchronizes state upon reconnection.

---

### Feature 2: "Away Mode" Security Automation

#### 1. What It Is
A system-wide security mode switch. When toggled from `HOME` to `AWAY`:
1. All smart locks across the house automatically engage.
2. Motion sensors are primed for intruder detection.
3. Any motion detected while in `AWAY` mode triggers an `INTRUSION_LOCKDOWN` security protocol and an audit log event.

#### 2. Why We Built It (Engineering Rationale)
- **Centralized Security State:** Eliminates the human error of leaving exterior doors unlocked when leaving the house.
- **Context-Aware Sensing:** The meaning of sensor data changes based on state: motion while `HOME` simply turns on a lamp; motion while `AWAY` signifies an unauthorized intrusion.

#### 3. How It Is Implemented in Code
- **Singleton Collection:** `home_status` stores a single document: `{ _id: "singleton", mode: "HOME" | "AWAY" }`.
- **Backend Controller & Service:**
  - `controller/SystemController.java`: Exposes `GET /api/system/mode` and `POST /api/system/mode?mode=AWAY`.
  - When `AWAY` is set, `SystemController` updates the singleton document in MongoDB, dispatches lock commands for all doors, and broadcasts `MODE_CHANGED` over WebSocket.
- **Intrusion Detection Trigger:**
  - `events/ChangeStreamListener.java`: When a motion sensor document is inserted, it checks the `home_status` singleton. If `mode.equals("AWAY")` and `motionDetected == true`, it immediately invokes `lockdownService.handleIntrusionEvent(sensorId)`.

#### 4. Teacher Viva Questions & Answers
- **Q: Why store the mode in a MongoDB singleton collection instead of an in-memory Java variable?**
  - **Answer:** An in-memory variable in Java is volatile. If the Spring Boot backend restarts, crashes, or is deployed across multiple replica instances behind a load balancer, the security state would be lost or desynchronized. Storing it in MongoDB guarantees persistence and consistency across all backend processes.

---

### Feature 3: Hardware Simulation & Debug Controls

#### 1. What It Is
A built-in developer and demonstration panel that allows users to manually simulate real-world hardware anomalies:
1. Spiking room temperature to trigger emergency protocols.
2. Simulating a battery drain down to 5%.
3. Simulating a total power cut / network severing (taking a device offline).

#### 2. Why We Built It (Engineering Rationale)
- **Testing Edge Cases in Software:** In IoT development, you cannot set physical fire to a room or wait 6 months for a lithium battery to drain just to test software handlers. A hardware simulator allows deterministic, repeatable edge-case validation.
- **Fail-Safe Verification:** Verifies how the system behaves when critical hardware fails (e.g., ensuring the fire evacuation logic handles an offline door gracefully).

#### 3. How It Is Implemented in Code
- **Backend Service:**
  - `simulator/DeviceHealthService.java`: Maintains the online/offline state of all devices in a thread-safe `ConcurrentHashMap`. Provides `killDevice(deviceId)`, `restoreDevice(deviceId)`, and `isDeviceOnline(deviceId)`.
  - `simulator/DeviceSimulationService.java`: Manages simulated battery percentages and manual sensor value overrides.
- **Backend Controller:**
  - `controller/DeviceController.java`: Exposes endpoints:
    - `POST /api/devices/{id}/kill`: Takes sensor offline.
    - `POST /api/devices/{id}/restore`: Brings sensor back online.
    - `POST /api/devices/{id}/drain-battery`: Sets battery to 5%.
- **Frontend Controls:**
  - `frontend/src/components/ControlStrip.jsx`: Contains the interactive buttons and sliders that send HTTP requests to these endpoints.

#### 4. Teacher Viva Questions & Answers
- **Q: If a door lock is killed (offline), what happens if a fire breakout occurs?**
  - **Answer:** In `LockdownService.java`, before attempting to update a lock document during an emergency, the code checks `deviceHealthService.isDeviceOnline(lockId)`. If offline, it logs a critical warning, skips sending electrical unlock signals to that dead lock, and alerts the user on the dashboard so occupants can use alternate exits.

---

### Feature 4: Autonomous Fire Evacuation Protocol (Advanced)

#### 1. What It Is
A safety-critical automated protocol. If any temperature sensor reports an ambient temperature exceeding **100°F (37.8°C)**:
1. Designated **Exit Route Doors** (Front Door, Back Door, Garage Door) **INSTANTLY UNLOCK** to allow occupants to escape and firefighters to enter.
2. Non-exit interior doors (Bedroom Door, Wine Cellar) **remain LOCKED** to contain smoke and slow fire propagation.
3. The entire frontend triggers a flashing red **Emergency Klaxon Banner**.
4. The entire operation is wrapped in a **MongoDB Multi-Document ACID Transaction**.

#### 2. Why We Built It (Safety-Critical Rule)
> ⚠️ **CRITICAL RULE:** Fire response must **NEVER** lock all doors. Locking all doors traps occupants inside a burning building! Exit route doors (`isExitRoute: true`) must ALWAYS be unlocked.

#### 3. How It Is Implemented in Code
- **Configuration:**
  - `AppConfig.java`: Defines `FIRE_TEMP_THRESHOLD = 100.0` and maps exit routes:
    ```java
    public static final Map<String, Boolean> LOCK_EXIT_ROUTES = Map.of(
        "LOCK_FRONT_DOOR", true,
        "LOCK_BACK_DOOR", true,
        "LOCK_GARAGE_DOOR", true,
        "LOCK_BEDROOM_DOOR", false,
        "LOCK_WINE_CELLAR", false
    );
    ```
- **Detection:**
  - `events/ChangeStreamListener.java`: Listens to MongoDB change streams. If `temperature > 100.0`, it invokes `lockdownService.handleFireEvent(sensorId, temp)`.
- **Execution (ACID Transaction):**
  - `transactions/LockdownService.java`:
    ```java
    boolean isExitRoute = entry.getValue();
    boolean locked = !isExitRoute; // Exits UNLOCKED (false), interior LOCKED (true)
    ```
    Inserts a `FIRE_EVACUATION` record into `audit_logs` and writes new lock state documents to `sensor_data` inside a single atomic transaction.
- **Frontend Alert:**
  - `frontend/src/components/AlertBanner.jsx`: Listens for WebSocket packets of type `FIRE` and triggers an urgent flashing alarm banner with the triggering room and temperature.

#### 4. Teacher Viva Questions & Answers
- **Q: Why don't you unlock ALL doors during a fire? Why keep the Bedroom and Wine Cellar locked?**
  - **Answer:** In modern fire safety engineering (NFPA standards), compartmentalization is crucial. Keeping interior non-exit doors closed and latched cuts off oxygen supply, slowing flame and toxic smoke spread, while designated exit route doors must be unlatched to guarantee immediate egress for residents.
- **Q: What is the "Document-Level Metadata" vs "Global State Machine" architectural note in the documentation?**
  - **Answer:** Currently, when exit doors are unlocked by fire, documents are stamped with `lockdownTriggeredAt` so the burglar alarm ignores them. A future enhancement would be a **Global State Machine** that formally transitions the entire system state from `AWAY` to `FIRE_EVACUATION`, disabling burglar alarms globally rather than per document.

---

### Feature 5: Intelligent Sensor Quarantine (Self-Healing Rate Limiter)

#### 1. What It Is
An intelligent self-healing mechanism that protects the IoT engine from erratic, malfunctioning, or short-circuiting sensors (e.g., a broken sensor firing 50 readings a second). 
- If a sensor exceeds **5 events in a 10-second sliding window**, it is automatically placed in **Quarantine**.
- While quarantined, its readings are dropped to prevent database bloat and false alarms.
- The UI displays an amber pulsing glow on the affected room.
- **Safety Override:** If the sensor reports a life-threatening temperature (> 100°F), the quarantine is **bypassed immediately**.
- **Self-Healing:** Once the event storm stops, MongoDB's TTL index purges the excess event documents, and the sensor auto-releases.

#### 2. Why We Built It (Engineering Rationale)
- **Denial of Service (DoS) Prevention:** IoT networks are prone to electrical noise and babbling-node syndrome. Without rate limiting, one bad sensor can exhaust server CPU, saturate WebSocket bandwidth, and flood the database.
- **Safety Priority:** Standard rate limiters blindly drop everything. Our engine understands context: **hardware health is secondary to human life**, so fire signals override the limit.

#### 3. How It Is Implemented in Code
- **Backend Service:**
  - `ratelimit/RateLimiterService.java`:
    1. Writes an event stamp into `sensor_rate_events`: `{ sensorId, createdAt: NOW }`.
    2. Counts records for that sensor: `countDocuments(Filters.eq("sensorId", sensorId))`.
    3. If `count > 5`:
       - If `isSafetyCritical == true` (Fire): Logs `RATE_LIMIT_SAFETY_OVERRIDE` and allows the event through.
       - If not: Adds sensor to `quarantinedSensors` set, logs `RATE_LIMIT_QUARANTINE` to `audit_logs`, and broadcasts `SENSOR_RATE_LIMITED` to the frontend.
    4. Auto-release check: Scheduled every 15s (`@Scheduled(fixedDelay = 15000)`). Once the 10s TTL window has purged documents in MongoDB and count drops below 5, it auto-releases the sensor and broadcasts `SENSOR_RELEASED`.
- **Database Index:**
  - `db/DatabaseInitializer.java`: Creates an automated TTL index on `sensor_rate_events` with `expireAfterSeconds = 10`.

#### 4. Teacher Viva Questions & Answers
- **Q: How does the sliding window work without a background thread deleting records every second?**
  - **Answer:** We offload data eviction directly to MongoDB using a **TTL (Time-To-Live) index** on the `createdAt` field. MongoDB's background TTL thread automatically purges documents older than 10 seconds. The sliding window is managed natively by the database engine.

---

### Feature 6: Full-Text Audit Logging Timeline

#### 1. What It Is
A searchable, reverse-chronological event timeline. Every significant occurrence in the house—door lock changes, Away mode arming, fire triggers, quarantine events, and security overrides—is persisted as an immutable audit record with timestamps, metadata, and reason codes.

#### 2. Why We Built It (Engineering Rationale)
- **Traceability & Forensics:** If an intrusion occurs or a fire protocol triggers, homeowners and investigators need an unalterable forensic record of what happened and when.
- **Compliance & Security:** Required for high-grade IoT security systems to detect unauthorized tampering and maintain auditability.

#### 3. How It Is Implemented in Code
- **MongoDB Collection:** `audit_logs`
- **Backend Writing:**
  - `LockdownService.java`, `RateLimiterService.java`, and `SystemController.java` write standardized audit documents:
    ```json
    {
      "eventType": "FIRE_EVACUATION",
      "reason": "FIRE_TEMPERATURE_THRESHOLD_EXCEEDED",
      "triggeringSensorId": "TEMP_KITCHEN",
      "triggeringTemperature": 104.2,
      "timestamp": "2026-09-23T00:15:30Z"
    }
    ```
- **Frontend Components:**
  - `frontend/src/components/AuditLog.jsx`: A compact timeline on the main dashboard showing the most recent 10 events.
  - `frontend/src/components/TimelineModal.jsx`: An expanded full-screen modal providing full-text search across all historical logs.

#### 4. Teacher Viva Questions & Answers
- **Q: How do you search through thousands of audit logs quickly?**
  - **Answer:** We created a **MongoDB Wildcard Text Index (`$**`)** on the `audit_logs` collection. Instead of running a slow $O(N)$ regex table scan, MongoDB tokenizes all string fields into an inverted index, allowing logarithmic $O(\log N)$ full-text search.

---

### Feature 7: Automated Background Telemetry Engine ("Auto Mode")

#### 1. What It Is
A self-driving background telemetry generator. When the user enables the "Auto Mode" switch on the dashboard, the backend procedurally simulates a living smart home environment without requiring manual button clicks. It generates randomized temperature fluctuations within realistic seasonal ranges, periodic motion triggers, and door checks.

#### 2. Why We Built It (Engineering Rationale)
- **Live Demonstration Capability:** In a viva or live project evaluation, manually typing REST commands or clicking buttons is tedious. Auto Mode demonstrates the engine's real-time streaming, gauge animations, and data pipeline continuously.
- **System Load Simulation:** Simulates continuous write throughput to test the database aggregation pipelines and change streams under realistic traffic.

#### 3. How It Is Implemented in Code
- **Backend Simulator:**
  - `simulator/DeviceSimulationService.java`: Annotations `@EnableScheduling` and `@Scheduled(fixedRate = 2000)`.
  - Maintains state vectors for temperature, battery drain, and motion.
  - Generates realistic telemetry (e.g., temperatures fluctuating using Gaussian Brownian noise between 68°F and 76°F).
  - Inserts new documents into MongoDB's `sensor_data` collection every 2 seconds.
- **Frontend Toggle:**
  - `frontend/src/components/ControlStrip.jsx`: Contains the "Auto Mode" toggle switch connected to `POST /api/simulator/auto-mode`.

#### 4. Teacher Viva Questions & Answers
- **Q: Does Auto Mode bypass the database and send data directly to the frontend?**
  - **Answer:** **No, absolutely not.** Auto Mode inserts records into MongoDB Atlas via standard Spring Boot repository calls. The frontend only receives the data because MongoDB's **Change Stream** catches the insert and pushes it over the WebSocket. It exercises the complete end-to-end data pipeline.

---

# Part 2: NoSQL Functionalities (Database Tier)

---

### Feature 8: Strict BSON Schema Validation

#### 1. What It Is
Although MongoDB is known as "schema-less," our system enforces strict server-side structural and type validation on the `sensor_data` collection using MongoDB's `$jsonSchema` specification.

#### 2. Why We Built It (Engineering Rationale)
- **The "Schema-on-Read" Trap:** Completely schema-less databases can easily be corrupted by bad client code (e.g., storing temperature as a string `"72"` instead of a double `72.0`, or misnaming a field). This breaks analytical aggregation pipelines and causes downstream runtime crashes.
- **Hybrid Benefit:** We get the flexible document model of NoSQL while retaining the data integrity and type safety of traditional RDBMS engines.

#### 3. How It Is Implemented in Code
- **File:** `backend/src/main/java/com/smarthome/db/DatabaseInitializer.java`
- **Code Snippet:**
  ```java
  private Document buildSchemaValidator() {
      Document properties = new Document()
              .append("sensorId", new Document("bsonType", "string"))
              .append("timestamp", new Document("bsonType", "date"))
              .append("temperature", new Document("bsonType", "double"))
              .append("locked", new Document("bsonType", "bool"))
              .append("motionDetected", new Document("bsonType", "bool"))
              .append("isExitRoute", new Document("bsonType", "bool"))
              .append("batteryLevel", new Document("bsonType", List.of("double", "int")));

      Document jsonSchema = new Document()
              .append("bsonType", "object")
              .append("required", List.of("sensorId", "timestamp"))
              .append("properties", properties);

      return new Document("$jsonSchema", jsonSchema);
  }
  ```
- **Execution:** Applied during collection creation with `ValidationLevel.MODERATE` and `ValidationAction.WARN` (or `ERROR`), ensuring that invalid data documents are rejected at the database engine level.

#### 4. Teacher Viva Questions & Answers
- **Q: What is the difference between ValidationLevel.STRICT and ValidationLevel.MODERATE in MongoDB?**
  - **Answer:** `STRICT` applies schema validation to all inserts and all existing documents upon update. `MODERATE` applies schema validation to all new inserts and only validates existing documents if they already met the schema requirements, preventing legacy records from blocking routine updates during schema evolution.
- **Q: Can a document have extra fields not defined in the schema?**
  - **Answer:** Yes, by default `additionalProperties` is true in MongoDB `$jsonSchema`. This allows semi-structured data: all sensor documents must contain `sensorId` and `timestamp` with strict types, but a temperature sensor can include `temperature` while a lock can include `locked` and `isExitRoute`.

---

### Feature 9: Real-Time Change Streams

#### 1. What It Is
An event-driven database listener. Instead of the Spring Boot backend repeatedly polling MongoDB (`SELECT * ... WHERE timestamp > lastPoll`), the backend establishes a persistent cursor on MongoDB's internal replication log (Oplog). The database natively notifies the backend within milliseconds whenever an `insert` occurs.

#### 2. Why We Built It (Engineering Rationale)
- **Elimination of Polling Waste:** Polling creates massive query overhead, burns database compute, and still introduces latency (e.g., a 1-second polling interval means events are delayed by up to 1000ms).
- **True Reactive Event Architecture:** Change Streams transform MongoDB into a real-time event hub similar to Apache Kafka, enabling sub-millisecond propagation from database write to user interface.

#### 3. How It Is Implemented in Code
- **File:** `backend/src/main/java/com/smarthome/events/ChangeStreamListener.java`
- **Code Snippet:**
  ```java
  // Filter pipeline: Only listen to new 'insert' operations
  List<Document> insertOnlyPipeline = List.of(
          new Document("$match", new Document("operationType", "insert"))
  );

  ChangeStreamIterable<Document> changeStream = sensorDataCollection.watch(insertOnlyPipeline);
  MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor = changeStream.cursor();

  while (cursor.hasNext()) {
      ChangeStreamDocument<Document> change = cursor.next();
      Document fullDoc = change.getFullDocument();
      // 1. Check for emergency fire temperature
      if (fullDoc.containsKey("temperature") && fullDoc.getDouble("temperature") > FIRE_TEMP_THRESHOLD) {
          lockdownService.handleFireEvent(fullDoc.getString("sensorId"), fullDoc.getDouble("temperature"));
      }
      // 2. Broadcast immediately over WebSocket to React UI
      webSocketHandler.broadcast(fullDoc.toJson());
  }
  ```

#### 4. Teacher Viva Questions & Answers
- **Q: What is the underlying database requirement for MongoDB Change Streams to work?**
  - **Answer:** Change Streams require a **MongoDB Replica Set** (or a Sharded Cluster). They read from the `oplog.rs` (operations log). A standalone `mongod` instance without replication enabled cannot support Change Streams. Our project runs on MongoDB Atlas, which natively operates as a multi-node replica set.
- **Q: What happens if the backend server restarts? Are missed change events lost?**
  - **Answer:** Change stream tokens (`resumeAfter`) can be saved. When restarting, a change stream can provide its last received resume token to replay events that occurred during downtime.

---

### Feature 10: Multi-Document ACID Transactions

#### 1. What It Is
When executing critical operations like a fire evacuation or security lockdown, the system modifies multiple documents across multiple collections (e.g., updating 5 door documents in `sensor_data` while inserting an incident record in `audit_logs`). 
By utilizing MongoDB's Multi-Document ACID Transactions, the operation is **all-or-nothing**: either every door state and audit log is committed, or if any error occurs, the entire operation rolls back.

#### 2. Why We Built It (Engineering Rationale)
- **Preventing Partial Failure States:** Imagine a fire occurs: the Front Door unlocks, but a network glitch causes the Garage Door write to fail. In a non-transactional system, occupants heading to the garage are trapped. ACID transactions ensure consistency across multiple documents.
- **Debunking NoSQL Myths:** Demonstrates that modern NoSQL databases (MongoDB 4.0+) are no longer limited to single-document atomicity; they support full ACID compliance across collections.

#### 3. How It Is Implemented in Code
- **File:** `backend/src/main/java/com/smarthome/transactions/LockdownService.java`
- **Code Snippet:**
  ```java
  ClientSessionOptions sessionOptions = ClientSessionOptions.builder()
          .causallyConsistent(true)
          .build();

  TransactionOptions transactionOptions = TransactionOptions.builder()
          .readConcern(ReadConcern.SNAPSHOT)
          .writeConcern(WriteConcern.MAJORITY)
          .build();

  try (ClientSession session = client.startSession(sessionOptions)) {
      session.withTransaction(() -> {
          // 1. Write Audit Log
          auditLogs.insertOne(session, fireEvent);

          // 2. Write New Lock States for all 5 doors
          for (var entry : com.smarthome.AppConfig.LOCK_EXIT_ROUTES.entrySet()) {
              boolean isExitRoute = entry.getValue();
              Document lockDoc = new Document()
                      .append("sensorId", entry.getKey())
                      .append("locked", !isExitRoute) // Exits unlocked, others locked
                      .append("timestamp", Date.from(Instant.now()));
              sensorData.insertOne(session, lockDoc);
          }
          return "Transaction Success";
      }, transactionOptions);
  } catch (Exception e) {
      log.error("Transaction rolled back due to error: {}", e.getMessage());
  }
  ```

#### 4. Teacher Viva Questions & Answers
- **Q: What is the isolation level used in MongoDB transactions?**
  - **Answer:** MongoDB uses **Snapshot Isolation** (`ReadConcern.SNAPSHOT`). Reads inside the transaction reflect a consistent snapshot of the database at the start of the transaction, avoiding dirty reads, non-repeatable reads, and phantom reads.
- **Q: What does `WriteConcern.MAJORITY` mean?**
  - **Answer:** It guarantees that the transaction commit will only be acknowledged after the writes have been successfully committed to a majority of replica set members, preventing data loss even if the primary node crashes immediately after commit.

---

### Feature 11: TTL (Time-To-Live) Sliding Windows

#### 1. What It Is
A database-native sliding window used by our sensor rate limiter. When a sensor reports an event, an entry is inserted into `sensor_rate_events` with a timestamp. MongoDB automatically purges documents after **10 seconds** using a native TTL index.

#### 2. Why We Built It (Engineering Rationale)
- **Zero Application Overhead:** In traditional systems, developers must write cron jobs or background executor threads running `DELETE FROM rate_events WHERE createdAt < NOW() - INTERVAL 10 SECOND`. This wastes backend CPU and creates database lock contention.
- **Self-Cleaning Database:** MongoDB handles document eviction in the background C++ storage engine, ensuring the sliding window remains lean and fast.

#### 3. How It Is Implemented in Code
- **Index Creation:**
  - `backend/src/main/java/com/smarthome/db/DatabaseInitializer.java`:
    ```java
    MongoCollection<Document> rateEvents = database.getCollection("sensor_rate_events");
    rateEvents.createIndex(
        Indexes.ascending("createdAt"),
        new IndexOptions().expireAfter(10L, TimeUnit.SECONDS).name("rate_events_ttl")
    );
    ```
- **Evaluation:**
  - `backend/src/main/java/com/smarthome/ratelimit/RateLimiterService.java`:
    ```java
    // Insert new event
    rateEvents.insertOne(new Document("sensorId", sensorId).append("createdAt", new Date()));
    // Count active events in the 10s window
    long count = rateEvents.countDocuments(Filters.eq("sensorId", sensorId));
    if (count > 5) {
        // Quarantine sensor!
    }
    ```

#### 4. Teacher Viva Questions & Answers
- **Q: How does MongoDB physically expire TTL documents?**
  - **Answer:** MongoDB runs a background thread once every 60 seconds that reads the TTL index and purges expired documents. In our application code, because we check the count against the indexed timestamp window, active queries filter out expired documents immediately, while MongoDB permanently reclaims the disk space asynchronously.

---

### Feature 12: Wildcard `$**` Text Indexing

#### 1. What It Is
An inverted full-text search index created over the `audit_logs` collection using the `$**` wildcard path. This allows fast, search-engine-grade text querying (e.g., searching for `"INTRUSION"`, `"FIRE"`, or `"QUARANTINE"`) across any field in the document.

#### 2. Why We Built It (Engineering Rationale)
- **Unstructured Event Schemas:** Audit log entries have varied structures: fire events have `triggeringTemperature`, rate limit events have `windowSeconds`, and mode changes have `newMode`. Creating individual indexes on every possible field is impossible.
- **Logarithmic vs Linear Performance:** Using SQL `LIKE '%INTRUSION%'` or MongoDB regex `/$INTRUSION/` requires a full table scan ($O(N)$). A Wildcard Text Index builds a B-Tree inverted index of tokenized words, allowing $O(\log N)$ search speed.

#### 3. How It Is Implemented in Code
- **Index Creation:**
  - `backend/src/main/java/com/smarthome/db/DatabaseInitializer.java`:
    ```java
    MongoCollection<Document> auditLogs = database.getCollection(AUDIT_LOGS_COLLECTION);
    auditLogs.createIndex(
        Indexes.text("$**"),
        new IndexOptions().name("audit_logs_text")
    );
    ```
- **Query Execution:**
  - Searching using the text operator:
    ```java
    auditLogs.find(Filters.text("INTRUSION_LOCKDOWN")).sort(Sorts.descending("timestamp"));
    ```
- **Frontend Search Bar:**
  - In `frontend/src/components/TimelineModal.jsx`, typing into the search box sends the text query to the backend, returning instant matching results.

#### 4. Teacher Viva Questions & Answers
- **Q: What is an inverted index?**
  - **Answer:** An inverted index tokenizes text strings into words (stems) and maps each word to the list of document IDs where it appears. When searching for `"FIRE"`, MongoDB simply looks up the word in the dictionary tree rather than inspecting every document on disk.
- **Q: Can you have multiple text indexes on a single MongoDB collection?**
  - **Answer:** No. A collection can have at most **one** text index. However, that single text index can cover multiple fields or use the wildcard `$**` to index every string field in the document, which is exactly why we used `$**`.

---

### Feature 13: Aggregation Pipeline Analytics (`$setWindowFields` + `$merge`)

#### 1. What It Is
An analytical batch computation that processes historical telemetry natively inside the database. It partitions data by room sensor, sorts by timestamp, calculates a 5-minute rolling average temperature, and writes the results directly into an analytics collection (`sensor_averages_daily`) using the `$merge` stage.

#### 2. Why We Built It (Engineering Rationale)
- **Pushing Computation to the Data:** In naive applications, developers fetch thousands of records to the backend Java server, compute averages in a `for` loop, and write them back. This saturates network bandwidth and causes Java Out-Of-Memory (OOM) errors.
- **Modern Windowing in NoSQL:** MongoDB 5.0+ introduced `$setWindowFields`, bringing the full analytical power of SQL window functions (like `AVG() OVER (PARTITION BY ... ORDER BY ...)`) directly into document pipelines.

#### 3. How It Is Implemented in Code
- **File:** `backend/src/main/java/com/smarthome/analytics/AnalyticsEngine.java`
- **Code Pipeline:**
  ```java
  // 1. Filter temperature readings
  Document matchStage = new Document("$match", 
          new Document("deviceType", "TEMPERATURE_SENSOR"));

  // 2. Compute 5-minute sliding average partitioned by sensor
  Document setWindowFieldsStage = new Document("$setWindowFields", new Document()
          .append("partitionBy", "$sensorId")
          .append("sortBy", new Document("timestamp", 1))
          .append("output", new Document("rollingAvgTemp", new Document()
                  .append("$avg", "$temperature")
                  .append("window", new Document()
                          .append("range", List.of(-5, "current"))
                          .append("unit", "minute")))));

  // 3. Project clean fields
  Document projectStage = new Document("$project", new Document()
          .append("_id", 0)
          .append("sensorId", 1)
          .append("timestamp", 1)
          .append("temperature", 1)
          .append("rollingAvgTemp", 1)
          .append("computedAt", "$$NOW"));

  // 4. Merge results directly into destination collection
  Document mergeStage = new Document("$merge", new Document()
          .append("into", SENSOR_AVERAGES_COLLECTION)
          .append("on", List.of("sensorId", "timestamp"))
          .append("whenMatched", "replace")
          .append("whenNotMatched", "insert"));

  sensorData.aggregate(List.of(matchStage, setWindowFieldsStage, projectStage, mergeStage)).toCollection();
  ```

#### 4. Teacher Viva Questions & Answers
- **Q: What is the benefit of the `$merge` stage compared to `$out`?**
  - **Answer:** `$out` completely replaces the entire destination collection, wiping out existing records. `$merge` performs an incremental upsert (`whenMatched: replace`, `whenNotMatched: insert`), preserving historical data and allowing online updates without locking the target collection.

---

### Feature 14: Compound Indexing for High-Performance Sorting

#### 1. What It Is
A compound index defined as `sensorId: 1, timestamp: -1` on the `sensor_data` collection. It optimizes the most frequent query in the entire application: *"Get the latest 30 readings for a specific room sensor."*

#### 2. Why We Built It (Engineering Rationale)
- **Eliminating In-Memory Sorts:** Without this index, MongoDB must first find all documents for the room and then perform a slow in-memory sort on the timestamp. If the collection grows to millions of records, in-memory sorts exceed MongoDB's 100MB RAM limit and abort.
- **Index-Covered Queries:** With `sensorId ASC, timestamp DESC`, MongoDB traverses directly to the specific sensor branch in the B-Tree and reads the records in exact descending order, making the query execution $O(1)$ relative to total collection size.

#### 3. How It Is Implemented in Code
- **File:** `backend/src/main/java/com/smarthome/db/DatabaseInitializer.java`
- **Code Snippet:**
  ```java
  MongoCollection<Document> collection = database.getCollection(SENSOR_DATA_COLLECTION);
  collection.createIndex(
      Indexes.compoundIndex(
          Indexes.ascending("sensorId"),
          Indexes.descending("timestamp")
      ),
      new IndexOptions().name("sensorId_1_timestamp_-1")
  );
  ```
- **Application Query:**
  ```java
  // Fast indexed lookup
  collection.find(Filters.eq("sensorId", "TEMP_LIVING_ROOM"))
            .sort(Sorts.descending("timestamp"))
            .limit(30);
  ```

#### 4. Teacher Viva Questions & Answers
- **Q: Does the order of fields in a compound index matter? Why not `timestamp: -1, sensorId: 1`?**
  - **Answer:** **Yes, order matters immensely.** This follows the **ESR (Equality, Sort, Range)** rule of database indexing. In our queries, `sensorId` is an exact equality match (`sensorId == "TEMP_KITCHEN"`), and `timestamp` is the sort field. Placing `sensorId` first narrows the index search space immediately to that room's sub-tree, allowing the database to read pre-sorted timestamps directly.

---

## 🎯 Top 10 Viva Questions You Must Know By Heart

| # | Question | Key Concept to Mention in Your Answer |
|---|---|---|
| **1** | Why choose MongoDB over MySQL for an IoT project? | Heterogeneous sensor payloads (temperature vs lock states), high write throughput, native Change Streams, and horizontal scalability. |
| **2** | Isn't NoSQL eventual consistency unsafe for door locks? | Modern MongoDB (4.0+) supports multi-document ACID transactions with `SNAPSHOT` read concern and `MAJORITY` write concern. |
| **3** | How does Change Stream know a write occurred? | It tail-reads the MongoDB replication log (`oplog.rs`), which records all write operations in order. |
| **4** | Why not use WebSocket directly without saving to the DB? | Bypassing the DB causes data loss if the server restarts. Persisting first and listening to the Change Stream guarantees an immutable system of record. |
| **5** | How is the sliding window rate limiter implemented? | A MongoDB TTL index with `expireAfterSeconds = 10` on `sensor_rate_events`. No cron job or cleanup thread required. |
| **6** | What is the safety rule regarding door locks during a fire? | Exit routes (`isExitRoute: true`) must ALWAYS unlock to prevent trapping occupants. Non-exit interior doors stay locked to contain smoke. |
| **7** | What is the ESR rule in database indexing? | **E**quality first, **S**ort second, **R**ange third. That's why our compound index is `(sensorId: 1, timestamp: -1)`. |
| **8** | How does full-text search differ from regex search? | Regex performs an $O(N)$ table scan. Wildcard text search builds an inverted index of tokenized stems for $O(\log N)$ retrieval. |
| **9** | What is the advantage of `$setWindowFields`? | Enables SQL-style window analytical functions (rolling averages) executed natively in C++ inside the database engine. |
| **10** | How does the frontend stay synchronized without polling? | The Spring Boot backend listens to MongoDB Change Streams and pushes JSON payloads over a resilient WebSocket connection. |

---
*Created for Review 2 — Smart Home IoT Data Engine*
