package com.example.raftimplementation.controller;

import com.example.raftimplementation.model.ServerInfo;
import com.example.raftimplementation.service.ClusterManager;
import com.example.raftimplementation.service.NodeManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class NodeManagerController {
    
    private final NodeManagerService nodeManagerService;
    private final ClusterManager clusterManager;
    private final RestTemplate restTemplate;
    
    /**
     * Submit a command to the cluster. This endpoint finds the leader
     * and forwards the command to it.
     */
    @PostMapping("/cluster/command")
    public ResponseEntity<Map<String, Object>> submitCommand(@RequestBody Map<String, String> request) {
        String command = request.get("command");
        
        if (command == null || command.trim().isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Command cannot be empty"
            ));
        }
        
        log.info("Received command from client: {}", command);
        
        // Try to find the leader
        ServerInfo leaderInfo = clusterManager.getLeaderInfo();
        
        if (leaderInfo != null) {
            // Try the known leader first
            ResponseEntity<Map<String, Object>> response = trySubmitToNode(leaderInfo, command);
            if (response != null && response.getBody() != null && 
                Boolean.TRUE.equals(response.getBody().get("success"))) {
                Map<String, Object> result = new HashMap<>(response.getBody());
                result.put("currentLeader", leaderInfo.getNodeId());
                return ResponseEntity.ok(result);
            }
        }
        
        // Leader unknown or request failed, try all servers
        log.info("Leader unknown or unavailable, trying all servers");
        for (ServerInfo server : clusterManager.getAllServers().values()) {
            try {
                ResponseEntity<Map<String, Object>> response = trySubmitToNode(server, command);
                if (response != null && response.getBody() != null && 
                    Boolean.TRUE.equals(response.getBody().get("success"))) {
                    Map<String, Object> result = new HashMap<>(response.getBody());
                    result.put("currentLeader", server.getNodeId());
                    return ResponseEntity.ok(result);
                }
            } catch (Exception e) {
                log.debug("Failed to submit to {}: {}", server.getNodeId(), e.getMessage());
            }
        }
        
        return ResponseEntity.ok(Map.of(
            "success", false,
            "message", "No leader found. Please wait for leader election.",
            "currentLeader", clusterManager.getCurrentLeader() != null ? 
                clusterManager.getCurrentLeader() : "unknown"
        ));
    }
    
    private ResponseEntity<Map<String, Object>> trySubmitToNode(ServerInfo server, String command) {
        try {
            String url = String.format("http://%s:%d/api/command", 
                server.getHost(), server.getHttpPort());
            
            log.debug("Trying to submit command to {}", server.getNodeId());
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                url, 
                Map.of("command", command), 
                Map.class
            );
            
            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                log.info("Command successfully submitted to leader: {}", server.getNodeId());
                clusterManager.setCurrentLeader(server.getNodeId());
                return ResponseEntity.ok(response);
            }
            
            return null;
        } catch (Exception e) {
            log.debug("Failed to contact {}: {}", server.getNodeId(), e.getMessage());
            return null;
        }
    }
    
    /**
     * Get cluster status including all members and the current leader.
     */
    @GetMapping("/cluster/status")
    public ResponseEntity<Map<String, Object>> getClusterStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("clusterSize", clusterManager.getClusterSize());
        status.put("currentLeader", clusterManager.getCurrentLeader());
        status.put("servers", clusterManager.getAllServers());
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * Add a new server to the cluster (membership change).
     */
    @PostMapping("/cluster/add")
    public ResponseEntity<Map<String, Object>> addServer(@RequestBody ServerInfo serverInfo) {
        log.info("Request to add server: {}", serverInfo);
        
        if (clusterManager.hasServer(serverInfo.getNodeId())) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Server " + serverInfo.getNodeId() + " already exists in cluster"
            ));
        }
        
        // Try to find the leader - with retry logic
        ServerInfo leaderInfo = clusterManager.getLeaderInfo();
        
        if (leaderInfo == null) {
            // Try to find leader by querying all nodes
            log.info("Leader unknown, querying all nodes to find leader");
            for (ServerInfo server : clusterManager.getAllServers().values()) {
                try {
                    String statusUrl = String.format("http://%s:%d/api/status", 
                        server.getHost(), server.getHttpPort());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> status = restTemplate.getForObject(statusUrl, Map.class);
                    
                    if (status != null && "LEADER".equals(status.get("state"))) {
                        leaderInfo = server;
                        clusterManager.setCurrentLeader(server.getNodeId());
                        log.info("Found leader: {}", server.getNodeId());
                        break;
                    }
                } catch (Exception e) {
                    log.debug("Failed to query {}: {}", server.getNodeId(), e.getMessage());
                }
            }
        }
        
        if (leaderInfo == null) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "No leader available for membership change. Please wait for leader election."
            ));
        }
        
        try {
            String url = String.format("http://%s:%d/api/membership/add", 
                leaderInfo.getHost(), leaderInfo.getHttpPort());
            
            log.info("Forwarding add request to leader {} at {}", leaderInfo.getNodeId(), url);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, serverInfo, Map.class);
            
            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                // Register in cluster manager after successful addition
                clusterManager.registerServer(serverInfo);
                log.info("Successfully added server {} to cluster", serverInfo.getNodeId());
            }
            
            return ResponseEntity.ok(response != null ? response : Map.of(
                "success", false,
                "message", "Failed to add server - no response from leader"
            ));
        } catch (Exception e) {
            log.error("Failed to forward add server request to leader {}", leaderInfo.getNodeId(), e);
            
            String errorMsg = e.getMessage();
            if (errorMsg == null) {
                errorMsg = e.getClass().getSimpleName();
            }
            
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Failed to contact leader: " + errorMsg
            ));
        }
    }
    
    /**
     * Remove a server from the cluster (membership change).
     */
    @PostMapping("/cluster/remove/{nodeId}")
    public ResponseEntity<Map<String, Object>> removeServer(@PathVariable String nodeId) {
        log.info("Request to remove server: {}", nodeId);
        
        if (!clusterManager.hasServer(nodeId)) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Server " + nodeId + " not found in cluster"
            ));
        }
        
        // Try to find the leader - with retry logic
        ServerInfo leaderInfo = clusterManager.getLeaderInfo();
        
        if (leaderInfo == null) {
            // Try to find leader by querying all nodes
            log.info("Leader unknown, querying all nodes to find leader");
            for (ServerInfo server : clusterManager.getAllServers().values()) {
                try {
                    String statusUrl = String.format("http://%s:%d/api/status", 
                        server.getHost(), server.getHttpPort());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> status = restTemplate.getForObject(statusUrl, Map.class);
                    
                    if (status != null && "LEADER".equals(status.get("state"))) {
                        leaderInfo = server;
                        clusterManager.setCurrentLeader(server.getNodeId());
                        log.info("Found leader: {}", server.getNodeId());
                        break;
                    }
                } catch (Exception e) {
                    log.debug("Failed to query {}: {}", server.getNodeId(), e.getMessage());
                }
            }
        }
        
        if (leaderInfo == null) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "No leader available for membership change. Please wait for leader election."
            ));
        }
        
        try {
            String url = String.format("http://%s:%d/api/membership/remove/%s", 
                leaderInfo.getHost(), leaderInfo.getHttpPort(), nodeId);
            
            log.info("Forwarding remove request to leader {} at {}", leaderInfo.getNodeId(), url);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, null, Map.class);
            
            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                // Unregister from cluster manager after successful removal
                clusterManager.unregisterServer(nodeId);
                log.info("Successfully removed server {} from cluster", nodeId);
            }
            
            return ResponseEntity.ok(response != null ? response : Map.of(
                "success", false,
                "message", "Failed to remove server - no response from leader"
            ));
        } catch (Exception e) {
            log.error("Failed to forward remove server request to leader {}", leaderInfo.getNodeId(), e);
            
            String errorMsg = e.getMessage();
            if (errorMsg == null) {
                errorMsg = e.getClass().getSimpleName();
            }
            
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Failed to contact leader: " + errorMsg
            ));
        }
    }
    
    // Node management endpoints
    
    @PostMapping("/nodes/{nodeId}/start")
    public ResponseEntity<Map<String, Object>> startNode(@PathVariable String nodeId) {
        boolean success = nodeManagerService.startNode(nodeId);
        
        if (success) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Node " + nodeId + " started successfully",
                "nodeId", nodeId
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Failed to start node " + nodeId + " (already running or error)",
                "nodeId", nodeId
            ));
        }
    }
    
    @PostMapping("/nodes/{nodeId}/stop")
    public ResponseEntity<Map<String, Object>> stopNode(@PathVariable String nodeId) {
        boolean success = nodeManagerService.stopNode(nodeId);
        
        if (success) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Node " + nodeId + " stopped successfully",
                "nodeId", nodeId
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Failed to stop node " + nodeId + " (not running)",
                "nodeId", nodeId
            ));
        }
    }
    
    /**
     * Suspend a node's Raft participation while keeping Spring Boot running.
     * This is different from stop - the process stays alive but pauses Raft protocol.
     */
    @PostMapping("/nodes/{nodeId}/suspend")
    public ResponseEntity<Map<String, Object>> suspendNode(@PathVariable String nodeId) {
        ServerInfo server = clusterManager.getServer(nodeId);
        
        if (server == null) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Node " + nodeId + " not found in cluster",
                "nodeId", nodeId
            ));
        }
        
        try {
            String url = String.format("http://%s:%d/api/suspend", server.getHost(), server.getHttpPort());
            restTemplate.postForEntity(url, null, Map.class);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Node " + nodeId + " suspended successfully",
                "nodeId", nodeId
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Failed to suspend node " + nodeId + ": " + e.getMessage(),
                "nodeId", nodeId
            ));
        }
    }
    
    /**
     * Resume a suspended node's Raft participation.
     */
    @PostMapping("/nodes/{nodeId}/resume")
    public ResponseEntity<Map<String, Object>> resumeNode(@PathVariable String nodeId) {
        ServerInfo server = clusterManager.getServer(nodeId);
        
        if (server == null) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Node " + nodeId + " not found in cluster",
                "nodeId", nodeId
            ));
        }
        
        try {
            String url = String.format("http://%s:%d/api/resume", server.getHost(), server.getHttpPort());
            restTemplate.postForEntity(url, null, Map.class);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Node " + nodeId + " resumed successfully",
                "nodeId", nodeId
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Failed to resume node " + nodeId + ": " + e.getMessage(),
                "nodeId", nodeId
            ));
        }
    }
    
    @GetMapping("/nodes/status")
    public ResponseEntity<Map<String, Boolean>> getAllNodesStatus() {
        return ResponseEntity.ok(nodeManagerService.getAllNodesStatus());
    }
    
    @GetMapping("/nodes/{nodeId}/logs")
    public ResponseEntity<Map<String, Object>> getNodeLogs(@PathVariable String nodeId) {
        return ResponseEntity.ok(Map.of(
            "nodeId", nodeId,
            "logs", nodeManagerService.getNodeLogs(nodeId),
            "running", nodeManagerService.isNodeRunning(nodeId)
        ));
    }
}
