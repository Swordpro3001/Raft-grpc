package com.example.raftimplementation.controller;

import com.example.raftimplementation.service.ManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Client-compatible API endpoints for the management node.
 * Provides backward compatibility with the old ClientProxyController.
 * All requests are forwarded to appropriate Raft nodes via ManagementService.
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "management.enabled", havingValue = "true")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@Slf4j
public class ManagementProxyController {
    
    private final ManagementService managementService;
    
    /**
     * Submit a command to the cluster (forwarded to leader).
     */
    @PostMapping("/command")
    public ResponseEntity<Map<String, Object>> submitCommand(@RequestBody Map<String, String> request) {
        String command = request.get("command");
        if (command == null || command.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "Command is required"
            ));
        }
        
        log.info("Command submission via /api/command: {}", command);
        return ResponseEntity.ok(managementService.submitCommand(command));
    }
    
    /**
     * Get status from any available node.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(managementService.getStatusFromAnyNode());
    }
    
    /**
     * Linearizable read - proxy to the leader.
     */
    @GetMapping("/read")
    public ResponseEntity<Map<String, Object>> linearizableRead() {
        log.info("Linearizable read via /api/read");
        return ResponseEntity.ok(managementService.linearizableRead());
    }
    
    /**
     * Get list of all nodes.
     */
    @GetMapping("/nodes")
    public ResponseEntity<List<Map<String, Object>>> getNodes() {
        return ResponseEntity.ok(managementService.getAllNodeStatus());
    }
    
    /**
     * Suspend a node.
     */
    @PostMapping("/nodes/{nodeId}/suspend")
    public ResponseEntity<Map<String, Object>> suspendNode(@PathVariable String nodeId) {
        log.info("Suspend node via /api/nodes/{}/suspend", nodeId);
        return ResponseEntity.ok(managementService.suspendNode(nodeId));
    }
    
    /**
     * Resume a node.
     */
    @PostMapping("/nodes/{nodeId}/resume")
    public ResponseEntity<Map<String, Object>> resumeNode(@PathVariable String nodeId) {
        log.info("Resume node via /api/nodes/{}/resume", nodeId);
        return ResponseEntity.ok(managementService.resumeNode(nodeId));
    }
    
    // ==================== Metrics Endpoints ====================
    
    @GetMapping("/metrics/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceMetrics() {
        return ResponseEntity.ok(managementService.proxyToAnyNode("/api/metrics/performance"));
    }
    
    @GetMapping("/metrics/health")
    public ResponseEntity<Map<String, Object>> getHealthMetrics() {
        return ResponseEntity.ok(managementService.proxyToAnyNode("/api/metrics/health"));
    }
    
    @GetMapping("/metrics/replication")
    public ResponseEntity<Map<String, Object>> getReplicationMetrics() {
        return ResponseEntity.ok(managementService.proxyToAnyNode("/api/metrics/replication"));
    }
    
    @GetMapping("/metrics/events")
    public ResponseEntity<Map<String, Object>> getEventMetrics(
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        String path = String.format("/api/metrics/events?limit=%d", limit);
        if (type != null && !type.isEmpty()) {
            path += "&type=" + type;
        }
        return ResponseEntity.ok(managementService.proxyToAnyNode(path));
    }
    
    @GetMapping("/metrics/snapshots")
    public ResponseEntity<Map<String, Object>> getSnapshotMetrics() {
        return ResponseEntity.ok(managementService.proxyToAnyNode("/api/metrics/snapshots"));
    }
    
    // ==================== Snapshot Endpoints ====================
    
    @PostMapping("/snapshot/create")
    public ResponseEntity<Map<String, Object>> createSnapshot() {
        log.info("Create snapshot via /api/snapshot/create");
        return ResponseEntity.ok(managementService.proxyPostToAnyNode("/api/snapshot/create"));
    }
    
    @GetMapping("/snapshot/info")
    public ResponseEntity<Map<String, Object>> getSnapshotInfo() {
        return ResponseEntity.ok(managementService.proxyToAnyNode("/api/snapshot/info"));
    }
}
