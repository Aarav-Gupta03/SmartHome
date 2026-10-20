package com.smarthome.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsScheduler.class);
    private final AnalyticsEngine analyticsEngine;

    public AnalyticsScheduler(AnalyticsEngine analyticsEngine) {
        this.analyticsEngine = analyticsEngine;
    }

    @Scheduled(fixedRate = 60_000)
    public void run() {
        try {
            analyticsEngine.computeRollingTemperatureAverages();
        } catch (Exception e) {
            log.error("Scheduled analytics run failed: {}", e.getMessage());
        }
    }
}
