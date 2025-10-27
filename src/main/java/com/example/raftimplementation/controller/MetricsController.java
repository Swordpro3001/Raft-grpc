package com.example.raftimplementation.controller;

import com.example.raftimplementation.service.RaftNode;
import com.example.raftimplementation.model.NodeState;
import com.example.raftimplementation.model.RaftEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for Raft cluster performance metrics and monitoring.
 */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class MetricsController {
    
    private final RaftNode raftNode;
    
    /**
     * Get comprehensive performance metrics for the Raft cluster.
     */
    @GetMapping("/performance")
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
     * Get detailed cluster health metrics.
     */
    @GetMapping("/health")
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
        
        // Cluster size
        health.put("clusterSize", raftNode.getStubs().size() + 1);
        health.put("connectedPeers", raftNode.getStubs().size());
        
        // Snapshot info
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
        
        // Log compaction stats
        health.put("compactedEntries", raftNode.getCompactedEntries().get());
        health.put("totalSnapshots", raftNode.getTotalSnapshots().get());
        
        return health;
    }
    
    /**
     * Get replication status for all peers.
     */
    @GetMapping("/replication")
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
                status.put("upToDate", matchIndex != null && matchIndex >= logSize);
                
                peerStatus.put(peerId, status);
            }
            
            replication.put("peers", peerStatus);
            replication.put("role", "LEADER");
        } else {
            replication.put("role", raftNode.getState().toString());
            replication.put("leader", raftNode.getCurrentLeader());
            replication.put("message", "Only leader can provide replication status");
        }
        
        replication.put("nodeId", raftNode.getConfig().getNodeId());
        replication.put("currentTerm", raftNode.getCurrentTerm().get());
        
        return replication;
    }
    
    /**
     * Calculate average election time from recent elections.
     */
    private double calculateAvgElectionTime() {
        List<RaftEvent> events = raftNode.getEvents();
        
        // Find all election cycles (ELECTION_START -> ELECTION_WON/LOST)
        List<Long> electionDurations = new ArrayList<>();
        RaftEvent electionStart = null;
        
        for (RaftEvent event : events) {
            if (event.getType() == RaftEvent.EventType.ELECTION_START) {
                electionStart = event;
            } else if (electionStart != null && 
                      (event.getType() == RaftEvent.EventType.ELECTION_WON || 
                       event.getType() == RaftEvent.EventType.ELECTION_LOST)) {
                // Calculate duration in milliseconds
                long duration = java.time.Duration.between(
                    electionStart.getTimestamp(), 
                    event.getTimestamp()
                ).toMillis();
                electionDurations.add(duration);
                electionStart = null;
            }
        }
        
        if (electionDurations.isEmpty()) {
            return 0.0;
        }
        
        return electionDurations.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0.0);
    }
    
    /**
     * Calculate average replication latency.
     */
    private double calculateReplicationLatency() {
        List<RaftEvent> events = raftNode.getEvents();
        
        // Find COMMAND_RECEIVED -> LOG_REPLICATED pairs
        List<Long> latencies = new ArrayList<>();
        RaftEvent commandReceived = null;
        
        for (RaftEvent event : events) {
            if (event.getType() == RaftEvent.EventType.COMMAND_RECEIVED) {
                commandReceived = event;
            } else if (commandReceived != null && 
                      event.getType() == RaftEvent.EventType.LOG_REPLICATED) {
                long latency = java.time.Duration.between(
                    commandReceived.getTimestamp(),
                    event.getTimestamp()
                ).toMillis();
                latencies.add(latency);
                commandReceived = null;
            }
        }
        
        if (latencies.isEmpty()) {
            return 0.0;
        }
        
        return latencies.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0.0);
    }
    
    /**
     * Calculate throughput (commands per second).
     */
    private double calculateThroughput() {
        List<RaftEvent> events = raftNode.getEvents();
        
        if (events.isEmpty()) {
            return 0.0;
        }
        
        // Count COMMAND_RECEIVED events in the last 60 seconds
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime windowStart = now.minusSeconds(60);
        
        long commandCount = events.stream()
            .filter(e -> e.getType() == RaftEvent.EventType.COMMAND_RECEIVED)
            .filter(e -> e.getTimestamp().isAfter(windowStart))
            .count();
        
        // Calculate per second
        long actualWindow = java.time.Duration.between(
            events.get(0).getTimestamp(), 
            now
        ).toMillis();
        
        actualWindow = Math.min(60000, actualWindow);
        
        if (actualWindow <= 0) {
            return 0.0;
        }
        
        return (commandCount * 1000.0) / actualWindow;
    }
    
    /**
     * Calculate leader stability (percentage of time with consistent leader).
     */
    private double calculateLeaderStability() {
        List<RaftEvent> events = raftNode.getEvents();
        
        if (events.isEmpty()) {
            return 100.0;
        }
        
        // Count leader changes (STATE_CHANGE events involving LEADER)
        long leaderChanges = events.stream()
            .filter(e -> e.getType() == RaftEvent.EventType.STATE_CHANGE)
            .filter(e -> e.getDescription().contains("LEADER"))
            .count();
        
        // Calculate time window
        java.time.LocalDateTime firstEventTime = events.get(0).getTimestamp();
        java.time.LocalDateTime lastEventTime = events.get(events.size() - 1).getTimestamp();
        long totalTime = java.time.Duration.between(firstEventTime, lastEventTime).toMillis();
        
        if (totalTime <= 0) {
            return 100.0;
        }
        
        // Estimate stability: fewer leader changes = more stability
        // Perfect stability (100%) = 0 changes, decrease by 10% per change
        double stability = 100.0 - (leaderChanges * 10.0);
        return Math.max(0.0, Math.min(100.0, stability));
    }
    
    /**
     * Get event history for debugging and monitoring.
     */
    @GetMapping("/events")
    public Map<String, Object> getEventHistory(
            @RequestParam(required = false) RaftEvent.EventType type,
            @RequestParam(defaultValue = "50") int limit) {
        
        List<RaftEvent> events = raftNode.getEvents();
        
        // Filter by type if specified
        List<RaftEvent> filteredEvents = events;
        if (type != null) {
            filteredEvents = events.stream()
                .filter(e -> e.getType() == type)
                .collect(Collectors.toList());
        }
        
        // Limit results
        int size = filteredEvents.size();
        int fromIndex = Math.max(0, size - limit);
        List<RaftEvent> recentEvents = filteredEvents.subList(fromIndex, size);
        
        Map<String, Object> result = new HashMap<>();
        result.put("events", recentEvents);
        result.put("totalCount", size);
        result.put("returnedCount", recentEvents.size());
        
        return result;
    }
    
    /**
     * Get snapshot statistics.
     */
    @GetMapping("/snapshots")
    public Map<String, Object> getSnapshotStats() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("totalSnapshots", raftNode.getTotalSnapshots().get());
        stats.put("compactedEntries", raftNode.getCompactedEntries().get());
        stats.put("currentLogSize", raftNode.getRaftLog().size());
        
        if (raftNode.getLastSnapshot() != null) {
            stats.put("lastSnapshot", Map.of(
                "lastIncludedIndex", raftNode.getLastSnapshot().getLastIncludedIndex(),
                "lastIncludedTerm", raftNode.getLastSnapshot().getLastIncludedTerm(),
                "timestamp", raftNode.getLastSnapshot().getTimestamp(),
                "sizeBytes", raftNode.getLastSnapshot().getSizeBytes(),
                "stateMachineSize", raftNode.getLastSnapshot().getStateMachineState().size()
            ));
        } else {
            stats.put("lastSnapshot", null);
        }
        
        // Calculate compression ratio
        int totalLogicalSize = raftNode.getCompactedEntries().get() + raftNode.getRaftLog().size();
        if (totalLogicalSize > 0) {
            double compressionRatio = (double) raftNode.getRaftLog().size() / totalLogicalSize * 100;
            stats.put("compressionRatio", String.format("%.2f%%", compressionRatio));
        } else {
            stats.put("compressionRatio", "N/A");
        }
        
        return stats;
    }
}
