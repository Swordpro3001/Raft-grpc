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
 * Management Controller - API for the management node.
 * Completely independent from Raft nodes.
 * Provides endpoints to monitor and manage the entire Raft cluster.
 */
@RestController
@RequestMapping("/api/management")
@ConditionalOnProperty(name = "management.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ManagementController {
    
    private final ManagementService managementService;
    
    /**
     * Get cluster overview (summary statistics).
     */
    @GetMapping("/cluster/overview")
    public ResponseEntity<Map<String, Object>> getClusterOverview() {
        log.info("Getting cluster overview");
        return ResponseEntity.ok(managementService.getClusterOverview());
    }
    
    /**
     * Get status of all nodes.
     */
    @GetMapping("/nodes")
    public ResponseEntity<List<Map<String, Object>>> getAllNodes() {
        log.info("Getting all node status");
        return ResponseEntity.ok(managementService.getAllNodeStatus());
    }
    
    /**
     * Get status of a specific node.
     */
    @GetMapping("/nodes/{nodeId}")
    public ResponseEntity<Map<String, Object>> getNodeStatus(@PathVariable String nodeId) {
        log.info("Getting status for node: {}", nodeId);
        return ResponseEntity.ok(managementService.getNodeStatus(nodeId));
    }
    
    /**
     * Submit a command to the cluster.
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
        
        log.info("Submitting command through management node: {}", command);
        return ResponseEntity.ok(managementService.submitCommand(command));
    }
    
    /**
     * Create a snapshot on a specific node.
     */
    @PostMapping("/nodes/{nodeId}/snapshot")
    public ResponseEntity<Map<String, Object>> createSnapshot(@PathVariable String nodeId) {
        log.info("Creating snapshot on node: {}", nodeId);
        return ResponseEntity.ok(managementService.createSnapshot(nodeId));
    }
    
    /**
     * Suspend a node.
     */
    @PostMapping("/nodes/{nodeId}/suspend")
    public ResponseEntity<Map<String, Object>> suspendNode(@PathVariable String nodeId) {
        log.info("Suspending node: {}", nodeId);
        return ResponseEntity.ok(managementService.suspendNode(nodeId));
    }
    
    /**
     * Resume a node.
     */
    @PostMapping("/nodes/{nodeId}/resume")
    public ResponseEntity<Map<String, Object>> resumeNode(@PathVariable String nodeId) {
        log.info("Resuming node: {}", nodeId);
        return ResponseEntity.ok(managementService.resumeNode(nodeId));
    }
    
    /**
     * Health check for the management node itself.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "Management Node",
            "managedNodes", managementService.getConfiguredNodes().size()
        ));
    }
    
    // ==================== Client Proxy Endpoints (for backward compatibility) ====================
    
    /**
     * Get status from any available node (used by older dashboard code).
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(managementService.getStatusFromAnyNode());
    }
    
    /**
     * Linearizable read - proxy to the leader for strong consistency.
     */
    @GetMapping("/read")
    public ResponseEntity<Map<String, Object>> linearizableRead() {
        log.info("Performing linearizable read through management node");
        return ResponseEntity.ok(managementService.linearizableRead());
    }
    
    // ==================== Metrics Endpoints (proxied to any available node) ====================
    
    /**
     * Get performance metrics from any available node.
     */
    @GetMapping("/metrics/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceMetrics() {
        return ResponseEntity.ok(managementService.proxyToAnyNode("/api/metrics/performance"));
    }
    
    /**
     * Get health metrics from any available node.
     */
    @GetMapping("/metrics/health")
    public ResponseEntity<Map<String, Object>> getHealthMetrics() {
        return ResponseEntity.ok(managementService.proxyToAnyNode("/api/metrics/health"));
    }
    
    /**
     * Get replication metrics from any available node.
     */
    @GetMapping("/metrics/replication")
    public ResponseEntity<Map<String, Object>> getReplicationMetrics() {
        return ResponseEntity.ok(managementService.proxyToAnyNode("/api/metrics/replication"));
    }
    
    /**
     * Get event metrics from any available node.
     */
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
    
    /**
     * Get snapshot statistics from any available node.
     */
    @GetMapping("/metrics/snapshots")
    public ResponseEntity<Map<String, Object>> getSnapshotMetrics() {
        return ResponseEntity.ok(managementService.proxyToAnyNode("/api/metrics/snapshots"));
    }
    
    /**
     * Get snapshot info from any available node.
     */
    @GetMapping("/snapshot/info")
    public ResponseEntity<Map<String, Object>> getSnapshotInfo() {
        return ResponseEntity.ok(managementService.proxyToAnyNode("/api/snapshot/info"));
    }
    
    /**
     * Create snapshot on leader node.
     */
    @PostMapping("/snapshot/create")
    public ResponseEntity<Map<String, Object>> createSnapshotOnLeader() {
        log.info("Creating snapshot on leader through management node");
        return ResponseEntity.ok(managementService.proxyPostToAnyNode("/api/snapshot/create"));
    }
}
