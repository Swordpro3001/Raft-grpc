package com.example.raftimplementation.service;

import com.example.raftimplementation.config.ClusterConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;

/**
 * Management Service - runs only on the management node.
 * Completely independent from Raft consensus logic.
 * Knows about all Raft nodes and can query/manage them.
 */
@Service
@ConditionalOnProperty(name = "management.enabled", havingValue = "true")
@Slf4j
public class ManagementService {
    
    private final ClusterConfig clusterConfig;
    private final RestTemplate restTemplate;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Cache for node status
    private final Map<String, Map<String, Object>> nodeStatusCache = new ConcurrentHashMap<>();
    
    public ManagementService(ClusterConfig clusterConfig) {
        this.clusterConfig = clusterConfig;
        this.restTemplate = new RestTemplate();
        
        // Start periodic health check
        scheduler.scheduleAtFixedRate(this::updateNodeStatus, 0, 5, TimeUnit.SECONDS);
        
        log.info("Management Service initialized with {} nodes", clusterConfig.getNodes().size());
    }
    
    /**
     * Get status of all nodes in the cluster.
     */
    public List<Map<String, Object>> getAllNodeStatus() {
        return new ArrayList<>(nodeStatusCache.values());
    }
    
    /**
     * Get status of a specific node.
     */
    public Map<String, Object> getNodeStatus(String nodeId) {
        ClusterConfig.NodeInfo node = findNode(nodeId);
        if (node == null) {
            return createErrorStatus(nodeId, "Node not found in cluster configuration");
        }
        
        return nodeStatusCache.getOrDefault(nodeId, createErrorStatus(nodeId, "Status not available"));
    }
    
    /**
     * Submit a command to the cluster (will be sent to the leader).
     */
    public Map<String, Object> submitCommand(String command) {
        // Find the leader
        String leaderId = findLeader();
        
        if (leaderId == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "No leader found in cluster");
            return error;
        }
        
        ClusterConfig.NodeInfo leader = findNode(leaderId);
        if (leader == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Leader node configuration not found");
            return error;
        }
        
