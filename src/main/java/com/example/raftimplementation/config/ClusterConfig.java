package com.example.raftimplementation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Cluster configuration for the management node.
 * This allows the management node to know about all Raft nodes without being part of the Raft cluster.
 */
@Configuration
@ConfigurationProperties(prefix = "cluster")
@Data
public class ClusterConfig {
    
    /**
     * List of all Raft nodes in the cluster.
     * The management node uses this to communicate with nodes.
     */
    private List<NodeInfo> nodes = new ArrayList<>();
    
    @Data
    public static class NodeInfo {
        private String nodeId;
        private String host;
        private int httpPort;
        private int grpcPort;
    }
}
