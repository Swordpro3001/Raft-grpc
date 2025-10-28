package com.example.raftimplementation.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persistent storage for Raft log entries.
 * Each entry contains a command for the state machine and the term when entry was received by leader.
 */
@Entity
@Table(name = "raft_log", indexes = {
    @Index(name = "idx_node_index", columnList = "nodeId,logIndex")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogEntryEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Node ID that owns this log entry.
     */
    @Column(nullable = false)
    private String nodeId;
    
    /**
     * Index of this entry in the log (0-based).
     */
    @Column(nullable = false)
    private int logIndex;
    
    /**
     * Term when entry was created.
     */
    @Column(nullable = false)
    private int term;
    
    /**
     * Command to be applied to state machine.
     */
    @Column(nullable = false, length = 1000)
    private String command;
    
    /**
     * Timestamp when entry was created.
     */
    @Column(nullable = false)
    private long timestamp;
}
