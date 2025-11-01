package com.example.raftimplementation.persistence;

import com.example.raftimplementation.model.LogEntry;
import com.example.raftimplementation.model.Snapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence service for Raft state, log entries, and snapshots.
 * Handles all database operations with proper error handling and recovery.
 */
@Service
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RaftPersistenceService {
    
    private final RaftStateRepository stateRepository;
    private final LogEntryRepository logRepository;
    private final SnapshotRepository snapshotRepository;
    
    // ==================== State Persistence ====================
    
    /**
     * Load persisted Raft state (currentTerm, votedFor) from database.
     * Returns default values if no state exists (first boot).
     */
    public RaftStateEntity loadState(String nodeId) {
        Optional<RaftStateEntity> state = stateRepository.findByNodeId(nodeId);
        
        if (state.isPresent()) {
            log.info("Loaded persisted state for {}: term={}, votedFor={}", 
                    nodeId, state.get().getCurrentTerm(), state.get().getVotedFor());
            return state.get();
        } else {
            log.info("No persisted state found for {}, starting with defaults", nodeId);
            RaftStateEntity newState = new RaftStateEntity(
                nodeId, 
                0,  // currentTerm = 0
                null,  // votedFor = null
                System.currentTimeMillis()
            );
            return stateRepository.save(newState);
        }
    }
    
    /**
     * Save Raft state (currentTerm, votedFor) to database.
     * This is critical for safety - must be persisted before responding to RPCs.
     */
    @Transactional
    public void saveState(String nodeId, int currentTerm, String votedFor) {
        RaftStateEntity state = stateRepository.findByNodeId(nodeId)
            .orElse(new RaftStateEntity(nodeId, currentTerm, votedFor, System.currentTimeMillis()));
        
        state.setCurrentTerm(currentTerm);
        state.setVotedFor(votedFor);
        state.setLastUpdated(System.currentTimeMillis());
        
        stateRepository.save(state);
        log.debug("Saved state for {}: term={}, votedFor={}", nodeId, currentTerm, votedFor);
    }
    
    // ==================== Log Persistence ====================
    
    /**
     * Load all persisted log entries for a node from database.
     */
    public List<LogEntry> loadLog(String nodeId) {
        List<LogEntryEntity> entities = logRepository.findByNodeIdOrderByLogIndexAsc(nodeId);
        List<LogEntry> logEntries = new ArrayList<>();
        
        for (LogEntryEntity entity : entities) {
            if (entity.getConfiguration() != null) {
                logEntries.add(new LogEntry(entity.getTerm(), entity.getCommand(), entity.getConfiguration()));
            } else {
                logEntries.add(new LogEntry(entity.getTerm(), entity.getCommand()));
            }
        }
        
        log.info("Loaded {} log entries for {} from persistent storage", logEntries.size(), nodeId);
        return logEntries;
    }
    
    /**
     * Append a single log entry to persistent storage.
     */
    @Transactional
    public void appendLogEntry(String nodeId, int logIndex, LogEntry entry) {
        LogEntryEntity entity = new LogEntryEntity(
            null,  // id will be auto-generated
            nodeId,
            logIndex,
            entry.getTerm(),
            entry.getCommand(),
            System.currentTimeMillis()
        );
        
        logRepository.save(entity);
        log.debug("Persisted log entry for {}: index={}, term={}", nodeId, logIndex, entry.getTerm());
    }
    
    /**
     * Append multiple log entries to persistent storage in batch.
     */
    @Transactional
    public void appendLogEntries(String nodeId, int startIndex, List<LogEntry> entries) {
        List<LogEntryEntity> entities = new ArrayList<>();
        
        for (int i = 0; i < entries.size(); i++) {
            LogEntry entry = entries.get(i);
            entities.add(new LogEntryEntity(
                null,
                nodeId,
                startIndex + i,
                entry.getTerm(),
                entry.getCommand(),
                System.currentTimeMillis()
            ));
        }
        
        logRepository.saveAll(entities);
        log.debug("Persisted {} log entries for {} starting at index {}", 
                entries.size(), nodeId, startIndex);
    }
    
    /**
     * Delete log entries from a specific index onwards.
     * Used when log conflicts are detected.
     */
    @Transactional
    public void deleteLogEntriesFrom(String nodeId, int fromIndex) {
        // Find all entries at or after fromIndex and delete them
        List<LogEntryEntity> toDelete = logRepository.findByNodeIdOrderByLogIndexAsc(nodeId)
            .stream()
            .filter(e -> e.getLogIndex() >= fromIndex)
            .toList();
        
        if (!toDelete.isEmpty()) {
            logRepository.deleteAll(toDelete);
            log.info("Deleted {} log entries for {} from index {}", toDelete.size(), nodeId, fromIndex);
        }
    }
    
    /**
     * Compact log by removing entries up to lastIncludedIndex (they're in snapshot).
     */
    @Transactional
    public void compactLog(String nodeId, int lastIncludedIndex) {
        logRepository.deleteByNodeIdAndLogIndexLessThanEqual(nodeId, lastIncludedIndex);
        log.info("Compacted log for {}: removed entries up to index {}", nodeId, lastIncludedIndex);
    }
    
    // ==================== Snapshot Persistence ====================
    
    /**
     * Load the latest snapshot for a node from database.
     */
    public Optional<Snapshot> loadSnapshot(String nodeId) {
        Optional<SnapshotEntity> entity = snapshotRepository
            .findFirstByNodeIdOrderByLastIncludedIndexDesc(nodeId);
        
        if (entity.isEmpty()) {
            log.info("No snapshot found for {}", nodeId);
            return Optional.empty();
        }
        
        try {
            SnapshotEntity snapshotEntity = entity.get();
            
            // Deserialize snapshot data
            ByteArrayInputStream bis = new ByteArrayInputStream(snapshotEntity.getSnapshotData());
            ObjectInputStream ois = new ObjectInputStream(bis);
            
            @SuppressWarnings("unchecked")
            List<String> stateMachineState = (List<String>) ois.readObject();
            
            Snapshot snapshot = new Snapshot(
                snapshotEntity.getLastIncludedIndex(),
                snapshotEntity.getLastIncludedTerm(),
                stateMachineState,
                null,  // ClusterConfiguration not persisted yet
                snapshotEntity.getTimestamp(),
                snapshotEntity.getSizeBytes()
            );
            
            log.info("Loaded snapshot for {}: lastIncludedIndex={}, size={} bytes", 
                    nodeId, snapshot.getLastIncludedIndex(), snapshot.getSizeBytes());
            
            return Optional.of(snapshot);
            
        } catch (Exception e) {
            log.error("Failed to load snapshot for {}: {}", nodeId, e.getMessage(), e);
            return Optional.empty();
        }
    }
    
    /**
     * Save a snapshot to persistent storage.
     */
    @Transactional
    public void saveSnapshot(String nodeId, Snapshot snapshot) {
        try {
            // Serialize snapshot data
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(snapshot.getStateMachineState());
            oos.flush();
            
            byte[] snapshotData = bos.toByteArray();
            
            SnapshotEntity entity = new SnapshotEntity(
                null,  // id will be auto-generated
                nodeId,
                snapshot.getLastIncludedIndex(),
                snapshot.getLastIncludedTerm(),
                snapshotData,
                snapshot.getTimestamp(),
                snapshotData.length
            );
            
            snapshotRepository.save(entity);
            log.info("Saved snapshot for {}: lastIncludedIndex={}, size={} bytes", 
                    nodeId, snapshot.getLastIncludedIndex(), snapshotData.length);
            
            // Clean up old snapshots (keep only the latest)
            cleanupOldSnapshots(nodeId, snapshot.getLastIncludedIndex());
            
        } catch (Exception e) {
            log.error("Failed to save snapshot for {}: {}", nodeId, e.getMessage(), e);
        }
    }
    
    /**
     * Delete old snapshots, keeping only the most recent one.
     */
    @Transactional
    public void cleanupOldSnapshots(String nodeId, int latestIndex) {
        try {
            snapshotRepository.deleteByNodeIdAndLastIncludedIndexLessThan(nodeId, latestIndex);
            log.debug("Cleaned up old snapshots for {}", nodeId);
        } catch (Exception e) {
            log.warn("Failed to cleanup old snapshots for {}: {}", nodeId, e.getMessage());
        }
    }
    
    /**
     * Clear all persisted data for a node (useful for testing/reset).
     */
    @Transactional
    public void clearAllData(String nodeId) {
        stateRepository.deleteById(nodeId);
        logRepository.deleteByNodeId(nodeId);
        
        log.warn("Cleared all persisted data for {}", nodeId);
    }
}
