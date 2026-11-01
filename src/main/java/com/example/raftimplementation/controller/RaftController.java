package com.example.raftimplementation.controller;

import com.example.raftimplementation.model.CommandResult;
import com.example.raftimplementation.model.NodeState;
import com.example.raftimplementation.model.RaftEvent;
import com.example.raftimplementation.model.ServerInfo;
import com.example.raftimplementation.model.Snapshot;
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
 * REST API of a single Raft node: client commands, membership changes, snapshots and metrics.
 * Only active when running as a Raft node (raft.node.enabled=true).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class RaftController {

    private static final int MAX_COMMAND_LENGTH = 1000;

    private final RaftNode raftNode;
    private final RestTemplate restTemplate;

    // ==================== Core Raft Operations ====================

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(raftNode.getStatus());
    }

    /**
     * Submits a command to this node, which must be the leader. Answers once the command is
     * committed, so a success means it will survive any failure Raft tolerates.
     */
    @PostMapping("/command")
    public ResponseEntity<Map<String, Object>> submitCommand(@RequestBody Map<String, String> request) {
        String command = request.get("command");

        if (command == null || command.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "Command is required"));
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "Command is longer than " + MAX_COMMAND_LENGTH + " characters"));
        }

        CommandResult result = raftNode.submitCommand(command);

        return ResponseEntity.ok(Map.of(
            "success", result == CommandResult.COMMITTED,
            "result", result.name(),
            "message", describe(result)
        ));
    }

    private String describe(CommandResult result) {
        return switch (result) {
            case COMMITTED -> "Command committed";
            case NOT_LEADER -> "Not a leader. Current leader: " + raftNode.getCurrentLeader();
            case SUSPENDED -> "Node is suspended";
            case LEADERSHIP_LOST -> "Lost leadership before the command committed. It may or may not take effect.";
            case TIMEOUT -> "No quorum confirmed the command in time. It may still take effect.";
        };
    }

    /**
     * Reads the state machine from the leader.
     *
     * @param index Optional specific index to read
     * @param strict If true (default), performs a linearizable read that confirms leadership with
     *               a quorum first. If false, performs a faster read that may return stale data.
     */
    @GetMapping("/read")
    public ResponseEntity<Map<String, Object>> linearizableRead(
            @RequestParam(required = false) Integer index,
            @RequestParam(required = false, defaultValue = "true") boolean strict) {

        if (raftNode.getState() != NodeState.LEADER) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Not a leader. Current leader: " + raftNode.getCurrentLeader()
            ));
        }

        if (strict && !raftNode.confirmLeadership()) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Could not confirm leadership with a quorum"
            ));
        }

        List<String> stateMachine = raftNode.getStateMachine();

        if (index == null) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "stateMachine", stateMachine,
                "size", stateMachine.size(),
                "linearizable", strict,
                "leader", raftNode.getConfig().getNodeId()
            ));
        }
        if (index < 0 || index >= stateMachine.size()) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Index out of bounds",
                "index", index,
                "size", stateMachine.size()
            ));
        }
        return ResponseEntity.ok(Map.of(
            "success", true,
            "index", index,
            "value", stateMachine.get(index),
            "linearizable", strict,
            "leader", raftNode.getConfig().getNodeId()
        ));
    }

    /**
     * The latest log entries of this node, with its commit and snapshot positions.
     */
    @GetMapping("/log")
    public Map<String, Object> getLog(@RequestParam(defaultValue = "100") int limit) {
        return raftNode.getLogView(Math.max(1, limit));
    }
    
    @GetMapping("/events")
    public ResponseEntity<List<RaftEvent>> getEvents() {
        return ResponseEntity.ok(raftNode.getEvents());
    }

    /**
     * Submit command via cluster (auto-routes to leader).
     */
    @PostMapping("/cluster/command")
    public ResponseEntity<Map<String, Object>> submitCommandToCluster(@RequestBody Map<String, String> request) {
        if (raftNode.getState() == NodeState.LEADER) {
            return submitCommand(request);
        }

        String leader = raftNode.getCurrentLeader();
        ServerInfo leaderInfo = leader != null ? raftNode.getServerInfo(leader) : null;
        if (leaderInfo != null) {
            try {
                String leaderUrl = String.format("http://%s:%d/api/command", leaderInfo.getHost(), leaderInfo.getHttpPort());
                @SuppressWarnings("unchecked")
                Map<String, Object> body = restTemplate.postForObject(leaderUrl, request, Map.class);
                return ResponseEntity.ok(body);
            } catch (Exception e) {
                log.warn("Failed to forward command to leader {}: {}", leader, e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of(
            "success", false,
            "message", "Not a leader and couldn't forward to leader",
            "currentLeader", leader != null ? leader : "unknown"
        ));
    }

    // ==================== Membership ====================

    /**
     * Add a server to the cluster. Must be called on the leader.
     */
    @PostMapping("/membership/add")
    public ResponseEntity<Map<String, Object>> addServer(@RequestBody ServerInfo serverInfo) {
        if (serverInfo.getNodeId() == null || serverInfo.getHost() == null || serverInfo.getGrpcPort() <= 0) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "nodeId, host and grpcPort are required"));
        }
        boolean success = raftNode.addServer(serverInfo);
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success
                ? "Server " + serverInfo.getNodeId() + " addition initiated"
                : "Failed to add server. Needs the leader, a server that is not a member yet, and no other change in progress."
        ));
    }

    /**
     * Remove a server from the cluster. Must be called on the leader.
     */
    @PostMapping("/membership/remove/{nodeId}")
    public ResponseEntity<Map<String, Object>> removeServer(@PathVariable String nodeId) {
        boolean success = raftNode.removeServer(nodeId);
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success
                ? "Server " + nodeId + " removal initiated"
                : "Failed to remove server. Needs the leader, a current member, and no other change in progress."
        ));
    }

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
     * Get performance metrics. Commit latency and throughput are measured on the leader.
     */
    @GetMapping("/metrics/performance")
    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("avgElectionTime", calculateAvgElectionTime());
        metrics.put("avgCommitLatency", raftNode.getAverageCommitLatencyMs());
        metrics.put("throughput", raftNode.getCommandsPerSecond());
        metrics.put("leaderUptime", raftNode.getLeaderUptimeMs());
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
        Map<String, Object> health = new HashMap<>(raftNode.getStatus());
        health.remove("stateMachine");
        health.put("stateMachineSize", raftNode.getStateMachine().size());
        health.put("clusterSize", raftNode.getClusterMembers().size());
        health.put("snapshot", describe(raftNode.getLastSnapshot()));
        health.put("compactedEntries", raftNode.getCompactedEntries());
        health.put("totalSnapshots", raftNode.getTotalSnapshots().get());
        return health;
    }

    /**
     * Get replication status for all peers. Only the leader tracks it.
     */
    @GetMapping("/metrics/replication")
    public Map<String, Object> getReplicationStatus() {
        boolean isLeader = raftNode.getState() == NodeState.LEADER;

        Map<String, Object> replication = new HashMap<>();
        replication.put("peers", isLeader ? raftNode.getReplicationStatus() : Map.of());
        replication.put("isLeader", isLeader);
        replication.put("role", raftNode.getState().name());
        replication.put("currentLeader", raftNode.getCurrentLeader());
        replication.put("nodeId", raftNode.getConfig().getNodeId());
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
        int compactedEntries = raftNode.getCompactedEntries();
        int currentLogSize = raftNode.getRaftLog().size();

        stats.put("totalSnapshots", raftNode.getTotalSnapshots().get());
        stats.put("compactedEntries", compactedEntries);
        stats.put("currentLogSize", currentLogSize);
        stats.put("lastSnapshot", describe(raftNode.getLastSnapshot()));

        int totalEntries = compactedEntries + currentLogSize;
        double compressionRatio = totalEntries > 0 ? (double) compactedEntries / totalEntries * 100 : 0;
        stats.put("compressionRatio", String.format(Locale.ROOT, "%.1f%%", compressionRatio));
        stats.put("nodeId", raftNode.getConfig().getNodeId());

        return stats;
    }

    // ==================== Snapshot Operations ====================

    /**
     * Manually create a snapshot.
     */
    @PostMapping("/snapshot/create")
    public Map<String, Object> createSnapshot() {
        int beforeSize = raftNode.getRaftLog().size();
        raftNode.createSnapshot();
        int afterSize = raftNode.getRaftLog().size();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("entriesCompacted", beforeSize - afterSize);
        result.put("newLogSize", afterSize);
        result.put("snapshot", describe(raftNode.getLastSnapshot()));
        return result;
    }

    /**
     * Get current snapshot information.
     */
    @GetMapping("/snapshot/info")
    public Map<String, Object> getSnapshotInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("exists", raftNode.getLastSnapshot() != null);
        info.put("snapshot", describe(raftNode.getLastSnapshot()));
        info.put("currentLogSize", raftNode.getRaftLog().size());
        info.put("totalSnapshots", raftNode.getTotalSnapshots().get());
        info.put("nodeId", raftNode.getConfig().getNodeId());
        return info;
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> describe(Snapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> info = new HashMap<>();
        info.put("lastIncludedIndex", snapshot.getLastIncludedIndex());
        info.put("lastIncludedTerm", snapshot.getLastIncludedTerm());
        info.put("timestamp", snapshot.getTimestamp());
        info.put("sizeBytes", snapshot.getSizeBytes());
        info.put("stateMachineSize", snapshot.getStateMachineState().size());
        return info;
    }

    private double calculateAvgElectionTime() {
        List<RaftEvent> allEvents = raftNode.getEvents();
        List<Long> durations = new ArrayList<>();

        // Match each ELECTION_START with the ELECTION_WON or ELECTION_LOST that ends it
        for (int i = 0; i < allEvents.size(); i++) {
            RaftEvent event = allEvents.get(i);
            if (event.getType() == RaftEvent.EventType.ELECTION_START) {
                for (int j = i + 1; j < allEvents.size(); j++) {
                    RaftEvent nextEvent = allEvents.get(j);
                    if (nextEvent.getType() == RaftEvent.EventType.ELECTION_WON ||
                        nextEvent.getType() == RaftEvent.EventType.ELECTION_LOST) {
                        durations.add(java.time.Duration.between(
                            event.getTimestamp(),
                            nextEvent.getTimestamp()
                        ).toMillis());
                        break;
                    }
                }
            }
        }

        return durations.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }
}
