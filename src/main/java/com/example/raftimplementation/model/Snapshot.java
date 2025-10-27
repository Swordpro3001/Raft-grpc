package com.example.raftimplementation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Represents a snapshot of the state machine at a specific point in the log.
 * Used for log compaction to prevent unbounded log growth.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Snapshot implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * The index of the last entry included in this snapshot (COUNT-based).
     */
    private int lastIncludedIndex;
    
    /**
     * The term of the last entry included in this snapshot.
     */
    private int lastIncludedTerm;
    
    /**
     * The state machine state at lastIncludedIndex.
     */
    private List<String> stateMachineState;
    
    /**
     * The cluster configuration at the time of snapshot.
     */
    private ClusterConfiguration configuration;
    
    /**
     * Timestamp when this snapshot was created.
     */
    private long timestamp;
    
    /**
     * Size of the snapshot in bytes (for monitoring).
     */
    private long sizeBytes;
}
