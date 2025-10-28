package com.example.raftimplementation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Repository for persisting Raft log entries.
 */
@Repository
public interface LogEntryRepository extends JpaRepository<LogEntryEntity, Long> {
    
    /**
     * Find all log entries for a specific node, ordered by index.
     */
    List<LogEntryEntity> findByNodeIdOrderByLogIndexAsc(String nodeId);
    
    /**
     * Find log entry by node and index.
     */
    LogEntryEntity findByNodeIdAndLogIndex(String nodeId, int logIndex);
    
    /**
     * Delete all log entries for a node up to (and including) a specific index.
     * Used for log compaction after snapshot.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM LogEntryEntity l WHERE l.nodeId = ?1 AND l.logIndex <= ?2")
    void deleteByNodeIdAndLogIndexLessThanEqual(String nodeId, int logIndex);
    
    /**
     * Delete all log entries for a node.
     */
    @Modifying
    @Transactional
    void deleteByNodeId(String nodeId);
    
    /**
     * Count log entries for a node.
     */
    long countByNodeId(String nodeId);
}
