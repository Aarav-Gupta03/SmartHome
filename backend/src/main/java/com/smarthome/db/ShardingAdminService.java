package com.smarthome.db;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

import static com.smarthome.AppConfig.DATABASE_NAME;
import static com.smarthome.AppConfig.SENSOR_DATA_COLLECTION;

@Service
public class ShardingAdminService {

    private static final Logger log = LoggerFactory.getLogger(ShardingAdminService.class);
    private final MongoDatabase adminDatabase;

    public ShardingAdminService(MongoClient client) {
        this.adminDatabase = client.getDatabase("admin");
    }

    public Map<String, Object> attemptEnableSharding() {
        try {
            Document enableResult = adminDatabase.runCommand(
                    new Document("enableSharding", DATABASE_NAME));
            log.info("enableSharding succeeded: {}", enableResult.toJson());

            Document shardResult = adminDatabase.runCommand(
                    new Document("shardCollection", DATABASE_NAME + "." + SENSOR_DATA_COLLECTION)
                            .append("key", new Document("sensorId", "hashed")));
            log.info("shardCollection succeeded: {}", shardResult.toJson());

            return Map.of(
                    "success", true,
                    "message", "Collection is now sharded on a hashed sensorId key.",
                    "enableShardingResult", enableResult.toJson(),
                    "shardCollectionResult", shardResult.toJson()
            );
        } catch (MongoCommandException e) {
            log.warn("Sharding command failed (expected on M0 / non-sharded deployments): {}", e.getErrorMessage());
            return Map.of(
                    "success", false,
                    "message", "This MongoDB deployment does not support sharding (e.g. Atlas M0 free tier, "
                            + "or a plain replica set without a mongos/config-server topology). "
                            + "The commands issued are real and would succeed against a sharded cluster "
                            + "(Atlas M10+ or a self-hosted sharded deployment).",
                    "driverErrorMessage", e.getErrorMessage()
            );
        } catch (Exception e) {
            log.error("Unexpected error while attempting to enable sharding: {}", e.getMessage());
            return Map.of("success", false, "message", "Unexpected error: " + e.getMessage());
        }
    }
}