        try {
            String url = String.format("http://%s:%d/api/command", leader.getHost(), leader.getHttpPort());
            Map<String, String> request = new HashMap<>();
            request.put("command", command);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            return response != null ? response : createErrorResponse("Empty response from leader");
        } catch (Exception e) {
            log.error("Failed to submit command to leader {}: {}", leaderId, e.getMessage());
            return createErrorResponse("Failed to submit command: " + e.getMessage());
        }
    }
    
    /**
     * Create a snapshot on a specific node.
     */
    public Map<String, Object> createSnapshot(String nodeId) {
        ClusterConfig.NodeInfo node = findNode(nodeId);
        if (node == null) {
            return createErrorResponse("Node not found: " + nodeId);
        }
        
        try {
            String url = String.format("http://%s:%d/api/snapshot", node.getHost(), node.getHttpPort());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, null, Map.class);
            return response != null ? response : createErrorResponse("Empty response");
        } catch (Exception e) {
            log.error("Failed to create snapshot on {}: {}", nodeId, e.getMessage());
            return createErrorResponse("Failed to create snapshot: " + e.getMessage());
        }
    }
    
    /**
     * Suspend a node.
     */
    public Map<String, Object> suspendNode(String nodeId) {
        return changeNodeState(nodeId, "suspend");
    }
    
    /**
     * Resume a node.
     */
    public Map<String, Object> resumeNode(String nodeId) {
        return changeNodeState(nodeId, "resume");
    }
    
    /**
     * Get cluster overview with statistics.
     */
    public Map<String, Object> getClusterOverview() {
        Map<String, Object> overview = new HashMap<>();
        
        int totalNodes = clusterConfig.getNodes().size();
        int healthyNodes = 0;
        int suspendedNodes = 0;
        String leaderId = null;
        
        for (Map<String, Object> status : nodeStatusCache.values()) {
            if (Boolean.TRUE.equals(status.get("healthy"))) {
                healthyNodes++;
            }
            if (Boolean.TRUE.equals(status.get("suspended"))) {
                suspendedNodes++;
            }
            if ("LEADER".equals(status.get("state"))) {
                leaderId = (String) status.get("nodeId");
            }
        }
        
        overview.put("totalNodes", totalNodes);
        overview.put("healthyNodes", healthyNodes);
        overview.put("suspendedNodes", suspendedNodes);
        overview.put("unhealthyNodes", totalNodes - healthyNodes);
        overview.put("leaderId", leaderId);
        overview.put("hasQuorum", healthyNodes >= (totalNodes / 2 + 1));
        
        return overview;
    }
    
    /**
     * Get list of all configured nodes.
     */
    public List<ClusterConfig.NodeInfo> getConfiguredNodes() {
        return new ArrayList<>(clusterConfig.getNodes());
    }
    
    /**
     * Get status from any available node (for metrics).
     */
    public Map<String, Object> getStatusFromAnyNode() {
        for (ClusterConfig.NodeInfo node : clusterConfig.getNodes()) {
            Map<String, Object> status = nodeStatusCache.get(node.getNodeId());
            if (status != null && Boolean.TRUE.equals(status.get("healthy"))) {
                return status;
            }
        }
        return createErrorResponse("No healthy nodes available");
    }
    
    /**
     * Proxy a request to any available node.
     */
    public Map<String, Object> proxyToAnyNode(String path) {
        for (ClusterConfig.NodeInfo node : clusterConfig.getNodes()) {
            try {
                String url = String.format("http://%s:%d%s", node.getHost(), node.getHttpPort(), path);
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.getForObject(url, Map.class);
                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                log.debug("Failed to proxy to {}: {}", node.getNodeId(), e.getMessage());
            }
        }
        return createErrorResponse("No available nodes to proxy request");
    }
    
    /**
     * Proxy a POST request to any available node.
     */
    public Map<String, Object> proxyPostToAnyNode(String path) {
        for (ClusterConfig.NodeInfo node : clusterConfig.getNodes()) {
            try {
                String url = String.format("http://%s:%d%s", node.getHost(), node.getHttpPort(), path);
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.postForObject(url, null, Map.class);
                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                log.debug("Failed to proxy POST to {}: {}", node.getNodeId(), e.getMessage());
            }
        }
        return createErrorResponse("No available nodes to proxy request");
    }
    
    /**
     * Linearizable read - proxy to the leader to ensure strong consistency.
     */
    public Map<String, Object> linearizableRead() {
        String leaderId = findLeader();
        if (leaderId == null) {
            return createErrorResponse("No leader available for linearizable read");
        }
        
        ClusterConfig.NodeInfo leader = findNode(leaderId);
        if (leader == null) {
            return createErrorResponse("Leader node configuration not found");
        }
        
        try {
            String url = String.format("http://%s:%d/api/read", leader.getHost(), leader.getHttpPort());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return response != null ? response : createErrorResponse("Empty response");
        } catch (Exception e) {
            log.error("Failed to perform linearizable read from leader {}: {}", leaderId, e.getMessage());
            return createErrorResponse("Failed to read from leader: " + e.getMessage());
        }
    }
    
    // ==================== Private Helper Methods ====================
    
    /**
     * Periodically update node status cache.
     */
    private void updateNodeStatus() {
        for (ClusterConfig.NodeInfo node : clusterConfig.getNodes()) {
            CompletableFuture.runAsync(() -> {
                try {
                    String url = String.format("http://%s:%d/api/status", node.getHost(), node.getHttpPort());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> status = restTemplate.getForObject(url, Map.class);
                    
                    if (status != null) {
                        status.put("healthy", true);
                        status.put("lastUpdate", System.currentTimeMillis());
                        
                        // Fetch events for this node
                        try {
                            String eventsUrl = String.format("http://%s:%d/api/events", node.getHost(), node.getHttpPort());
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> events = restTemplate.getForObject(eventsUrl, List.class);
                            status.put("events", events != null ? events : new ArrayList<>());
                        } catch (Exception eventsError) {
                            log.debug("Failed to get events from {}: {}", node.getNodeId(), eventsError.getMessage());
                            status.put("events", new ArrayList<>());
                        }
                        
                        nodeStatusCache.put(node.getNodeId(), status);
                    }
                } catch (Exception e) {
                    log.debug("Failed to get status from {}: {}", node.getNodeId(), e.getMessage());
                    nodeStatusCache.put(node.getNodeId(), 
                        createErrorStatus(node.getNodeId(), "Unreachable: " + e.getMessage()));
                }
            });
        }
    }
    
    /**
     * Find the current leader in the cluster.
     */
    private String findLeader() {
        for (Map<String, Object> status : nodeStatusCache.values()) {
            if ("LEADER".equals(status.get("state"))) {
                return (String) status.get("nodeId");
            }
        }
        return null;
    }
    
    /**
     * Find node configuration by ID.
     */
    private ClusterConfig.NodeInfo findNode(String nodeId) {
        return clusterConfig.getNodes().stream()
            .filter(n -> n.getNodeId().equals(nodeId))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Change node state (suspend/resume).
     */
    private Map<String, Object> changeNodeState(String nodeId, String action) {
        ClusterConfig.NodeInfo node = findNode(nodeId);
        if (node == null) {
            return createErrorResponse("Node not found: " + nodeId);
        }
        
        try {
            String url = String.format("http://%s:%d/api/%s", node.getHost(), node.getHttpPort(), action);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, null, Map.class);
            return response != null ? response : createErrorResponse("Empty response");
        } catch (Exception e) {
            log.error("Failed to {} node {}: {}", action, nodeId, e.getMessage());
            return createErrorResponse("Failed to " + action + " node: " + e.getMessage());
        }
    }
    
    /**
     * Create an error status object.
     */
    private Map<String, Object> createErrorStatus(String nodeId, String error) {
        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", nodeId);
        status.put("healthy", false);
        status.put("error", error);
        status.put("lastUpdate", System.currentTimeMillis());
        status.put("events", new ArrayList<>()); // Empty events array for offline nodes
        return status;
    }
    
    /**
     * Create an error response object.
     */
    private Map<String, Object> createErrorResponse(String error) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", error);
        return response;
    }
}
