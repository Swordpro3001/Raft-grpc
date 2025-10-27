package com.example.raftimplementation.controller;

import com.example.raftimplementation.service.RaftNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for snapshot operations.
 */
@RestController
@RequestMapping("/api/snapshot")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class SnapshotController {
    
    private final RaftNode raftNode;
    
    /**
     * Manually trigger snapshot creation.
     */
    @PostMapping("/create")
    public Map<String, Object> createSnapshot() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            raftNode.createSnapshot();
            
            response.put("success", true);
            response.put("message", "Snapshot created successfully");
            
            if (raftNode.getLastSnapshot() != null) {
                response.put("snapshot", Map.of(
                    "lastIncludedIndex", raftNode.getLastSnapshot().getLastIncludedIndex(),
                    "lastIncludedTerm", raftNode.getLastSnapshot().getLastIncludedTerm(),
                    "timestamp", raftNode.getLastSnapshot().getTimestamp(),
                    "sizeBytes", raftNode.getLastSnapshot().getSizeBytes()
                ));
            }
            
        } catch (Exception e) {
            log.error("Failed to create snapshot", e);
            response.put("success", false);
            response.put("message", "Failed to create snapshot: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Get snapshot information.
     */
    @GetMapping("/info")
    public Map<String, Object> getSnapshotInfo() {
        Map<String, Object> info = new HashMap<>();
        
        if (raftNode.getLastSnapshot() != null) {
            info.put("hasSnapshot", true);
            info.put("lastIncludedIndex", raftNode.getLastSnapshot().getLastIncludedIndex());
            info.put("lastIncludedTerm", raftNode.getLastSnapshot().getLastIncludedTerm());
            info.put("timestamp", raftNode.getLastSnapshot().getTimestamp());
            info.put("sizeBytes", raftNode.getLastSnapshot().getSizeBytes());
            info.put("stateMachineSize", raftNode.getLastSnapshot().getStateMachineState().size());
            
            if (raftNode.getLastSnapshot().getConfiguration() != null) {
                info.put("configurationServers", 
                    raftNode.getLastSnapshot().getConfiguration().getNewServers().size());
            }
        } else {
            info.put("hasSnapshot", false);
            info.put("message", "No snapshot available");
        }
        
        info.put("totalSnapshots", raftNode.getTotalSnapshots().get());
        info.put("compactedEntries", raftNode.getCompactedEntries().get());
        info.put("currentLogSize", raftNode.getRaftLog().size());
        info.put("logBaseIndex", raftNode.getLogBaseIndex());
        info.put("logBaseTerm", raftNode.getLogBaseTerm());
        
        return info;
    }
}
