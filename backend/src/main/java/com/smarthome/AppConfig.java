package com.smarthome;

import java.util.Map;

public final class AppConfig {

    private AppConfig() {
    }

    public static final String DATABASE_NAME = "SmartHomeDB";

    public static final String SENSOR_DATA_COLLECTION = "sensor_data";
    public static final String SENSOR_AVERAGES_COLLECTION = "sensor_averages_daily";
    public static final String AUDIT_LOGS_COLLECTION = "audit_logs";
    // PHASE 1: singleton collection holding the current HOME/AWAY mode.
    public static final String HOME_STATUS_COLLECTION = "home_status";
    // PHASE 8: short-TTL sliding window collection for rate limiting.
    public static final String SENSOR_RATE_EVENTS_COLLECTION = "sensor_rate_events";
    public static final int RATE_LIMIT_WINDOW_SECONDS = 10; // Window size in seconds
    public static final int RATE_LIMIT_MAX_EVENTS = 5;      // Max events before quarantine

    public static final String[] ROOM_SENSOR_IDS = {
            "TEMP_LIVING_ROOM",
            "TEMP_BEDROOM",
            "TEMP_KITCHEN",
            "TEMP_GARAGE",
            "TEMP_OFFICE"
    };

    public static final String[] LOCK_SENSOR_IDS = {
            "LOCK_FRONT_DOOR",
            "LOCK_BACK_DOOR",
            "LOCK_GARAGE_DOOR",
            "LOCK_BEDROOM_DOOR",
            "LOCK_WINE_CELLAR"
    };

    // PHASE 1: which locks are exit routes (evacuation paths).
    // During a fire: exit routes (true) are UNLOCKED for evacuation;
    // interior doors (false) are LOCKED to contain the fire.
    public static final Map<String, Boolean> LOCK_EXIT_ROUTES = Map.of(
            "LOCK_FRONT_DOOR", true,
            "LOCK_BACK_DOOR", true,
            "LOCK_GARAGE_DOOR", true,
            "LOCK_BEDROOM_DOOR", false,
            "LOCK_WINE_CELLAR", false
    );

    public static final String[] MOTION_SENSOR_IDS = {
            "MOTION_HALLWAY",
            "MOTION_LIVING_ROOM",
            "MOTION_BACKYARD"
    };

    public static final double TEMP_MIN = 68.0;
    public static final double TEMP_MAX = 75.0;
    public static final double FIRE_TEMP_READING = 150.0;
    public static final double FIRE_TEMP_THRESHOLD = 100.0;

    public static final long TTL_EXPIRE_AFTER_DAYS = 7;

    // PHASE 1: starting battery level (percent) for every simulated device.
    // Draining logic itself is Phase 5 - for now the field just exists and
    // starts full, so the schema and every document already carries it.
    public static final double DEFAULT_BATTERY_LEVEL = 100.0;
}

