package com.smarthome.transactions;

import com.mongodb.ClientSessionOptions;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.smarthome.websocket.LiveUpdateWebSocketHandler;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.smarthome.AppConfig.AUDIT_LOGS_COLLECTION;
import static com.smarthome.AppConfig.FIRE_TEMP_THRESHOLD;
import static com.smarthome.AppConfig.SENSOR_DATA_COLLECTION;

/**
 * PHASE 2 — Fire vs. Intrusion split (safety-correct implementation).
 *
 * handleFireEvent():
 *   - Unlocks all isExitRoute=true doors (evacuation paths).
 *   - Locks all isExitRoute=false doors (contain the fire).
 *   - Records FAILED_TO_UNLOCK_EXIT audit entry + dashboard alert for any
 *     exit-route lock that is offline and therefore could not be actuated.
 *   - Writes FIRE_EVACUATION audit log atomically with all lock state changes.
 *
 * handleIntrusionEvent():
 *   - Locks every door regardless of isExitRoute.
 *   - Writes INTRUSION_LOCKDOWN audit log atomically.
 *
 * Both handlers:
 *   - Use WriteConcern.MAJORITY / ReadConcern.SNAPSHOT transactions.
 *   - Set a lockdown-in-progress flag so the 5-second heartbeat cannot replay
 *     stale lock state between the transaction commit and the in-memory sync.
 *   - Sync in-memory latestState ONLY for online locks (matches what the
 *     transaction actually wrote — offline locks are not synced).
 */
@Service
public class LockdownService {

    private static final Logger log = LoggerFactory.getLogger(LockdownService.class);

    private final MongoClient client;
    private final MongoDatabase database;
    private final LiveUpdateWebSocketHandler webSocketHandler;
    private final com.smarthome.simulator.DeviceHealthService deviceHealthService;
    private final com.smarthome.simulator.DeviceSimulationService simulationService;

    public LockdownService(MongoClient client, MongoDatabase database, LiveUpdateWebSocketHandler webSocketHandler,
                           com.smarthome.simulator.DeviceHealthService deviceHealthService,
                           com.smarthome.simulator.DeviceSimulationService simulationService) {
        this.client = client;
        this.database = database;
        this.webSocketHandler = webSocketHandler;
        this.deviceHealthService = deviceHealthService;
        this.simulationService = simulationService;
    }

    public void handleFireEvent(String triggeringSensorId, double triggeringTemperature) {
        MongoCollection<Document> auditLogs = database.getCollection(AUDIT_LOGS_COLLECTION);
        MongoCollection<Document> sensorData = database.getCollection(SENSOR_DATA_COLLECTION);

        ClientSessionOptions sessionOptions = ClientSessionOptions.builder().causallyConsistent(true).build();
        TransactionOptions transactionOptions = TransactionOptions.builder()
                .readConcern(ReadConcern.SNAPSHOT)
                .writeConcern(WriteConcern.MAJORITY)
                .build();

        Document fireEvent = new Document()
                .append("eventType", "FIRE_EVACUATION")
                .append("reason", "FIRE_TEMPERATURE_THRESHOLD_EXCEEDED")
                .append("triggeringSensorId", triggeringSensorId)
                .append("triggeringTemperature", triggeringTemperature)
                .append("threshold", FIRE_TEMP_THRESHOLD)
                .append("timestamp", Date.from(Instant.now()));

        // Prevent the heartbeat from replaying stale lock state between commit and sync.
        simulationService.setLockdownInProgress(true);

        // Track exit-route locks that are offline — we'll alert the dashboard after the transaction.
        List<String> offlineExitLocks = new ArrayList<>();

        try (ClientSession session = client.startSession(sessionOptions)) {
            session.withTransaction(() -> {
                auditLogs.insertOne(session, fireEvent);

                for (var entry : com.smarthome.AppConfig.LOCK_EXIT_ROUTES.entrySet()) {
                    String lockId = entry.getKey();
                    boolean isExitRoute = entry.getValue();

                    if (!deviceHealthService.isDeviceOnline(lockId)) {
                        log.warn("Skipping offline lock during fire evacuation: {}", lockId);
                        if (isExitRoute) {
                            // Critical: an exit route could not be unlocked — record it atomically.
                            offlineExitLocks.add(lockId);
                            auditLogs.insertOne(session, new Document()
                                    .append("eventType", "FAILED_TO_UNLOCK_EXIT")
                                    .append("lockId", lockId)
                                    .append("reason", "DEVICE_OFFLINE")
                                    .append("timestamp", Date.from(Instant.now())));
                        }
                        continue;
                    }

                    boolean locked = !isExitRoute; // exit routes UNLOCKED, interior LOCKED

                    Document lockDoc = new Document()
                            .append("sensorId", lockId)
                            .append("timestamp", Date.from(Instant.now()))
                            .append("locked", locked)
                            .append("deviceType", "SMART_LOCK")
                            .append("isExitRoute", isExitRoute)
                            .append("batteryLevel", simulationService.getDeviceBattery(lockId))
                            .append("lockdownTriggeredAt", Date.from(Instant.now()));
                    sensorData.insertOne(session, lockDoc);
                }

                log.warn("FIRE TRANSACTION: audit_logs +1, lock states written (exits unlocked, interior locked).");
                return null;
            }, transactionOptions);

            log.warn("Fire evacuation transaction committed. Exit routes unlocked.");

            // Sync in-memory state ONLY for locks the transaction actually processed (online only).
            for (var entry : com.smarthome.AppConfig.LOCK_EXIT_ROUTES.entrySet()) {
                String lockId = entry.getKey();
                if (!deviceHealthService.isDeviceOnline(lockId)) continue;
                boolean isExitRoute = entry.getValue();
                simulationService.syncLockLatestState(lockId, !isExitRoute);
            }

            webSocketHandler.broadcast(new Document()
                    .append("type", "FIRE")
                    .append("payload", fireEvent)
                    .toJson());

            // Alert the dashboard for every exit route that could not be unlocked.
            for (String offlineLockId : offlineExitLocks) {
                log.error("CRITICAL: Exit route {} could not be unlocked — device is offline!", offlineLockId);
                webSocketHandler.broadcast(new Document()
                        .append("type", "EXIT_LOCK_FAILURE")
                        .append("lockId", offlineLockId)
                        .append("message", "Exit route could not be unlocked — device offline")
                        .toJson());
            }
        } catch (Exception e) {
            log.error("Fire transaction FAILED and rolled back: {}", e.getMessage(), e);
            webSocketHandler.broadcast(new Document()
                    .append("type", "LOCKDOWN_FAILED")
                    .append("message", e.getMessage())
                    .toJson());
        } finally {
            simulationService.setLockdownInProgress(false);
        }
    }

