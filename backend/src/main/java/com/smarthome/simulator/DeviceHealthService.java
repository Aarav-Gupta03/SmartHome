package com.smarthome.simulator;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

import static com.smarthome.AppConfig.SENSOR_DATA_COLLECTION;

@Service
public class DeviceHealthService {

    private final MongoCollection<Document> sensorDataCollection;

    public DeviceHealthService(MongoDatabase database) {
        this.sensorDataCollection = database.getCollection(SENSOR_DATA_COLLECTION);
    }

    public boolean isDeviceOnline(String sensorId) {
        Document latest = sensorDataCollection.find(Filters.eq("sensorId", sensorId))
                .sort(Sorts.descending("timestamp"))
                .first();

        if (latest == null) {
            return false; // No data at all -> offline
        }

        // Check battery
        Object batObj = latest.get("batteryLevel");
        double batteryLevel = 100.0;
        if (batObj instanceof Number) {
            batteryLevel = ((Number) batObj).doubleValue();
        }
        if (batteryLevel < 10.0) {
            return false;
        }

        // Check silence (missed heartbeats)
        Date timestamp = latest.getDate("timestamp");
        if (timestamp != null) {
            long ageMillis = Instant.now().toEpochMilli() - timestamp.getTime();
            if (ageMillis > 15000) { // 15 seconds
                return false;
            }
        }

        return true;
    }
}
