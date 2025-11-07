package com.example.raftimplementation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for persisting Raft state (currentTerm, votedFor).
 */
@Repository
public interface RaftStateRepository extends JpaRepository<RaftStateEntity, String> {
    
    /**
     * Find state by node ID.
     */
    Optional<RaftStateEntity> findByNodeId(String nodeId);
}
