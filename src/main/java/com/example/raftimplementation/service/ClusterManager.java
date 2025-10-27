package com.example.raftimplementation.service;

import com.example.raftimplementation.model.ServerInfo;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages cluster membership information.
 * This service maintains a registry of all servers in the cluster
 * and provides methods to query and update membership.
 */
@Service
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@Getter
public class ClusterManager {
    
    /**
     * Map of nodeId -> ServerInfo for all known servers.
     */
    private final Map<String, ServerInfo> servers = new ConcurrentHashMap<>();
    
    /**
     * The current leader (if known).
     */
    private volatile String currentLeader = null;
    
    /**
     * Register a server in the cluster.
     */
    public void registerServer(ServerInfo serverInfo) {
        servers.put(serverInfo.getNodeId(), serverInfo);
        log.info("Registered server: {}", serverInfo);
    }
    
    /**
     * Unregister a server from the cluster.
     */
    public void unregisterServer(String nodeId) {
        ServerInfo removed = servers.remove(nodeId);
        if (removed != null) {
            log.info("Unregistered server: {}", removed);
        }
    }
    
    /**
     * Get information about a specific server.
     */
    public ServerInfo getServer(String nodeId) {
        return servers.get(nodeId);
    }
    
    /**
     * Get all server IDs.
     */
    public Set<String> getAllServerIds() {
        return servers.keySet();
    }
    
    /**
     * Get all servers.
     */
    public Map<String, ServerInfo> getAllServers() {
        return new ConcurrentHashMap<>(servers);
    }
    
    /**
     * Update the current leader.
     */
    public void setCurrentLeader(String leaderId) {
        if (leaderId == null || !leaderId.equals(this.currentLeader)) {
            log.info("Leader changed: {} -> {}", this.currentLeader, leaderId);
            this.currentLeader = leaderId;
        }
    }
    
    /**
     * Get the current leader's server info.
     */
    public ServerInfo getLeaderInfo() {
        if (currentLeader == null) {
            return null;
        }
        return servers.get(currentLeader);
    }
    
    /**
     * Check if a server exists in the cluster.
     */
    public boolean hasServer(String nodeId) {
        return servers.containsKey(nodeId);
    }
    
    /**
     * Get the number of servers in the cluster.
     */
    public int getClusterSize() {
        return servers.size();
    }
    
    /**
     * Initialize with default servers from configuration.
     */
    @PostConstruct
    public void initializeDefaultServers() {
        // Default 5-node cluster
        for (int i = 1; i <= 5; i++) {
            String nodeId = "node" + i;
            registerServer(new ServerInfo(
                nodeId,
                "localhost",
                9090 + i,  // gRPC ports: 9091-9095
                8080 + i   // HTTP ports: 8081-8085
            ));
        }
        log.info("Initialized cluster manager with {} default servers", servers.size());
    }
}
