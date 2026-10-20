package com.smarthome.controller;

import com.smarthome.AppConfig;
import com.smarthome.simulator.DeviceSimulationService;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceSimulationService simulationService;
    private final com.smarthome.transactions.LockdownService lockdownService;
    private final com.mongodb.client.MongoDatabase database;

    public DeviceController(DeviceSimulationService simulationService, 
                            com.smarthome.transactions.LockdownService lockdownService,
                            com.mongodb.client.MongoDatabase database) {
        this.simulationService = simulationService;
        this.lockdownService = lockdownService;
        this.database = database;
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return Map.of(
                "rooms", AppConfig.ROOM_SENSOR_IDS,
                "locks", AppConfig.LOCK_SENSOR_IDS,
                "motionSensors", AppConfig.MOTION_SENSOR_IDS
        );
    }

    public static class TemperatureRequest {
        public Double temperature;
    }

    @PostMapping("/rooms/{roomId}/reading")
    public Document generateReading(@PathVariable String roomId, @RequestBody(required = false) TemperatureRequest body) {
        Double temp = body != null ? body.temperature : null;
        return simulationService.generateRoomReading(roomId, temp, false);
    }

    @PostMapping("/rooms/{roomId}/simulate-fire")
    public Document simulateFire(@PathVariable String roomId) {
        return simulationService.generateRoomReading(roomId, null, true);
    }

    public static class LockRequest {
        public boolean locked;
    }

    @PostMapping("/locks/{lockId}")
    public Document setLock(@PathVariable String lockId, @RequestBody LockRequest body) {
        return simulationService.setLockState(lockId, body.locked);
    }

    public static class MotionRequest {
        public boolean detected;
    }

    @PostMapping("/motion/{motionId}")
    public Document triggerMotion(@PathVariable String motionId, @RequestBody MotionRequest body) {
        return simulationService.triggerMotion(motionId, body.detected);
    }

    public static class AutoModeRequest {
        public Integer intervalSeconds;
    }

    @PostMapping("/auto-mode/start")
    public Map<String, Object> startAutoMode(@RequestBody(required = false) AutoModeRequest body) {
        simulationService.startAutoMode(body != null ? body.intervalSeconds : null);
        return simulationService.getState();
    }

    @PostMapping("/auto-mode/stop")
    public Map<String, Object> stopAutoMode() {
        simulationService.stopAutoMode();
        return simulationService.getState();
    }

    @PostMapping("/rooms/{roomId}/auto-enabled")
    public Map<String, Object> setRoomEnabled(@PathVariable String roomId, @RequestBody Map<String, Boolean> body) {
        simulationService.setRoomEnabled(roomId, Boolean.TRUE.equals(body.get("enabled")));
        return simulationService.getState();
    }

    @GetMapping("/auto-mode/state")
    public Map<String, Object> getState() {
        return simulationService.getState();
    }



    @PostMapping("/reset")
    public Map<String, String> resetSystem() {
        // Reset home mode to HOME
        com.mongodb.client.MongoCollection<Document> homeStatus = 
            database.getCollection(AppConfig.HOME_STATUS_COLLECTION);
        homeStatus.updateOne(com.mongodb.client.model.Filters.eq("_id", "singleton"),
                             com.mongodb.client.model.Updates.combine(
                                 com.mongodb.client.model.Updates.set("mode", "HOME"),
                                 com.mongodb.client.model.Updates.unset("lockedInteriorDoors")
                             ));
                             
        // Generate a normal reading for every room to clear fire state
        for (String roomId : AppConfig.ROOM_SENSOR_IDS) {
            simulationService.generateRoomReading(roomId, 72.0, false);
            simulationService.setDeviceBattery(roomId, 100.0);
            simulationService.setDevicePower(roomId, true);
        }
        // Unlock all doors — use bypass so reset works even if a lock sensor is quarantined
        for (String lockId : AppConfig.LOCK_SENSOR_IDS) {
            simulationService.setLockStateBypass(lockId, false);
            simulationService.setDeviceBattery(lockId, 100.0);
            simulationService.setDevicePower(lockId, true);
        }
        for (String motionId : AppConfig.MOTION_SENSOR_IDS) {
            simulationService.setDeviceBattery(motionId, 100.0);
            simulationService.setDevicePower(motionId, true);
        }
        return Map.of("status", "reset_complete");
    }

    @PostMapping("/lockdown")
    public Map<String, String> triggerLockdown(@RequestBody Map<String, String> body) {
        String sensorId = body.getOrDefault("sensorId", "MANUAL_TRIGGER");
        lockdownService.handleIntrusionEvent(sensorId);
        return Map.of("status", "lockdown_triggered");
    }

    public static class BatteryRequest {
        public double battery;
    }

    @PostMapping("/{deviceId}/battery")
    public Map<String, Object> setBattery(@PathVariable String deviceId, @RequestBody BatteryRequest body) {
        simulationService.setDeviceBattery(deviceId, body.battery);
        return simulationService.getState();
    }

    public static class PowerRequest {
        public boolean powered;
    }

    @PostMapping("/{deviceId}/power")
    public Map<String, Object> setPower(@PathVariable String deviceId, @RequestBody PowerRequest body) {
        simulationService.setDevicePower(deviceId, body.powered);
        return simulationService.getState();
    }
}
