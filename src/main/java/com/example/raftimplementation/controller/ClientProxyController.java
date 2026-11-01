package com.example.raftimplementation.controller;

import com.example.raftimplementation.service.ClusterClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * JSON API of the dashboard process, for scripts and tools. The dashboard
 * pages use the same operations through DashboardController.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "false")
public class ClientProxyController {

    private final ClusterClient cluster;

    @GetMapping("/cluster/overview")
    public Map<String, Object> getOverview() {
        return cluster.getOverview();
    }

    /**
     * Submits a command to the cluster by routing it to the leader.
     */
    @PostMapping({"/command", "/cluster/command"})
    public ResponseEntity<Map<String, Object>> submitCommand(@RequestBody Map<String, String> request) {
        return toResponse(cluster.submitCommand(request.get("command")));
    }

    @GetMapping("/read")
    public ResponseEntity<Map<String, Object>> read(@RequestParam(required = false) Integer index) {
        return toResponse(cluster.read(index));
    }

    @PostMapping("/snapshot/create")
    public ResponseEntity<Map<String, Object>> createSnapshot() {
        return toResponse(cluster.createSnapshot());
    }

    @PostMapping("/nodes/{nodeId}/start")
    public ResponseEntity<Map<String, Object>> startNode(@PathVariable String nodeId) {
        return toResponse(cluster.startNode(nodeId));
    }

    @PostMapping("/nodes/{nodeId}/stop")
    public ResponseEntity<Map<String, Object>> stopNode(@PathVariable String nodeId) {
        return toResponse(cluster.stopNode(nodeId));
    }

    @PostMapping("/nodes/{nodeId}/suspend")
    public ResponseEntity<Map<String, Object>> suspendNode(@PathVariable String nodeId) {
        return toResponse(cluster.suspendNode(nodeId));
    }

    @PostMapping("/nodes/{nodeId}/resume")
    public ResponseEntity<Map<String, Object>> resumeNode(@PathVariable String nodeId) {
        return toResponse(cluster.resumeNode(nodeId));
    }

    @PostMapping("/cluster/add-node")
    public ResponseEntity<Map<String, Object>> addNode() {
        return toResponse(cluster.addNode());
    }

    @PostMapping("/cluster/remove-node/{nodeId}")
    public ResponseEntity<Map<String, Object>> removeNode(@PathVariable String nodeId) {
        return toResponse(cluster.removeNode(nodeId));
    }

    private static ResponseEntity<Map<String, Object>> toResponse(Map<String, Object> result) {
        Object code = result.get("result");
        if (ClusterClient.NO_LEADER.equals(code)) {
            return ResponseEntity.status(503).body(result);
        }
        if (ClusterClient.INVALID.equals(code)) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}
