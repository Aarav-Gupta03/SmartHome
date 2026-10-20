package com.smarthome.db;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

@Service
public class ReplicaSetTopologyService {

    private static final Logger log = LoggerFactory.getLogger(ReplicaSetTopologyService.class);
    private final MongoDatabase database;

    public ReplicaSetTopologyService(MongoDatabase database) {
        this.database = database;
    }

    @PostConstruct
    public void logTopologyOnStartup() {
        Map<String, Object> topology = describeTopology();
        if (Boolean.TRUE.equals(topology.get("isReplicaSet"))) {
            log.info("Connected to replica set '{}' - primary: {}, {} member(s). "
                            + "Change streams and transactions are supported.",
                    topology.get("setName"), topology.get("primary"), topology.get("memberCount"));
        } else {
            log.warn("Connected deployment does NOT report a replica set name. "
                    + "Change streams and multi-document transactions will fail on a standalone mongod.");
        }
    }

    public Map<String, Object> describeTopology() {
        Document result;
        try {
            result = database.runCommand(new Document("hello", 1));
        } catch (Exception e) {
            log.error("Failed to run 'hello' admin command: {}", e.getMessage());
            return Map.of("isReplicaSet", false, "error", e.getMessage());
        }

        String setName = result.getString("setName");
        boolean isReplicaSet = setName != null;
        List<String> hosts = result.getList("hosts", String.class, List.of());
        String primary = result.getString("primary");
        Boolean isWritablePrimary = result.getBoolean("isWritablePrimary");

        return Map.of(
                "isReplicaSet", isReplicaSet,
                "setName", setName != null ? setName : "(none - standalone instance)",
                "memberCount", hosts.size(),
                "hosts", hosts,
                "primary", primary != null ? primary : "(unknown)",
                "thisNodeIsWritablePrimary", isWritablePrimary != null && isWritablePrimary
        );
    }
}
