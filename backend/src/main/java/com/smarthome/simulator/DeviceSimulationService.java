package com.smarthome.simulator;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.smarthome.ratelimit.RateLimiterService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.smarthome.AppConfig.*;

@Service
public class DeviceSimulationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulationService.class);

    private final MongoCollection<Document> sensorDataCollection;
    private RateLimiterService rateLimiterService; // Set via setter to avoid circular dependency

    private final Map<String, Boolean> roomEnabled = new ConcurrentHashMap<>();
    private final AtomicInteger autoIntervalSeconds = new AtomicInteger(5);
    
    private final Map<String, Double> batteryLevels = new ConcurrentHashMap<>();
    private final Map<String, Boolean> devicePower = new ConcurrentHashMap<>();
    private final Map<String, Document> latestState = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "auto-simulator");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> autoTask;
    private volatile ScheduledFuture<?> heartbeatTask;
    /** Set to true during a lockdown transaction to block heartbeat from replaying stale lock state. */
    private volatile boolean lockdownInProgress = false;

    public DeviceSimulationService(MongoDatabase database) {
        this.sensorDataCollection = database.getCollection(SENSOR_DATA_COLLECTION);
        for (String roomId : ROOM_SENSOR_IDS) {
            roomEnabled.put(roomId, true);
            batteryLevels.put(roomId, 100.0);
            devicePower.put(roomId, true);
        }
        for (String lockId : LOCK_SENSOR_IDS) {
            batteryLevels.put(lockId, 100.0);
            devicePower.put(lockId, true);
        }
        for (String motionId : MOTION_SENSOR_IDS) {
            batteryLevels.put(motionId, 100.0);
            devicePower.put(motionId, true);
        }
        
        heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeatTick, 0, 5, TimeUnit.SECONDS);
    }

    public void setDevicePower(String deviceId, boolean powered) {
        devicePower.put(deviceId, powered);
        if (!powered) {
            // Force an update to the DB with timestamp shifted back so it immediately triggers the silence timeout
            Document latest = latestState.get(deviceId);
            if (latest != null) {
                Document doc = new Document(latest);
                doc.remove("_id");
                doc.put("timestamp", Date.from(Instant.now().minusSeconds(20)));
                sensorDataCollection.insertOne(doc);
                latestState.put(deviceId, doc);
            }
        } else {
            // Instantly write a heartbeat to bring it back online
            forceImmediateHeartbeat(deviceId);
        }
    }

    public void setDeviceBattery(String deviceId, double battery) {
        batteryLevels.put(deviceId, battery);
        forceImmediateHeartbeat(deviceId);
    }

    public double getDeviceBattery(String deviceId) {
        return batteryLevels.getOrDefault(deviceId, DEFAULT_BATTERY_LEVEL);
    }

    private void forceImmediateHeartbeat(String deviceId) {
        Document latest = latestState.get(deviceId);
        if (latest != null && Boolean.TRUE.equals(devicePower.get(deviceId))) {
            Document doc = new Document(latest);
            doc.remove("_id");
            doc.put("timestamp", Date.from(Instant.now()));
            doc.put("batteryLevel", batteryLevels.getOrDefault(deviceId, 100.0));
            sensorDataCollection.insertOne(doc);
            latestState.put(deviceId, doc);
        }
    }

    public void setRateLimiterService(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * Called by LockdownService before and after a transaction to prevent the heartbeat
     * from replaying stale lock state between the transaction commit and in-memory sync.
     */
    public void setLockdownInProgress(boolean inProgress) {
        this.lockdownInProgress = inProgress;
    }

    public Document generateRoomReading(String roomId, Double overrideTemp, boolean forceFire) {
        // PHASE 8: check rate limit before writing. Fire readings use safety override.
        if (rateLimiterService != null && !rateLimiterService.checkAndRecord(roomId, forceFire)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Sensor " + roomId + " is rate-limited. Too many writes in the sliding window.");
        }

        double temperature;
        if (forceFire) {
            temperature = FIRE_TEMP_READING;
        } else if (overrideTemp != null) {
            temperature = overrideTemp;
        } else {
            temperature = ThreadLocalRandom.current().nextDouble(TEMP_MIN, TEMP_MAX);
        }

        Document doc = new Document()
                .append("sensorId", roomId)
                .append("timestamp", Date.from(Instant.now()))
                .append("temperature", temperature)
                .append("deviceType", "TEMPERATURE_SENSOR")
                .append("batteryLevel", batteryLevels.getOrDefault(roomId, DEFAULT_BATTERY_LEVEL));

        sensorDataCollection.insertOne(doc);

        // IMPORTANT: if this was a fire reading, store a SAFE temperature in latestState
        // so heartbeats don't keep re-triggering the fire alarm every 5 seconds.
        if (forceFire) {
            Document safeDoc = new Document(doc);
            safeDoc.put("temperature", TEMP_MIN + 5.0);
            latestState.put(roomId, safeDoc);
        } else {
            latestState.put(roomId, doc);
        }

        log.info("Manual reading: {} -> {} deg{}", roomId, temperature, forceFire ? " (FIRE TRIGGERED)" : "");
        return doc;
    }

    public Document setLockState(String lockId, boolean locked) {
        // PHASE 8: check rate limit before writing.
        if (rateLimiterService != null && !rateLimiterService.checkAndRecord(lockId, false)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Sensor " + lockId + " is rate-limited. Too many writes in the sliding window.");
        }

        // PHASE 1: every lock document now carries isExitRoute, since Phase 2's
        // fire-vs-intrusion logic needs it on the document itself (time-series
        // documents are immutable inserts, not updatable "current state" rows).
        boolean isExitRoute = LOCK_EXIT_ROUTES.getOrDefault(lockId, false);

        Document doc = new Document()
                .append("sensorId", lockId)
                .append("timestamp", Date.from(Instant.now()))
                .append("locked", locked)
                .append("deviceType", "SMART_LOCK")
                .append("isExitRoute", isExitRoute)
                .append("batteryLevel", batteryLevels.getOrDefault(lockId, DEFAULT_BATTERY_LEVEL));

        sensorDataCollection.insertOne(doc);
        latestState.put(lockId, doc);
        log.info("Manual lock event: {} -> locked={} (isExitRoute={})", lockId, locked, isExitRoute);
        return doc;
    }

    /**
     * Sync the in-memory latestState for a lock AFTER a transaction has already written
     * the document to MongoDB. This prevents the heartbeat from overwriting the
     * transaction's lock state with the old (stale) in-memory value.
     * Does NOT insert to the database — caller already did that inside the transaction.
     */
    public void syncLockLatestState(String lockId, boolean locked) {
        boolean isExitRoute = LOCK_EXIT_ROUTES.getOrDefault(lockId, false);
        Document doc = new Document()
                .append("sensorId", lockId)
                .append("timestamp", Date.from(Instant.now()))
                .append("locked", locked)
                .append("deviceType", "SMART_LOCK")
                .append("isExitRoute", isExitRoute)
                .append("batteryLevel", batteryLevels.getOrDefault(lockId, DEFAULT_BATTERY_LEVEL))
                .append("lockdownTriggeredAt", Date.from(Instant.now()));
        latestState.put(lockId, doc);
        log.info("Synced latestState for {} -> locked={}", lockId, locked);
    }

    public Document triggerMotion(String motionId, boolean detected) {
        // PHASE 8: check rate limit before writing.
        if (rateLimiterService != null && !rateLimiterService.checkAndRecord(motionId, false)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Sensor " + motionId + " is rate-limited. Too many writes in the sliding window.");
        }

        Document doc = new Document()
                .append("sensorId", motionId)
                .append("timestamp", Date.from(Instant.now()))
                .append("motionDetected", detected)
                .append("deviceType", "MOTION_SENSOR")
                .append("batteryLevel", batteryLevels.getOrDefault(motionId, DEFAULT_BATTERY_LEVEL));

        sensorDataCollection.insertOne(doc);

        // IMPORTANT: always store latestState with motionDetected=false so the heartbeat
        // doesn't keep re-triggering the intrusion alarm on every tick.
        Document idleDoc = new Document(doc);
        idleDoc.put("motionDetected", false);
        latestState.put(motionId, idleDoc);

        log.info("Manual motion event: {} -> detected={}", motionId, detected);
        return doc;
    }

    public synchronized void startAutoMode(Integer intervalSeconds) {
        if (intervalSeconds != null && intervalSeconds > 0) {
            autoIntervalSeconds.set(intervalSeconds);
        }
        if (autoTask != null && !autoTask.isDone()) {
            autoTask.cancel(false);
        }
        autoTask = scheduler.scheduleAtFixedRate(this::autoTick, 0,
                autoIntervalSeconds.get(), TimeUnit.SECONDS);
        log.info("Auto mode started (interval={}s).", autoIntervalSeconds.get());
    }

    public synchronized void stopAutoMode() {
        if (autoTask != null) {
            autoTask.cancel(false);
            autoTask = null;
        }
        log.info("Auto mode stopped.");
    }

    public boolean isAutoModeRunning() {
        return autoTask != null && !autoTask.isDone();
    }

    public void setRoomEnabled(String roomId, boolean enabled) {
        roomEnabled.put(roomId, enabled);
    }

    public Map<String, Object> getState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("autoRunning", isAutoModeRunning());
        state.put("intervalSeconds", autoIntervalSeconds.get());
        state.put("roomEnabled", new LinkedHashMap<>(roomEnabled));
        state.put("batteryLevels", new LinkedHashMap<>(batteryLevels));
        state.put("devicePower", new LinkedHashMap<>(devicePower));
        return state;
    }

    public java.util.List<String> getLockedInteriorDoors() {
        java.util.List<String> lockedInteriorDoors = new java.util.ArrayList<>();
        for (String lockId : com.smarthome.AppConfig.LOCK_SENSOR_IDS) {
            if (!Boolean.TRUE.equals(com.smarthome.AppConfig.LOCK_EXIT_ROUTES.get(lockId))) {
                Document state = latestState.get(lockId);
                if (state != null && Boolean.TRUE.equals(state.get("locked"))) {
                    lockedInteriorDoors.add(lockId);
                }
            }
        }
        return lockedInteriorDoors;
    }

    private void autoTick() {
        // Try-catch is INSIDE the loop so one rate-limited/offline room cannot abort the entire tick.
        for (Map.Entry<String, Boolean> entry : roomEnabled.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                try {
                    boolean spontaneousFire = ThreadLocalRandom.current().nextDouble() < 0.01;
                    generateRoomReading(entry.getKey(), null, spontaneousFire);
                } catch (Exception e) {
                    log.warn("Auto-mode tick skipped for {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }
    }

    private void heartbeatTick() {
        // Skip entirely while a lockdown transaction is in flight to prevent
        // replaying stale lock state between transaction commit and in-memory sync.
        if (lockdownInProgress) {
            log.debug("Heartbeat skipped — lockdown transaction in progress.");
            return;
        }
        try {
            for (String sensorId : devicePower.keySet()) {
                if (!Boolean.TRUE.equals(devicePower.get(sensorId))) continue;
                
                double currentBattery = batteryLevels.getOrDefault(sensorId, 100.0);
                if (currentBattery > 0) {
                    currentBattery -= 0.5; // Drain battery
                    batteryLevels.put(sensorId, Math.max(0.0, currentBattery));
                }
                
                if (currentBattery > 0) {
                    Document latest = latestState.get(sensorId);
                    if (latest != null) {
                        Document heartbeat = new Document(latest);
                        heartbeat.remove("_id"); // remove the old ObjectID
                        heartbeat.put("timestamp", Date.from(Instant.now()));
                        heartbeat.put("batteryLevel", currentBattery);
                        // Safety: heartbeats NEVER replay motionDetected=true — that would
                        // cause an infinite intrusion-lockdown loop every 5 seconds.
                        if (Boolean.TRUE.equals(heartbeat.get("motionDetected"))) {
                            heartbeat.put("motionDetected", false);
                        }
                        sensorDataCollection.insertOne(heartbeat);
                        latestState.put(sensorId, heartbeat);
                    } else {
                        // Bootstrap if no manual event was fired yet
                        if (sensorId.startsWith("TEMP_")) {
                            generateRoomReading(sensorId, 72.0, false);
                        } else if (sensorId.startsWith("LOCK_")) {
                            setLockState(sensorId, true); // safe default: locked (prevents open-door-on-restart bug)
                        } else if (sensorId.startsWith("MOTION_")) {
                            triggerMotion(sensorId, false);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Heartbeat tick failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public Set<String> getRoomIds() {
        return roomEnabled.keySet();
    }
}
