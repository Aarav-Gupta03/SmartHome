# Smart Home IoT Data Engine
## DBMS Project Abstract

This project demonstrates a full-stack IoT Smart Home Data Engine built on **MongoDB**, **Spring Boot (Java 11)**, and **React + Vite**. It models a real-time smart home environment using a time-series database design to handle high-frequency sensor writes, complex aggregation, and safety-critical background processing.

### Key Database Features and Architecture

The system utilizes several advanced MongoDB features to solve common IoT challenges:

1. **Time-Series Collections**: Sensor readings are stored in a specialized Time-Series collection optimized for high-volume, append-only sensor data.
2. **Change Streams for Event-Driven Architecture**: Instead of polling for alerts, the backend listens to a MongoDB Change Stream on the `sensor_data` collection. When critical events (like fire or unauthorized access) are written, the change stream instantly triggers safety procedures.
3. **Multi-Document ACID Transactions**: When a fire is detected, the system executes a lockdown transaction that ensures all doors are updated atomically. If one door update fails, the entire transaction rolls back, preventing a partially-locked state during an emergency.
4. **Offline Resilience & Data Reconciliation**: We model network partitions. When a sensor drops offline, its updates are queued. When it reconnects, the backend uses `updateMany` to mark the queued historical readings as "reconciled", resolving split-brain scenarios.

### 1. MongoDB Text Index & Tokenizer Behaviour

To support searching through the system's `audit_logs`, we implemented a wildcard `$**` **Text Index**.

- **Tokenizer Quirks**: MongoDB's text tokenizer treats underscores (`_`) as word characters, not separators.
  - A log entry for `INTRUSION_LOCKDOWN` is stored as a single token in the index, not two separate words.
  - Searching for `intrusion_lockdown` works instantly. Searching for `intrusion` alone returns nothing.
- **Performance vs Regex**: This is a critical architectural decision compared to using a regex (`$regex`) scan. A regex search performs a full collection scan with no index (O(N) complexity). The Text Index allows logarithmic (O(log N)) lookups.
- **Why it matters**: *"We deliberately chose a Text Index over regex to demonstrate database-aware design. A regex scan on millions of audit rows is a classic IoT anti-pattern."*

### 2. Rate Limiter — Architecture & Sliding Window

To prevent malicious or malfunctioning sensors from flooding the database, we implemented a sliding-window rate limiter using a **MongoDB TTL (Time-To-Live) Index**.

#### Architecture Flowchart

```mermaid
flowchart TD
    A[Sensor Write Attempt] --> B[RateLimiterService.checkAndRecord()]
    B --> C{Is Event Safety Critical?}
    
    C -- Yes (Fire/Intrusion) --> D[ALLOW WRITE<br/>Override Limit]
    C -- No --> E[Insert into sensor_rate_events]
    
    E --> F[Count events for sensorId]
    F --> G{Count > MAX_LIMIT?}
    
    G -- No --> H[ALLOW WRITE]
    G -- Yes --> I[BLOCK WRITE<br/>Quarantine Sensor]
    
    subgraph MongoDB
    J[(sensor_rate_events)]
    K[TTL Index on createdAt]
    J --> K
    K --> L(Auto-Delete after 10s)
    end
```

#### Key Mechanics:
- **Self-Cleaning Window**: Every write attempt inserts a tiny document into `sensor_rate_events`. A TTL index on `createdAt` automatically deletes documents older than 10 seconds. The count of documents for a sensor *is* its write rate.
- **No Cron Jobs**: MongoDB natively handles the cleanup, eliminating the need for application-side cron jobs to prune old data.
- **Safety Overrides**: Fire and intrusion events bypass the rate limit completely. The system will never block a critical emergency alert just because a sensor is writing too fast.
- **Auto-Release**: A lightweight background task checks if the TTL window has naturally expired for quarantined sensors, automatically restoring service once the flood stops.
