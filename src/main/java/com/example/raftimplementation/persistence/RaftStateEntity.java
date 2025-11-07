package com.example.raftimplementation.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persistent storage for critical Raft state that must survive restarts.
 * Stores currentTerm and votedFor for safety guarantees.
 */
@Entity
@Table(name = "raft_state")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RaftStateEntity {
    
    @Id
    private String nodeId;
    
    /**
     * Latest term server has seen (initialized to 0 on first boot, increases monotonically).
     */
    @Column(nullable = false)
    private int currentTerm;
    
    /**
     * CandidateId that received vote in current term (or null if none).
     */
    @Column
    private String votedFor;
    
    /**
     * Timestamp of last update for monitoring.
     */
    @Column(nullable = false)
    private long lastUpdated;
}
