package com.smarthome.controller;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.smarthome.analytics.AnalyticsEngine;
import com.smarthome.ratelimit.RateLimiterService;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.smarthome.AppConfig.*;

@RestController
@RequestMapping("/api")
public class SensorController {

    private final MongoDatabase database;
    private final AnalyticsEngine analyticsEngine;
    private final RateLimiterService rateLimiterService;

    public SensorController(MongoDatabase database, AnalyticsEngine analyticsEngine, RateLimiterService rateLimiterService) {
        this.database = database;
        this.analyticsEngine = analyticsEngine;
        this.rateLimiterService = rateLimiterService;
    }

    @GetMapping("/sensors/latest")
    public List<Document> latest() {
        MongoCollection<Document> collection = database.getCollection(SENSOR_DATA_COLLECTION);
        List<Document> pipeline = List.of(
                new Document("$sort", new Document("timestamp", -1)),
                new Document("$group", new Document("_id", "$sensorId")
                        .append("doc", new Document("$first", "$$ROOT"))),
                new Document("$replaceRoot", new Document("newRoot", "$doc"))
        );
        List<Document> results = new ArrayList<>();
        collection.aggregate(pipeline).forEach(results::add);
        return results;
    }

    @GetMapping("/sensors/{sensorId}/history")
    public List<Document> history(@PathVariable String sensorId,
                                   @RequestParam(defaultValue = "30") int minutes) {
        MongoCollection<Document> collection = database.getCollection(SENSOR_DATA_COLLECTION);
        Date since = Date.from(Instant.now().minusSeconds(minutes * 60L));
        List<Document> results = new ArrayList<>();
        collection.find(Filters.and(Filters.eq("sensorId", sensorId), Filters.gte("timestamp", since)))
                .sort(Sorts.ascending("timestamp"))
                .into(results);
        return results;
    }

    @GetMapping("/analytics/averages")
    public List<Document> averages() {
        MongoCollection<Document> collection = database.getCollection(SENSOR_AVERAGES_COLLECTION);
        List<Document> results = new ArrayList<>();
        collection.find().sort(Sorts.descending("timestamp")).limit(100).into(results);
        return results;
    }

    @PostMapping("/analytics/run")
    public Document runAnalytics() {
        analyticsEngine.computeRollingTemperatureAverages();
        return new Document("status", "completed");
    }

    @GetMapping("/alerts")
    public List<Document> alerts(@RequestParam(defaultValue = "0") int skip, 
                                 @RequestParam(defaultValue = "20") int limit,
                                 @RequestParam(required = false) String q) {
        MongoCollection<Document> collection = database.getCollection(AUDIT_LOGS_COLLECTION);
        List<Document> results = new ArrayList<>();
        org.bson.conversions.Bson filter = (q != null && !q.trim().isEmpty()) ? com.mongodb.client.model.Filters.text(q) : new Document();
        collection.find(filter).sort(Sorts.descending("timestamp")).skip(skip).limit(limit).into(results);
        return results;
    }

    // PHASE 8: Rate Limit Endpoints

    /** Returns the set of currently quarantined sensor IDs. */
    @GetMapping("/rate-limits")
    public Map<String, Object> getRateLimits() {
        return Map.of("quarantined", rateLimiterService.getQuarantinedSensors());
    }

    /** Manually releases a quarantined sensor. */
    @DeleteMapping("/rate-limits/{sensorId}")
    public Map<String, String> releaseSensor(@PathVariable String sensorId) {
        rateLimiterService.releaseSensor(sensorId);
        return Map.of("status", "released", "sensorId", sensorId);
    }

    /** Returns historical quarantine counts per sensor. */
    @GetMapping("/rate-limits/stats")
    public List<Map<String, Object>> getRateLimitStats() {
        List<Map<String, Object>> stats = new ArrayList<>();
        String[] allSensors = concat(ROOM_SENSOR_IDS, LOCK_SENSOR_IDS, MOTION_SENSOR_IDS);
        for (String sensorId : allSensors) {
            long count = rateLimiterService.getQuarantineCount(sensorId);
            if (count > 0) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("sensorId", sensorId);
                entry.put("quarantineCount", count);
                stats.add(entry);
            }
        }
        return stats;
    }

    private static String[] concat(String[]... arrays) {
        int total = 0;
        for (String[] a : arrays) total += a.length;
        String[] result = new String[total];
        int i = 0;
        for (String[] a : arrays) { System.arraycopy(a, 0, result, i, a.length); i += a.length; }
        return result;
    }
}
