package com.example.raftimplementation.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persistent storage for Raft snapshots.
 * Stores compressed state machine state at specific log index.
 */
@Entity
@Table(name = "raft_snapshot")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Node ID that owns this snapshot.
     */
    @Column(nullable = false)
    private String nodeId;
    
    /**
     * Index of the last entry included in this snapshot.
     */
    @Column(nullable = false)
    private int lastIncludedIndex;
    
    /**
     * Term of the last entry included in this snapshot.
     */
    @Column(nullable = false)
    private int lastIncludedTerm;
    
    /**
     * Serialized snapshot data (state machine state).
     */
    @Lob
    @Column(nullable = false)
    private byte[] snapshotData;
    
    /**
     * Timestamp when snapshot was created.
     */
    @Column(nullable = false)
    private long timestamp;
    
    /**
     * Size in bytes for monitoring.
     */
    @Column(nullable = false)
    private long sizeBytes;
}