    public void handleIntrusionEvent(String triggeringSensorId) {
        MongoCollection<Document> auditLogs = database.getCollection(AUDIT_LOGS_COLLECTION);
        MongoCollection<Document> sensorData = database.getCollection(SENSOR_DATA_COLLECTION);

        ClientSessionOptions sessionOptions = ClientSessionOptions.builder().causallyConsistent(true).build();
        TransactionOptions transactionOptions = TransactionOptions.builder()
                .readConcern(ReadConcern.SNAPSHOT)
                .writeConcern(WriteConcern.MAJORITY)
                .build();

        Document intrusionEvent = new Document()
                .append("eventType", "INTRUSION_LOCKDOWN")
                .append("reason", "MOTION_DETECTED_WHILE_AWAY")
                .append("triggeringSensorId", triggeringSensorId)
                .append("timestamp", Date.from(Instant.now()));

        // Prevent the heartbeat from replaying stale lock state between commit and sync.
        simulationService.setLockdownInProgress(true);

        try (ClientSession session = client.startSession(sessionOptions)) {
            session.withTransaction(() -> {
                auditLogs.insertOne(session, intrusionEvent);

                for (var entry : com.smarthome.AppConfig.LOCK_EXIT_ROUTES.entrySet()) {
                    String lockId = entry.getKey();
                    if (!deviceHealthService.isDeviceOnline(lockId)) {
                        log.warn("Skipping offline lock during intrusion lockdown: {}", lockId);
                        continue;
                    }
                    boolean isExitRoute = entry.getValue();

                    Document lockDoc = new Document()
                            .append("sensorId", lockId)
                            .append("timestamp", Date.from(Instant.now()))
                            .append("locked", true)
                            .append("deviceType", "SMART_LOCK")
                            .append("isExitRoute", isExitRoute)
                            .append("batteryLevel", simulationService.getDeviceBattery(lockId))
                            .append("lockdownTriggeredAt", Date.from(Instant.now()));
                    sensorData.insertOne(session, lockDoc);
                }

                log.warn("INTRUSION TRANSACTION: audit_logs +1, lock states written (all doors locked).");
                return null;
            }, transactionOptions);

            log.warn("Intrusion lockdown transaction committed. All doors locked.");

            // Sync in-memory state ONLY for locks the transaction actually processed (online only).
            for (String lockId : com.smarthome.AppConfig.LOCK_EXIT_ROUTES.keySet()) {
                if (!deviceHealthService.isDeviceOnline(lockId)) continue;
                simulationService.syncLockLatestState(lockId, true);
            }

            webSocketHandler.broadcast(new Document()
                    .append("type", "INTRUSION")
                    .append("payload", intrusionEvent)
                    .toJson());
        } catch (Exception e) {
            log.error("Intrusion transaction FAILED and rolled back: {}", e.getMessage(), e);
            webSocketHandler.broadcast(new Document()
                    .append("type", "LOCKDOWN_FAILED")
                    .append("message", e.getMessage())
                    .toJson());
        } finally {
            simulationService.setLockdownInProgress(false);
        }
    }
}
