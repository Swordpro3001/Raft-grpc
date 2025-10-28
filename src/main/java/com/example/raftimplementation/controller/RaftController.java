package com.example.raftimplementation.controller;

import com.example.raftimplementation.model.NodeState;
import com.example.raftimplementation.model.RaftEvent;
import com.example.raftimplementation.model.ServerInfo;
import com.example.raftimplementation.model.Snapshot;
import com.example.raftimplementation.service.ClusterManager;
import com.example.raftimplementation.service.NodeManagerService;
import com.example.raftimplementation.service.RaftNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified REST controller for all Raft node operations.
 * Includes: Core Raft, Metrics, Snapshots, and Node Management.
 * Only active when running as a Raft node (raft.node.enabled=true).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class RaftController {
    
    private final RaftNode raftNode;
    private final ClusterManager clusterManager;
    private final NodeManagerService nodeManagerService;
    private final RestTemplate restTemplate;
    
    // ==================== Core Raft Operations ====================
    
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
    
    // ==================== Metrics & Monitoring ====================
    
    /**
     * Get comprehensive performance metrics.
     */
    @GetMapping("/metrics/performance")
    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("avgElectionTime", calculateAvgElectionTime());
        metrics.put("avgReplicationLatency", calculateReplicationLatency());
        metrics.put("throughput", calculateThroughput());
        metrics.put("leaderStability", calculateLeaderStability());
        metrics.put("timestamp", System.currentTimeMillis());
        metrics.put("nodeId", raftNode.getConfig().getNodeId());
        metrics.put("currentState", raftNode.getState());
        return metrics;
    }
    
    /**
     * Get cluster health metrics.
     */
    @GetMapping("/metrics/health")
    public Map<String, Object> getHealthMetrics() {
        Map<String, Object> health = new HashMap<>();
        health.put("nodeId", raftNode.getConfig().getNodeId());
        health.put("state", raftNode.getState());
        health.put("currentTerm", raftNode.getCurrentTerm().get());
        health.put("currentLeader", raftNode.getCurrentLeader());
        health.put("logSize", raftNode.getRaftLog().size());
        health.put("commitIndex", raftNode.getCommitIndex().get());
        health.put("lastApplied", raftNode.getLastApplied().get());
        health.put("stateMachineSize", raftNode.getStateMachine().size());
        health.put("suspended", raftNode.isSuspended());
        health.put("clusterSize", raftNode.getStubs().size() + 1);
        health.put("connectedPeers", raftNode.getStubs().size());
        
        if (raftNode.getLastSnapshot() != null) {
            Map<String, Object> snapshotInfo = new HashMap<>();
            snapshotInfo.put("lastIncludedIndex", raftNode.getLastSnapshot().getLastIncludedIndex());
            snapshotInfo.put("lastIncludedTerm", raftNode.getLastSnapshot().getLastIncludedTerm());
            snapshotInfo.put("timestamp", raftNode.getLastSnapshot().getTimestamp());
            snapshotInfo.put("sizeBytes", raftNode.getLastSnapshot().getSizeBytes());
            health.put("snapshot", snapshotInfo);
        } else {
            health.put("snapshot", null);
        }
        
        health.put("compactedEntries", raftNode.getCompactedEntries().get());
        health.put("totalSnapshots", raftNode.getTotalSnapshots().get());
        return health;
    }
    
    /**
     * Get replication status for all peers.
     * Shows peer information from leader's perspective, or cluster info from follower's view.
     */
    @GetMapping("/metrics/replication")
    public Map<String, Object> getReplicationStatus() {
        Map<String, Object> replication = new HashMap<>();
        
        if (raftNode.getState() == NodeState.LEADER) {
            Map<String, Map<String, Object>> peerStatus = new HashMap<>();
            
            for (String peerId : raftNode.getStubs().keySet()) {
                Map<String, Object> status = new HashMap<>();
                Integer nextIndex = raftNode.getNextIndex().get(peerId);
                Integer matchIndex = raftNode.getMatchIndex().get(peerId);
                int logSize = raftNode.getRaftLog().size();
                
                status.put("nextIndex", nextIndex);
                status.put("matchIndex", matchIndex);
                status.put("lag", logSize - (matchIndex != null ? matchIndex : 0));
                status.put("upToDate", matchIndex != null && matchIndex >= logSize - 1);
                
                peerStatus.put(peerId, status);
            }
            
            replication.put("peers", peerStatus);
            replication.put("isLeader", true);
            replication.put("role", "LEADER");
        } else {
            // For followers, show cluster membership information
            Map<String, Map<String, Object>> clusterInfo = new HashMap<>();
            
            // Add information about known peers
            for (String peerId : raftNode.getStubs().keySet()) {
                Map<String, Object> peerInfo = new HashMap<>();
                peerInfo.put("status", "Known Peer");
                peerInfo.put("role", peerId.equals(raftNode.getCurrentLeader()) ? "LEADER" : "FOLLOWER");
                clusterInfo.put(peerId, peerInfo);
            }
            
            // Add current leader info
            String currentLeader = raftNode.getCurrentLeader();
            if (currentLeader != null && !clusterInfo.containsKey(currentLeader)) {
                Map<String, Object> leaderInfo = new HashMap<>();
                leaderInfo.put("status", "Current Leader");
                leaderInfo.put("role", "LEADER");
                clusterInfo.put(currentLeader, leaderInfo);
            }
            
            replication.put("peers", clusterInfo);
            replication.put("isLeader", false);
            replication.put("role", raftNode.getState().name());
            replication.put("currentLeader", currentLeader);
        }
        
        replication.put("nodeId", raftNode.getConfig().getNodeId());
        replication.put("currentState", raftNode.getState());
        return replication;
    }
    
    /**
     * Get filtered event history.
     */
    @GetMapping("/metrics/events")
    public Map<String, Object> getEventMetrics(
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        
        List<RaftEvent> events = raftNode.getEvents();
        
        if (type != null && !type.isEmpty()) {
            try {
                RaftEvent.EventType eventType = RaftEvent.EventType.valueOf(type);
                events = events.stream()
                    .filter(e -> e.getType() == eventType)
                    .collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid event type: {}", type);
            }
        }
        
        if (events.size() > limit) {
            events = events.subList(Math.max(0, events.size() - limit), events.size());
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("events", events);
        result.put("totalCount", events.size());
        result.put("nodeId", raftNode.getConfig().getNodeId());
        return result;
    }
    
    /**
     * Get snapshot statistics.
     */
    @GetMapping("/metrics/snapshots")
    public Map<String, Object> getSnapshotMetrics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSnapshots", raftNode.getTotalSnapshots().get());
        stats.put("compactedEntries", raftNode.getCompactedEntries().get());
        stats.put("currentLogSize", raftNode.getRaftLog().size());
        
        if (raftNode.getLastSnapshot() != null) {
            Snapshot lastSnap = raftNode.getLastSnapshot();
            Map<String, Object> snapshotInfo = new HashMap<>();
            snapshotInfo.put("lastIncludedIndex", lastSnap.getLastIncludedIndex());
            snapshotInfo.put("lastIncludedTerm", lastSnap.getLastIncludedTerm());
            snapshotInfo.put("timestamp", lastSnap.getTimestamp());
            snapshotInfo.put("sizeBytes", lastSnap.getSizeBytes());
            stats.put("lastSnapshot", snapshotInfo);
        }
        
        int totalEntries = raftNode.getCompactedEntries().get() + raftNode.getRaftLog().size();
        double compressionRatio = totalEntries > 0 
            ? (double) raftNode.getCompactedEntries().get() / totalEntries * 100 
            : 0;
        stats.put("compressionRatio", String.format("%.1f%%", compressionRatio));
        stats.put("nodeId", raftNode.getConfig().getNodeId());
        
        return stats;
    }
    
    // ==================== Snapshot Operations ====================
    
    /**
     * Manually create a snapshot.
     */
    @PostMapping("/snapshot/create")
    public Map<String, Object> createSnapshot() {
        try {
            int beforeSize = raftNode.getRaftLog().size();
            raftNode.createSnapshot();
            int afterSize = raftNode.getRaftLog().size();
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("entriesCompacted", beforeSize - afterSize);
            result.put("newLogSize", afterSize);
            
            if (raftNode.getLastSnapshot() != null) {
                result.put("snapshotIndex", raftNode.getLastSnapshot().getLastIncludedIndex());
                result.put("snapshotTerm", raftNode.getLastSnapshot().getLastIncludedTerm());
            }
            
            return result;
        } catch (Exception e) {
            log.error("Failed to create snapshot", e);
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }
    
    /**
     * Get current snapshot information.
     */
    @GetMapping("/snapshot/info")
    public Map<String, Object> getSnapshotInfo() {
        Map<String, Object> info = new HashMap<>();
        
        if (raftNode.getLastSnapshot() != null) {
            Snapshot snapshot = raftNode.getLastSnapshot();
            info.put("exists", true);
            info.put("lastIncludedIndex", snapshot.getLastIncludedIndex());
            info.put("lastIncludedTerm", snapshot.getLastIncludedTerm());
            info.put("timestamp", snapshot.getTimestamp());
            info.put("sizeBytes", snapshot.getSizeBytes());
            info.put("stateMachineSize", snapshot.getStateMachineState().size());
        } else {
            info.put("exists", false);
            info.put("message", "No snapshot created yet");
        }
        
        info.put("currentLogSize", raftNode.getRaftLog().size());
        info.put("totalSnapshots", raftNode.getTotalSnapshots().get());
        info.put("nodeId", raftNode.getConfig().getNodeId());
        
        return info;
    }
    
    /**
     * Install a snapshot (for testing or recovery).
     */
    @PostMapping("/snapshot/install")
    public Map<String, Object> installSnapshot(@RequestBody Snapshot snapshot) {
        try {
            raftNode.installSnapshot(snapshot);
            return Map.of(
                "success", true,
                "message", "Snapshot installed successfully",
                "snapshotIndex", snapshot.getLastIncludedIndex()
            );
        } catch (Exception e) {
            log.error("Failed to install snapshot", e);
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }
    
    // ==================== Node Management ====================
    
    /**
     * Submit command via cluster (auto-routes to leader).
     */
    @PostMapping("/cluster/command")
    public ResponseEntity<Map<String, Object>> submitCommandToCluster(@RequestBody Map<String, String> request) {
        String command = request.get("command");
        
        if (command == null || command.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "Command is required"));
        }
        
        if (raftNode.getState() == NodeState.LEADER) {
            return submitCommand(request);
        } else {
            String leader = raftNode.getCurrentLeader();
            if (leader != null) {
                try {
                    String leaderUrl = getNodeHttpUrl(leader) + "/api/command";
                    @SuppressWarnings("rawtypes")
                    ResponseEntity<Map> response = restTemplate.postForEntity(
                        leaderUrl, 
                        request, 
                        Map.class
                    );
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = response.getBody();
                    return ResponseEntity.ok(body);
                } catch (Exception e) {
                    log.error("Failed to forward command to leader", e);
                }
            }
            
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Not a leader and couldn't forward to leader",
                "currentLeader", leader != null ? leader : "unknown"
            ));
        }
    }
    
    /**
     * Start a node process.
     */
    @PostMapping("/nodes/{nodeId}/start")
    public ResponseEntity<Map<String, Object>> startNode(@PathVariable String nodeId) {
        try {
            boolean success = nodeManagerService.startNode(nodeId);
            if (success) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Node " + nodeId + " started successfully"
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Failed to start node " + nodeId
                ));
            }
        } catch (Exception e) {
            log.error("Error starting node {}", nodeId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Stop a node process.
     */
    @PostMapping("/nodes/{nodeId}/stop")
    public ResponseEntity<Map<String, Object>> stopNode(@PathVariable String nodeId) {
        try {
            nodeManagerService.stopNode(nodeId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Node " + nodeId + " stopped successfully"
            ));
        } catch (Exception e) {
            log.error("Error stopping node {}", nodeId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Suspend a node's Raft participation.
     */
    @PostMapping("/nodes/{nodeId}/suspend")
    public ResponseEntity<Map<String, Object>> suspendNode(@PathVariable String nodeId) {
        if (raftNode.getConfig().getNodeId().equals(nodeId)) {
            return suspend();
        }
        
        try {
            String url = getNodeHttpUrl(nodeId) + "/api/suspend";
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.postForEntity(url, null, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("Failed to suspend node {}", nodeId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Resume a node's Raft participation.
     */
    @PostMapping("/nodes/{nodeId}/resume")
    public ResponseEntity<Map<String, Object>> resumeNode(@PathVariable String nodeId) {
        if (raftNode.getConfig().getNodeId().equals(nodeId)) {
            return resume();
        }
        
        try {
            String url = getNodeHttpUrl(nodeId) + "/api/resume";
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.postForEntity(url, null, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("Failed to resume node {}", nodeId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Add a node to the cluster.
     */
    @PostMapping("/cluster/add-node")
    public ResponseEntity<Map<String, Object>> addNodeToCluster(@RequestBody ServerInfo serverInfo) {
        return addServer(serverInfo);
    }
    
    /**
     * Remove a node from the cluster.
     */
    @PostMapping("/cluster/remove-node")
    public ResponseEntity<Map<String, Object>> removeNodeFromCluster(@RequestBody Map<String, String> request) {
        String nodeId = request.get("nodeId");
        if (nodeId == null || nodeId.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "nodeId is required"));
        }
        return removeServer(nodeId);
    }
    
    /**
     * Get cluster configuration.
     */
    @GetMapping("/cluster/configuration")
    public ResponseEntity<Map<String, Object>> getClusterConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("members", raftNode.getClusterMembers());
        config.put("clusterSize", raftNode.getClusterMembers().size());
        config.put("currentLeader", raftNode.getCurrentLeader());
        config.put("nodeId", raftNode.getConfig().getNodeId());
        return ResponseEntity.ok(config);
    }
    
    /**
     * Get node logs.
     */
    @GetMapping("/nodes/{nodeId}/logs")
    public ResponseEntity<Map<String, Object>> getNodeLogs(@PathVariable String nodeId) {
        List<String> logs = nodeManagerService.getNodeLogs(nodeId);
        return ResponseEntity.ok(Map.of(
            "nodeId", nodeId,
            "logs", logs,
            "logCount", logs.size()
        ));
    }
    
    // ==================== Helper Methods ====================
    
    private double calculateAvgElectionTime() {
        List<RaftEvent> allEvents = raftNode.getEvents();
        List<Long> durations = new ArrayList<>();
        
        // Find all ELECTION_START events and match them with the next ELECTION_WON or ELECTION_LOST
        for (int i = 0; i < allEvents.size(); i++) {
            RaftEvent event = allEvents.get(i);
            if (event.getType() == RaftEvent.EventType.ELECTION_START) {
                // Look for the matching completion event
                for (int j = i + 1; j < allEvents.size(); j++) {
                    RaftEvent nextEvent = allEvents.get(j);
                    if (nextEvent.getType() == RaftEvent.EventType.ELECTION_WON || 
                        nextEvent.getType() == RaftEvent.EventType.ELECTION_LOST) {
                        long duration = java.time.Duration.between(
                            event.getTimestamp(),
                            nextEvent.getTimestamp()
                        ).toMillis();
                        durations.add(duration);
                        break;
                    }
                }
            }
        }
        
        return durations.isEmpty() ? 0.0 : 
               durations.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }
    
    private double calculateReplicationLatency() {
        List<RaftEvent> replications = raftNode.getEvents().stream()
            .filter(e -> e.getType() == RaftEvent.EventType.LOG_REPLICATED)
            .collect(Collectors.toList());
        
        if (replications.size() < 2) return 0.0;
        
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < replications.size() - 1; i++) {
            long latency = java.time.Duration.between(
                replications.get(i).getTimestamp(),
                replications.get(i + 1).getTimestamp()
            ).toMillis();
            latencies.add(latency);
        }
        
        return latencies.isEmpty() ? 0.0 :
               latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }
    
    private double calculateThroughput() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime oneMinuteAgo = now.minusMinutes(1);
        
        // Count actual committed commands in the last minute
        // We look for COMMAND_RECEIVED events that happened in the last minute
        long commandsInLastMinute = raftNode.getEvents().stream()
            .filter(e -> e.getType() == RaftEvent.EventType.COMMAND_RECEIVED)
            .filter(e -> e.getTimestamp().isAfter(oneMinuteAgo))
            .count();
        
        // Alternative: count state machine changes (more accurate for committed commands)
        // This counts actual applied entries, not just received commands
        Map<String, Object> status = raftNode.getStatus();
        Integer commitIndex = (Integer) status.get("commitIndex");
        Integer lastApplied = (Integer) status.get("lastApplied");
        
        // If we have applied entries, we can estimate throughput based on recent activity
        // For a more accurate measure, we count command events
        if (commandsInLastMinute > 0) {
            return commandsInLastMinute / 60.0; // commands per second
        }
        
        // If no recent commands but we have committed entries, show that the system is active
        if (commitIndex != null && commitIndex > 0) {
            // We have data but no recent activity, return 0
            return 0.0;
        }
        
        return 0.0;
    }
    
    private double calculateLeaderStability() {
        long leaderChanges = raftNode.getEvents().stream()
            .filter(e -> e.getType() == RaftEvent.EventType.STATE_CHANGE)
            .filter(e -> e.getDescription().contains("LEADER"))
            .count();
        
        return Math.max(0, 100 - (leaderChanges * 10));
    }
    
    private String getNodeHttpUrl(String nodeId) {
        ServerInfo server = clusterManager.getServer(nodeId);
        if (server != null && server.getHttpPort() > 0) {
            return String.format("http://%s:%d", server.getHost(), server.getHttpPort());
        }
        
        int portNum = Integer.parseInt(nodeId.replaceAll("\\D+", ""));
        return String.format("http://localhost:%d", 8080 + portNum);
    }
}
