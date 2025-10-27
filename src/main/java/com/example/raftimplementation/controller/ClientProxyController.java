package com.example.raftimplementation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Client-side controller that proxies requests to Raft nodes.
 * This controller is only active when running in client mode (raft.node.enabled=false).
 * It communicates with the Raft cluster nodes via REST API.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "false")
@Slf4j
public class ClientProxyController {
    
    private final RestTemplate restTemplate;
    
    // Default node ports to try
    private static final int[] NODE_PORTS = {8081, 8082, 8083, 8084, 8085, 8086};
    
    /**
     * Get status of all nodes in the cluster.
     */
    @GetMapping("/nodes")
    public ResponseEntity<List<Map<String, Object>>> getNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        
        for (int port : NODE_PORTS) {
            Map<String, Object> nodeInfo = new HashMap<>();
            nodeInfo.put("id", "node" + (port - 8080));
            nodeInfo.put("port", port);
            nodes.add(nodeInfo);
        }
        
        return ResponseEntity.ok(nodes);
    }
    
    /**
     * Proxy command submission to the cluster.
     * Finds a leader and forwards the command.
     */
    @PostMapping("/cluster/command")
    public ResponseEntity<Map<String, Object>> submitCommand(@RequestBody Map<String, String> request) {
        String command = request.get("command");
        
        if (command == null || command.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "Command is required"));
        }
        
        // Try to find a node that can handle the command
        for (int port : NODE_PORTS) {
            try {
                String url = String.format("http://localhost:%d/api/command", port);
                ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, 
                    Map.of("command", command), 
                    Map.class
                );
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    return ResponseEntity.ok(response.getBody());
                }
            } catch (Exception e) {
                log.debug("Failed to submit command to node on port {}: {}", port, e.getMessage());
            }
        }
        
        return ResponseEntity.status(503)
            .body(Map.of("success", false, "message", "No available nodes found"));
    }
    
    /**
     * Start a node (proxy to node manager).
     */
    @PostMapping("/nodes/{nodeId}/start")
    public ResponseEntity<Map<String, Object>> startNode(@PathVariable String nodeId) {
        return proxyToNodeManager("POST", "/nodes/" + nodeId + "/start", null);
    }
    
    /**
     * Stop a node (proxy to node manager).
     */
    @PostMapping("/nodes/{nodeId}/stop")
    public ResponseEntity<Map<String, Object>> stopNode(@PathVariable String nodeId) {
        return proxyToNodeManager("POST", "/nodes/" + nodeId + "/stop", null);
    }
    
    /**
     * Suspend a node (proxy to node manager).
     */
    @PostMapping("/nodes/{nodeId}/suspend")
    public ResponseEntity<Map<String, Object>> suspendNode(@PathVariable String nodeId) {
        return proxyToNodeManager("POST", "/nodes/" + nodeId + "/suspend", null);
    }
    
    /**
     * Resume a node (proxy to node manager).
     */
    @PostMapping("/nodes/{nodeId}/resume")
    public ResponseEntity<Map<String, Object>> resumeNode(@PathVariable String nodeId) {
        return proxyToNodeManager("POST", "/nodes/" + nodeId + "/resume", null);
    }
    
    /**
     * Add a node to the cluster (proxy to node manager).
     */
    @PostMapping("/cluster/add-node")
    public ResponseEntity<Map<String, Object>> addNode(@RequestBody Map<String, Object> request) {
        return proxyToNodeManager("POST", "/cluster/add-node", request);
    }
    
    /**
     * Remove a node from the cluster (proxy to node manager).
     */
    @PostMapping("/cluster/remove-node")
    public ResponseEntity<Map<String, Object>> removeNode(@RequestBody Map<String, Object> request) {
        return proxyToNodeManager("POST", "/cluster/remove-node", request);
    }
    
    /**
     * Helper method to proxy requests to any available node manager.
     */
    private ResponseEntity<Map<String, Object>> proxyToNodeManager(String method, String path, Object body) {
        for (int port : NODE_PORTS) {
            try {
                String url = String.format("http://localhost:%d/api%s", port, path);
                
                ResponseEntity<Map> response;
                if ("POST".equals(method)) {
                    response = restTemplate.postForEntity(url, body != null ? body : "", Map.class);
                } else {
                    response = restTemplate.getForEntity(url, Map.class);
                }
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    return ResponseEntity.ok(response.getBody());
                }
            } catch (Exception e) {
                log.debug("Failed to proxy to node on port {}: {}", port, e.getMessage());
            }
        }
        
        return ResponseEntity.status(503)
            .body(Map.of("success", false, "message", "No available nodes found"));
    }
}
