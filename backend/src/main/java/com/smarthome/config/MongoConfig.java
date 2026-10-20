package com.smarthome.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.smarthome.AppConfig;
import com.smarthome.db.DatabaseInitializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.PreDestroy;

@Configuration
public class MongoConfig implements WebMvcConfigurer {

    @Value("${mongodb.uri}")
    private String connectionString;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    private MongoClient mongoClient;

    @Bean
    public MongoClient mongoClient() {
        this.mongoClient = MongoClients.create(connectionString);
        return this.mongoClient;
    }

    @Bean
    public MongoDatabase mongoDatabase(MongoClient client) {
        MongoDatabase database = client.getDatabase(AppConfig.DATABASE_NAME);
        new DatabaseInitializer(database).initialize();
        return database;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }

    @PreDestroy
    public void shutdown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}
