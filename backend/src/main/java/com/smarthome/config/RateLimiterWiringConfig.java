package com.smarthome.config;

import com.smarthome.ratelimit.RateLimiterService;
import com.smarthome.simulator.DeviceSimulationService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimiterWiringConfig {
    @Bean
    public ApplicationRunner wireRateLimiter(DeviceSimulationService sim, RateLimiterService rl) {
        return args -> sim.setRateLimiterService(rl);
    }
}