package com.example.raftimplementation.persistence;

import com.example.raftimplementation.grpc.RaftCodec;
import com.example.raftimplementation.grpc.SnapshotData;
import com.example.raftimplementation.model.LogEntry;
import com.example.raftimplementation.model.Snapshot;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence service for Raft state, log entries, and snapshots.
 * Every write must reach the database before the node answers the RPC that caused it,
 * so failures propagate to the caller instead of being swallowed here.
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
     * Load the persisted log entries that follow the given index, oldest first.
     * Entries at or before the index can remain after a crash between saving a snapshot and
     * compacting. Loading stops at the first gap, because a log must never have holes.
     */
    public List<LogEntry> loadLog(String nodeId, int afterIndex) {
        List<LogEntry> logEntries = new ArrayList<>();
        int expectedIndex = afterIndex + 1;

        for (LogEntryEntity entity : logRepository.findByNodeIdOrderByLogIndexAsc(nodeId)) {
            if (entity.getLogIndex() <= afterIndex) {
                continue;
            }
            if (entity.getLogIndex() != expectedIndex) {
                log.warn("Log of {} has a gap at index {}, dropping everything after it", nodeId, expectedIndex);
                logRepository.deleteByNodeIdAndLogIndexGreaterThanEqual(nodeId, expectedIndex);
                break;
            }
            logEntries.add(toLogEntry(entity));
            expectedIndex++;
        }

        log.info("Loaded {} log entries for {} from persistent storage", logEntries.size(), nodeId);
        return logEntries;
    }

    /**
     * Append log entries with consecutive indices to persistent storage in batch.
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
                RaftCodec.encodeConfiguration(entry.getConfiguration()),
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
        logRepository.deleteByNodeIdAndLogIndexGreaterThanEqual(nodeId, fromIndex);
        log.info("Deleted log entries for {} from index {}", nodeId, fromIndex);
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
     * Load the latest snapshot for a node from database. An unreadable snapshot is fatal:
     * its log entries are gone, so starting without it would silently lose committed data.
     */
    public Optional<Snapshot> loadSnapshot(String nodeId) {
        Optional<SnapshotEntity> entity = snapshotRepository
            .findFirstByNodeIdOrderByLastIncludedIndexDesc(nodeId);

        if (entity.isEmpty()) {
            log.info("No snapshot found for {}", nodeId);
            return Optional.empty();
        }

        SnapshotEntity snapshotEntity = entity.get();
        try {
            SnapshotData data = SnapshotData.parseFrom(snapshotEntity.getSnapshotData());
            Snapshot snapshot = new Snapshot(
                snapshotEntity.getLastIncludedIndex(),
                snapshotEntity.getLastIncludedTerm(),
                RaftCodec.decodeSnapshotState(data),
                RaftCodec.decodeSnapshotConfiguration(data),
                snapshotEntity.getTimestamp(),
                snapshotEntity.getSizeBytes()
            );

            log.info("Loaded snapshot for {}: lastIncludedIndex={}, size={} bytes",
                    nodeId, snapshot.getLastIncludedIndex(), snapshot.getSizeBytes());
            return Optional.of(snapshot);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Snapshot of " + nodeId + " is unreadable. It may have been "
                + "written by an older version: delete data/" + nodeId + " to resync from the leader.", e);
        }
    }

    /**
     * Save a snapshot to persistent storage, keeping only the latest one.
     */
    @Transactional
    public void saveSnapshot(String nodeId, Snapshot snapshot) {
        byte[] snapshotData = RaftCodec.encodeSnapshot(snapshot.getStateMachineState(), snapshot.getConfiguration());

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
        snapshotRepository.deleteByNodeIdAndLastIncludedIndexLessThan(nodeId, snapshot.getLastIncludedIndex());
        log.info("Saved snapshot for {}: lastIncludedIndex={}, size={} bytes",
                nodeId, snapshot.getLastIncludedIndex(), snapshotData.length);
    }

    private LogEntry toLogEntry(LogEntryEntity entity) {
        try {
            return new LogEntry(entity.getTerm(), entity.getCommand(),
                RaftCodec.decodeConfiguration(entity.getConfiguration()));
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Corrupt configuration at log index " + entity.getLogIndex(), e);
        }
    }
}
