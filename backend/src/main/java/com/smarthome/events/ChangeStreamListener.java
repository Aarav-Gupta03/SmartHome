package com.smarthome.events;

import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import com.smarthome.transactions.LockdownService;
import com.smarthome.websocket.LiveUpdateWebSocketHandler;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.List;

import static com.smarthome.AppConfig.FIRE_TEMP_THRESHOLD;
import static com.smarthome.AppConfig.SENSOR_DATA_COLLECTION;
import static com.smarthome.AppConfig.HOME_STATUS_COLLECTION;
import com.mongodb.client.model.Filters;

@Component
public class ChangeStreamListener {

    private static final Logger log = LoggerFactory.getLogger(ChangeStreamListener.class);

    private final MongoDatabase database;
    private final LockdownService lockdownService;
    private final LiveUpdateWebSocketHandler webSocketHandler;
    private final com.smarthome.simulator.DeviceHealthService deviceHealthService;

    private volatile boolean running = true;
    private MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor;
    private Thread listenerThread;

    public ChangeStreamListener(MongoDatabase database, LockdownService lockdownService,
                                 LiveUpdateWebSocketHandler webSocketHandler,
                                 com.smarthome.simulator.DeviceHealthService deviceHealthService) {
        this.database = database;
        this.lockdownService = lockdownService;
        this.webSocketHandler = webSocketHandler;
        this.deviceHealthService = deviceHealthService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        listenerThread = new Thread(this::watch, "change-stream-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void watch() {
        MongoCollection<Document> sensorDataCollection = database.getCollection(SENSOR_DATA_COLLECTION);
        log.info("Change stream listener starting - broadcasting all inserts, alerting on temperature > {}",
                FIRE_TEMP_THRESHOLD);

        List<Document> insertOnlyPipeline = List.of(
                new Document("$match", new Document("operationType", "insert"))
        );

        ChangeStreamIterable<Document> changeStream = sensorDataCollection
                .watch(insertOnlyPipeline);

        try (MongoChangeStreamCursor<ChangeStreamDocument<Document>> streamCursor = changeStream.cursor()) {
            this.cursor = streamCursor;
            while (running && streamCursor.hasNext()) {
                ChangeStreamDocument<Document> event = streamCursor.next();
                handleInsert(event);
            }
        } catch (Exception e) {
            if (running) {
                log.error("Change stream terminated unexpectedly: {}", e.getMessage(), e);
            } else {
                log.info("Change stream listener stopped.");
            }
        }
    }

    private void handleInsert(ChangeStreamDocument<Document> event) {
        Document doc = event.getFullDocument();
        if (doc == null) {
            return;
        }

        String sensorId = doc.getString("sensorId");

        // 0. Check health first — offline sensor readings must not update the dashboard.
        if (!deviceHealthService.isDeviceOnline(sensorId)) {
            log.warn("Ignoring event from offline sensor: {}", sensorId);
            return;
        }

        // Broadcast only after confirming the sensor is online.
        Document liveEnvelope = new Document()
                .append("type", "NORMAL_READING")
                .append("payload", doc);
        webSocketHandler.broadcast(liveEnvelope.toJson());

        // 1. Check Fire Condition
        Object tempObj = doc.get("temperature");
        if (tempObj instanceof Number) {
            double temperature = ((Number) tempObj).doubleValue();
            if (temperature > FIRE_TEMP_THRESHOLD) {
                log.error("!!!!! CRITICAL ALERT: FIRE CONDITION DETECTED - {} @ {} deg !!!!!", sensorId, temperature);
                lockdownService.handleFireEvent(sensorId, temperature);
                return;
            }
        }

        // 2. Check Intrusion Condition
        Object motionObj = doc.get("motionDetected");
        if (motionObj instanceof Boolean && (Boolean) motionObj) {
            MongoCollection<Document> homeStatusColl = database.getCollection(HOME_STATUS_COLLECTION);
            Document statusDoc = homeStatusColl.find(Filters.eq("_id", "singleton")).first();
            if (statusDoc != null && "AWAY".equals(statusDoc.getString("mode"))) {
                log.error("!!!!! CRITICAL ALERT: INTRUSION DETECTED IN AWAY MODE - {} !!!!!", sensorId);
                lockdownService.handleIntrusionEvent(sensorId);
            }
        }

        // 3. Check for unauthorized door unlock in AWAY mode.
        // We skip doors unlocked by the lockdown system itself — those carry a 'lockdownTriggeredAt' field.
        Object lockedObj = doc.get("locked");
        if (lockedObj instanceof Boolean) {
            boolean isLocked = (Boolean) lockedObj;
            
            MongoCollection<Document> homeStatusColl = database.getCollection(HOME_STATUS_COLLECTION);
            Document statusDoc = homeStatusColl.find(Filters.eq("_id", "singleton")).first();
            
            if (statusDoc != null && "AWAY".equals(statusDoc.getString("mode"))) {
                boolean isExitRoute = Boolean.TRUE.equals(doc.get("isExitRoute"));
                
                if (isLocked) {
                    // Dynamically protect interior doors locked during AWAY mode
                    if (!isExitRoute) {
                        java.util.List<String> lockedInteriorDoors = statusDoc.getList("lockedInteriorDoors", String.class);
                        if (lockedInteriorDoors == null) {
                            lockedInteriorDoors = new java.util.ArrayList<>();
                        }
                        if (!lockedInteriorDoors.contains(sensorId)) {
                            homeStatusColl.updateOne(
                                    Filters.eq("_id", "singleton"),
                                    com.mongodb.client.model.Updates.push("lockedInteriorDoors", sensorId)
                            );
                            log.info("Dynamically added {} to protected interior doors during AWAY mode", sensorId);
                        }
                    }
                } else if (doc.get("lockdownTriggeredAt") == null) {
                    // Door was unlocked
                    boolean unauthorized = false;
                    
                    if (isExitRoute) {
                        unauthorized = true;
                    } else {
                        java.util.List<String> lockedInteriorDoors = statusDoc.getList("lockedInteriorDoors", String.class);
                        if (lockedInteriorDoors != null && lockedInteriorDoors.contains(sensorId)) {
                            unauthorized = true;
                        }
                    }

                    if (unauthorized) {
                        log.warn("!!!!! SECURITY ALERT: Door unlocked while system is ARMED - {} !!!!!", sensorId);
                        database.getCollection(com.smarthome.AppConfig.AUDIT_LOGS_COLLECTION).insertOne(new Document()
                                .append("eventType", "UNAUTHORIZED_UNLOCK")
                                .append("triggeringSensorId", sensorId)
                                .append("lockId", sensorId)
                                .append("reason", "DOOR_UNLOCKED_WHILE_ARMED")
                                .append("timestamp", doc.get("timestamp") != null ? doc.get("timestamp") : java.util.Date.from(java.time.Instant.now())));
                        Document alertEvent = new Document()
                                .append("type", "UNAUTHORIZED_UNLOCK")
                                .append("lockId", sensorId)
                                .append("timestamp", doc.get("timestamp"));
                        webSocketHandler.broadcast(alertEvent.toJson());
                    }
                }
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (cursor != null) {
            cursor.close();
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
    }
}
