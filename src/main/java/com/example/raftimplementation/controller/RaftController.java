package com.example.raftimplementation.controller;

import com.example.raftimplementation.model.RaftEvent;
import com.example.raftimplementation.model.ServerInfo;
import com.example.raftimplementation.service.ClusterManager;
import com.example.raftimplementation.service.RaftNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
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
        try {
            boolean success = raftNode.addServer(serverInfo);
            
            if (success) {
                // Register in cluster manager
                clusterManager.registerServer(serverInfo);
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "Server " + serverInfo.getNodeId() + " addition initiated");
                response.put("nodeId", serverInfo.getNodeId());
                response.put("host", serverInfo.getHost());
                response.put("grpcPort", serverInfo.getGrpcPort());
                response.put("httpPort", serverInfo.getHttpPort());
                
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Failed to add server. Only leader can modify membership."
                ));
            }
        } catch (Exception e) {
            log.error("Error adding server: {}", serverInfo, e);
            
            String errorMessage = e.getMessage();
            if (errorMessage == null) {
                errorMessage = e.getClass().getSimpleName() + " occurred";
            }
            
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "Error adding server: " + errorMessage,
                "error", e.getClass().getSimpleName()
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
    
    /**
     * Suspend Raft participation while keeping Spring Boot running.
     * Useful for temporarily taking a node offline without killing the process.
     */
    @PostMapping("/suspend")
    public ResponseEntity<Map<String, Object>> suspend() {
        raftNode.suspend();
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Node suspended. Spring Boot is still running, but Raft participation paused.",
            "nodeId", raftNode.getConfig().getNodeId(),
            "suspended", true
        ));
    }
    
    /**
     * Resume Raft participation after suspension.
     * Node will rejoin the cluster as a follower.
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume() {
        raftNode.resume();
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Node resumed. Rejoining cluster as FOLLOWER.",
            "nodeId", raftNode.getConfig().getNodeId(),
            "suspended", false
        ));
    }
}
