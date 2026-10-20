package com.smarthome.db;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.TimeSeriesGranularity;
import com.mongodb.client.model.TimeSeriesOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.smarthome.AppConfig.HOME_STATUS_COLLECTION;
import static com.smarthome.AppConfig.SENSOR_DATA_COLLECTION;
import static com.smarthome.AppConfig.TTL_EXPIRE_AFTER_DAYS;

public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final MongoDatabase database;

    public DatabaseInitializer(MongoDatabase database) {
        this.database = database;
    }

    public void initialize() {
        if (!collectionExists(SENSOR_DATA_COLLECTION)) {
            createSensorDataCollection();
        } else {
            log.info("Collection '{}' already exists - skipping creation.", SENSOR_DATA_COLLECTION);
        }
        ensureIndexes();
        ensureHomeStatusDocument();
    }

    private boolean collectionExists(String name) {
        for (String existing : database.listCollectionNames()) {
            if (existing.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void createSensorDataCollection() {
        log.info("Creating standard collection '{}' ...", SENSOR_DATA_COLLECTION);

        ValidationOptions validationOptions = new ValidationOptions()
                .validator(buildSchemaValidator())
                .validationLevel(ValidationLevel.MODERATE)
                .validationAction(ValidationAction.WARN);

        CreateCollectionOptions options = new CreateCollectionOptions()
                .validationOptions(validationOptions);

        try {
            database.createCollection(SENSOR_DATA_COLLECTION, options);
            log.info("Standard collection '{}' created.", SENSOR_DATA_COLLECTION);
        } catch (MongoCommandException e) {
            log.warn("Collection creation failed ({}). Retrying without validator...",
                    e.getErrorMessage());
            database.createCollection(SENSOR_DATA_COLLECTION);
            log.warn("Created '{}' WITHOUT schema validation.", SENSOR_DATA_COLLECTION);
        }
    }

    private Document buildSchemaValidator() {
        Document properties = new Document()
                .append("sensorId", new Document("bsonType", "string"))
                .append("timestamp", new Document("bsonType", "date"))
                .append("temperature", new Document("bsonType", "double"))
                .append("locked", new Document("bsonType", "bool"))
                .append("motionDetected", new Document("bsonType", "bool"))
                // PHASE 1 additions:
                .append("isExitRoute", new Document()
                        .append("bsonType", "bool")
                        .append("description", "present on SMART_LOCK documents; true if this door is an evacuation route"))
                .append("batteryLevel", new Document()
                        .append("bsonType", List.of("double", "int"))
                        .append("description", "present on every device document; percentage 0-100"));

        Document jsonSchema = new Document()
                .append("bsonType", "object")
                .append("required", List.of("sensorId", "timestamp"))
                .append("properties", properties);

        return new Document("$jsonSchema", jsonSchema);
    }

    private void ensureIndexes() {
        MongoCollection<Document> collection = database.getCollection(SENSOR_DATA_COLLECTION);
        String indexName = collection.createIndex(
                Indexes.compoundIndex(Indexes.ascending("sensorId"), Indexes.descending("timestamp")),
                new IndexOptions().name("sensorId_1_timestamp_-1")
        );
        log.info("Compound index ensured: {}", indexName);

        String ttlIndex = collection.createIndex(
                Indexes.ascending("timestamp"),
                new IndexOptions().expireAfter(TTL_EXPIRE_AFTER_DAYS, TimeUnit.DAYS).name("timestamp_ttl")
        );
        log.info("TTL index ensured: {}", ttlIndex);

        MongoCollection<Document> averagesCollection = database.getCollection(com.smarthome.AppConfig.SENSOR_AVERAGES_COLLECTION);
        String avgIndex = averagesCollection.createIndex(
                Indexes.compoundIndex(Indexes.ascending("sensorId"), Indexes.ascending("timestamp")),
                new IndexOptions().unique(true).name("sensorId_1_timestamp_1_unique")
        );
        log.info("Averages unique index ensured: {}", avgIndex);

        MongoCollection<Document> auditLogsCollection = database.getCollection(com.smarthome.AppConfig.AUDIT_LOGS_COLLECTION);
        String textIndex = auditLogsCollection.createIndex(
                Indexes.text("$**"),
                new IndexOptions().name("audit_logs_text")
        );
        log.info("Text index ensured: {}", textIndex);

        // PHASE 8: sensor_rate_events — short-TTL sliding window for rate limiting.
        // The TTL index auto-expires documents after RATE_LIMIT_WINDOW_SECONDS, which IS the
        // sliding window. No scheduled cleanup job needed — MongoDB does it natively.
        MongoCollection<Document> rateEventsCollection = database.getCollection(com.smarthome.AppConfig.SENSOR_RATE_EVENTS_COLLECTION);
        String rateTtlIndex = rateEventsCollection.createIndex(
                Indexes.ascending("createdAt"),
                new IndexOptions().expireAfter((long) com.smarthome.AppConfig.RATE_LIMIT_WINDOW_SECONDS, TimeUnit.SECONDS)
                        .name("rate_events_ttl")
        );
        log.info("Rate events TTL index ensured (window={}s): {}", com.smarthome.AppConfig.RATE_LIMIT_WINDOW_SECONDS, rateTtlIndex);

        String rateCompoundIndex = rateEventsCollection.createIndex(
                Indexes.compoundIndex(Indexes.ascending("sensorId"), Indexes.ascending("createdAt")),
                new IndexOptions().name("sensorId_1_createdAt_1")
        );
        log.info("Rate events compound index ensured: {}", rateCompoundIndex);
    }

    /**
     * PHASE 1: seeds a single home_status document ({_id: "singleton",
     * mode: "HOME"}) if one doesn't already exist. A fixed _id keeps this a
     * true singleton - upserts always target the same document rather than
     * accidentally creating a second one. The Phase 3 toggle endpoint will
     * update this document's "mode" field; Phase 2's intrusion logic reads
     * it to decide whether motion means "someone's home" or "break-in."
     */
    private void ensureHomeStatusDocument() {
        MongoCollection<Document> homeStatus = database.getCollection(HOME_STATUS_COLLECTION);

        Document existing = homeStatus.find(Filters.eq("_id", "singleton")).first();
        if (existing != null) {
            log.info("home_status singleton already exists (mode={}) - leaving as-is.", existing.getString("mode"));
            return;
        }

        Document defaultStatus = new Document("_id", "singleton").append("mode", "HOME");
        homeStatus.insertOne(defaultStatus);
        log.info("home_status singleton created with default mode=HOME.");
    }
}
