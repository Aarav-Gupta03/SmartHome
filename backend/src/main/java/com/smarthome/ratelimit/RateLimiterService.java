package com.smarthome.ratelimit;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.smarthome.websocket.LiveUpdateWebSocketHandler;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static com.smarthome.AppConfig.*;

@Service
@EnableScheduling
public class RateLimiterService {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    private final MongoCollection<Document> rateEventsCollection;
    private final MongoCollection<Document> auditLogsCollection;
    private final LiveUpdateWebSocketHandler webSocketHandler;
    private final Set<String> quarantinedSensors = ConcurrentHashMap.newKeySet();

    public RateLimiterService(MongoDatabase database, LiveUpdateWebSocketHandler webSocketHandler) {
        this.rateEventsCollection = database.getCollection(SENSOR_RATE_EVENTS_COLLECTION);
        this.auditLogsCollection  = database.getCollection(AUDIT_LOGS_COLLECTION);
        this.webSocketHandler     = webSocketHandler;
    }

    public boolean checkAndRecord(String sensorId, boolean isSafetyCritical) {
        Date windowStart = Date.from(Instant.now().minusSeconds(RATE_LIMIT_WINDOW_SECONDS));
        long count = rateEventsCollection.countDocuments(
                Filters.and(Filters.eq("sensorId", sensorId), Filters.gte("createdAt", windowStart))
        );
        if (count >= RATE_LIMIT_MAX_EVENTS) {
            if (isSafetyCritical) {
                // Safety override: allow the event through and record it.
                rateEventsCollection.insertOne(new Document().append("sensorId", sensorId).append("createdAt", Date.from(Instant.now())));
                log.warn("RATE LIMIT SAFETY OVERRIDE: {} is quarantined but safety-critical event -- allowing.", sensorId);
                auditLogsCollection.insertOne(new Document().append("eventType", "RATE_LIMIT_SAFETY_OVERRIDE").append("sensorId", sensorId).append("eventCount", count).append("timestamp", Date.from(Instant.now())));
                return true;
            }
            if (!quarantinedSensors.contains(sensorId)) quarantineSensor(sensorId, count);
            return false;
        }
        // Under the limit — record the event and allow.
        rateEventsCollection.insertOne(new Document().append("sensorId", sensorId).append("createdAt", Date.from(Instant.now())));
        return true;
    }

    private void quarantineSensor(String sensorId, long eventCount) {
        quarantinedSensors.add(sensorId);
        log.warn("SENSOR QUARANTINED: {} exceeded rate limit ({} events in {}s window)", sensorId, eventCount, RATE_LIMIT_WINDOW_SECONDS);
        auditLogsCollection.insertOne(new Document().append("eventType", "RATE_LIMIT_QUARANTINE").append("sensorId", sensorId).append("eventCount", eventCount).append("windowSeconds", RATE_LIMIT_WINDOW_SECONDS).append("timestamp", Date.from(Instant.now())));
        webSocketHandler.broadcast(new Document().append("type", "SENSOR_RATE_LIMITED").append("sensorId", sensorId).append("eventCount", eventCount).append("windowSeconds", RATE_LIMIT_WINDOW_SECONDS).toJson());
    }

    public void releaseSensor(String sensorId) {
        if (!quarantinedSensors.contains(sensorId)) return;
        quarantinedSensors.remove(sensorId);
        rateEventsCollection.deleteMany(Filters.eq("sensorId", sensorId));
        log.info("Sensor {} manually released from quarantine.", sensorId);
        webSocketHandler.broadcast(new Document().append("type", "SENSOR_RELEASED").append("sensorId", sensorId).append("releaseReason", "MANUAL").toJson());
    }

    @Scheduled(fixedDelay = 15000)
    public void autoReleaseCheck() {
        Date windowStart = Date.from(Instant.now().minusSeconds(RATE_LIMIT_WINDOW_SECONDS));
        for (String sensorId : quarantinedSensors) {
            long count = rateEventsCollection.countDocuments(
                    Filters.and(Filters.eq("sensorId", sensorId), Filters.gte("createdAt", windowStart))
            );
            // Release when count drops below the max (consistent with the >= check in checkAndRecord).
            if (count < RATE_LIMIT_MAX_EVENTS) {
                quarantinedSensors.remove(sensorId);
                log.info("Sensor {} auto-released from quarantine (TTL window expired, count={})", sensorId, count);
                webSocketHandler.broadcast(new Document().append("type", "SENSOR_RELEASED").append("sensorId", sensorId).append("releaseReason", "AUTO_TTL_EXPIRED").toJson());
            }
        }
    }

    public Set<String> getQuarantinedSensors() { return quarantinedSensors; }
    public long getQuarantineCount(String sensorId) {
        return auditLogsCollection.countDocuments(Filters.and(Filters.eq("eventType", "RATE_LIMIT_QUARANTINE"), Filters.eq("sensorId", sensorId)));
    }
}