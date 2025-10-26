package com.example.raftimplementation.controller;

import com.example.raftimplementation.model.RaftEvent;
import com.example.raftimplementation.model.ServerInfo;
import com.example.raftimplementation.service.ClusterManager;
import com.example.raftimplementation.service.RaftNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RaftController {
    
    private final RaftNode raftNode;
    private final ClusterManager clusterManager;
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = raftNode.getStatus();
        
        // Update cluster manager with current leader
        String currentLeader = raftNode.getCurrentLeader();
        if (currentLeader != null) {
            clusterManager.setCurrentLeader(currentLeader);
        }
        
        return ResponseEntity.ok(status);
    }
    
    @PostMapping("/command")
    public ResponseEntity<Map<String, Object>> submitCommand(@RequestBody Map<String, String> request) {
        String command = request.get("command");
        
        if (command == null || command.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "Command is required"));
        }
        
        boolean success = raftNode.submitCommand(command);
        
        if (success) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Command submitted successfully"
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Not a leader. Current leader: " + raftNode.getCurrentLeader()
            ));
        }
    }
    
    @GetMapping("/events")
    public ResponseEntity<List<RaftEvent>> getEvents() {
        return ResponseEntity.ok(raftNode.getEvents());
    }
    
    /**
     * Add a server to the cluster (membership change).
     * This should only be called on the leader.
     */
    @PostMapping("/membership/add")
    public ResponseEntity<Map<String, Object>> addServer(@RequestBody ServerInfo serverInfo) {
        boolean success = raftNode.addServer(serverInfo);
        
        if (success) {
            // Register in cluster manager
            clusterManager.registerServer(serverInfo);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Server " + serverInfo.getNodeId() + " addition initiated",
                "server", serverInfo
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Failed to add server. Only leader can modify membership."
            ));
        }
    }
    
    /**
     * Remove a server from the cluster (membership change).
     * This should only be called on the leader.
     */
    @PostMapping("/membership/remove/{nodeId}")
    public ResponseEntity<Map<String, Object>> removeServer(@PathVariable String nodeId) {
        boolean success = raftNode.removeServer(nodeId);
        
        if (success) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Server " + nodeId + " removal initiated"
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Failed to remove server. Only leader can modify membership."
            ));
        }
    }
    
    /**
     * Get the current cluster members.
     */
    @GetMapping("/membership/members")
    public ResponseEntity<Map<String, Object>> getClusterMembers() {
        Set<String> members = raftNode.getClusterMembers();
        
        return ResponseEntity.ok(Map.of(
            "members", members,
            "clusterSize", members.size()
        ));
    }
}
