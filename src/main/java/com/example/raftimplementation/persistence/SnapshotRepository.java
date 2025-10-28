package com.example.raftimplementation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for persisting Raft snapshots.
 */
@Repository
public interface SnapshotRepository extends JpaRepository<SnapshotEntity, Long> {
    
    /**
     * Find the latest snapshot for a node.
     */
    Optional<SnapshotEntity> findFirstByNodeIdOrderByLastIncludedIndexDesc(String nodeId);
    
    /**
     * Delete all snapshots for a node except the most recent one.
     * Used to clean up old snapshots.
     */
    void deleteByNodeIdAndLastIncludedIndexLessThan(String nodeId, int lastIncludedIndex);
}
