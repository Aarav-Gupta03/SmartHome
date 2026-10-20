package com.smarthome.controller;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.smarthome.AppConfig;
import com.smarthome.db.ReplicaSetTopologyService;
import com.smarthome.db.ShardingAdminService;
import com.smarthome.websocket.LiveUpdateWebSocketHandler;
import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ReplicaSetTopologyService topologyService;
    private final ShardingAdminService shardingAdminService;
    private final MongoDatabase database;
    private final LiveUpdateWebSocketHandler webSocketHandler;
    private final com.smarthome.simulator.DeviceSimulationService simulationService;

    public SystemController(ReplicaSetTopologyService topologyService, ShardingAdminService shardingAdminService, 
                            MongoDatabase database, LiveUpdateWebSocketHandler webSocketHandler,
                            com.smarthome.simulator.DeviceSimulationService simulationService) {
        this.topologyService = topologyService;
        this.shardingAdminService = shardingAdminService;
        this.database = database;
        this.webSocketHandler = webSocketHandler;
        this.simulationService = simulationService;
    }

    @GetMapping("/topology")
    public Map<String, Object> topology() {
        return topologyService.describeTopology();
    }

    @PostMapping("/sharding/enable")
    public Map<String, Object> enableSharding() {
        return shardingAdminService.attemptEnableSharding();
    }

    @GetMapping("/mode")
    public Map<String, String> getMode() {
        MongoCollection<Document> homeStatus = database.getCollection(AppConfig.HOME_STATUS_COLLECTION);
        Document status = homeStatus.find(Filters.eq("_id", "singleton")).first();
        String mode = (status != null && status.getString("mode") != null) ? status.getString("mode") : "HOME";
        return Map.of("mode", mode);
    }

    @PostMapping("/mode")
    public Map<String, String> setMode(@RequestParam String mode) {
        MongoCollection<Document> homeStatus = database.getCollection(AppConfig.HOME_STATUS_COLLECTION);
        java.util.List<String> lockedInteriorDoors = new java.util.ArrayList<>();

        if ("AWAY".equals(mode)) {
            // Build the list of interior doors that will be locked when arming.
            // Exit-route doors (isExitRoute=true) are intentionally NOT locked on arm —
            // they must remain operable for emergency evacuation (same principle as fire logic).
            for (String lockId : AppConfig.LOCK_SENSOR_IDS) {
                if (!Boolean.TRUE.equals(AppConfig.LOCK_EXIT_ROUTES.get(lockId))) {
                    lockedInteriorDoors.add(lockId);
                }
            }
        }

        homeStatus.updateOne(Filters.eq("_id", "singleton"),
            Updates.combine(
                Updates.set("mode", mode),
                Updates.set("lockedInteriorDoors", lockedInteriorDoors)
            )
        );

        if ("AWAY".equals(mode)) {
            // Only lock interior (non-exit-route) doors when arming.
            // Exit-route doors remain in their current state — locking them would be a safety violation.
            for (String lockId : AppConfig.LOCK_SENSOR_IDS) {
                if (!Boolean.TRUE.equals(AppConfig.LOCK_EXIT_ROUTES.get(lockId))) {
                    simulationService.setLockStateBypass(lockId, true);
                }
            }
        }

        webSocketHandler.broadcast(new Document()
                .append("type", "HOME_STATUS_CHANGED")
                .append("mode", mode)
                .toJson());

        return Map.of("mode", mode);
    }
}
