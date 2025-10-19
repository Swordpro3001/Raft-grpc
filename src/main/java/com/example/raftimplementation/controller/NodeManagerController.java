package com.example.raftimplementation.controller;

import com.example.raftimplementation.service.NodeManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/nodes")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class NodeManagerController {
    
    private final NodeManagerService nodeManagerService;
    
    @PostMapping("/{nodeId}/start")
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
    
    @PostMapping("/{nodeId}/stop")
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
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> getAllNodesStatus() {
        return ResponseEntity.ok(nodeManagerService.getAllNodesStatus());
    }
    
    @GetMapping("/{nodeId}/logs")
    public ResponseEntity<Map<String, Object>> getNodeLogs(@PathVariable String nodeId) {
        return ResponseEntity.ok(Map.of(
            "nodeId", nodeId,
            "logs", nodeManagerService.getNodeLogs(nodeId),
            "running", nodeManagerService.isNodeRunning(nodeId)
        ));
    }
}
