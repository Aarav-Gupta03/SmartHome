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
            lockedInteriorDoors = simulationService.getLockedInteriorDoors();
        }
        
        homeStatus.updateOne(Filters.eq("_id", "singleton"), 
            Updates.combine(
                Updates.set("mode", mode),
                Updates.set("lockedInteriorDoors", lockedInteriorDoors)
            )
        );
        
        webSocketHandler.broadcast(new Document()
                .append("type", "HOME_STATUS_CHANGED")
                .append("mode", mode)
                .toJson());
                
        if ("AWAY".equals(mode)) {
            // Lock ALL doors when arming — interior doors must also be secured so any unlock triggers an alert.
            for (String lockId : AppConfig.LOCK_SENSOR_IDS) {
                simulationService.setLockState(lockId, true);
            }
        }
                
        return Map.of("mode", mode);
    }
}
