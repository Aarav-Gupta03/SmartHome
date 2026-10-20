package com.smarthome.analytics;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.smarthome.AppConfig.SENSOR_AVERAGES_COLLECTION;
import static com.smarthome.AppConfig.SENSOR_DATA_COLLECTION;

@Service
public class AnalyticsEngine {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEngine.class);
    private final MongoDatabase database;

    public AnalyticsEngine(MongoDatabase database) {
        this.database = database;
    }

    public void computeRollingTemperatureAverages() {
        MongoCollection<Document> sensorData = database.getCollection(SENSOR_DATA_COLLECTION);

        Document matchStage = new Document("$match", new Document("deviceType", "TEMPERATURE_SENSOR"));

        Document setWindowFieldsStage = new Document("$setWindowFields", new Document()
                .append("partitionBy", "$sensorId")
                .append("sortBy", new Document("timestamp", 1))
                .append("output", new Document("rollingAvgTemp", new Document()
                        .append("$avg", "$temperature")
                        .append("window", new Document()
                                .append("range", List.of(-5, "current"))
                                .append("unit", "minute")))));

        Document projectStage = new Document("$project", new Document()
                .append("_id", 0)
                .append("sensorId", 1)
                .append("timestamp", 1)
                .append("temperature", 1)
                .append("rollingAvgTemp", 1)
                .append("computedAt", "$$NOW"));

        Document mergeStage = new Document("$merge", new Document()
                .append("into", SENSOR_AVERAGES_COLLECTION)
                .append("on", List.of("sensorId", "timestamp"))
                .append("whenMatched", "replace")
                .append("whenNotMatched", "insert"));

        List<Document> pipeline = List.of(matchStage, setWindowFieldsStage, projectStage, mergeStage);

        log.info("Running rolling average aggregation -> '{}'.", SENSOR_AVERAGES_COLLECTION);
        sensorData.aggregate(pipeline).first();
    }
}
