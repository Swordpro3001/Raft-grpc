package com.example.raftimplementation.persistence;

import com.example.raftimplementation.model.ClusterConfiguration;
import com.example.raftimplementation.model.LogEntry;
import com.example.raftimplementation.model.ServerInfo;
import com.example.raftimplementation.model.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs against a real (in-memory) H2 database.
 */
@DataJpaTest(properties = "raft.node-id=test")
@ActiveProfiles("node")
@Import(RaftPersistenceService.class)
class RaftPersistenceServiceTest {

    @Autowired
    private RaftPersistenceService persistence;

    @Autowired
    private LogEntryRepository logRepository;

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Test
    void stateIsCreatedOnFirstBootAndUpdated() {
        assertEquals(0, persistence.loadState("n").getCurrentTerm());

        persistence.saveState("n", 7, "other");

        RaftStateEntity state = persistence.loadState("n");
        assertEquals(7, state.getCurrentTerm());
        assertEquals("other", state.getVotedFor());
    }

    @Test
    void logEntriesKeepOrderCommandAndConfiguration() {
        ClusterConfiguration config = ClusterConfiguration.createJoint(
            Set.of("a"), Set.of("a", "b"), Map.of("b", new ServerInfo("b", "host", 9, 8)));
        persistence.appendLogEntries("n", 0, List.of(
            new LogEntry(1, "first"),
            new LogEntry(1, null),
            new LogEntry(2, null, config)));

        List<LogEntry> loaded = persistence.loadLog("n", -1);

        assertEquals("first", loaded.get(0).getCommand());
        assertNull(loaded.get(1).getCommand());
        assertFalse(loaded.get(1).isConfigurationEntry());
        assertEquals(config, loaded.get(2).getConfiguration());
    }

    @Test
    void logsOfDifferentNodesAreSeparate() {
        persistence.appendLogEntries("n", 0, List.of(new LogEntry(1, "mine")));
        persistence.appendLogEntries("other", 0, List.of(new LogEntry(1, "theirs")));

        assertEquals("mine", persistence.loadLog("n", -1).get(0).getCommand());
    }

    @Test
    void entriesCoveredByASnapshotAreIgnoredAfterACrashBeforeCompaction() {
        persistence.appendLogEntries("n", 0, List.of(
            new LogEntry(1, "a"), new LogEntry(1, "b"), new LogEntry(1, "c")));

        List<LogEntry> loaded = persistence.loadLog("n", 1);

        assertEquals(1, loaded.size());
        assertEquals("c", loaded.get(0).getCommand());
    }

    @Test
    void loadingStopsAtAGap() {
        persistence.appendLogEntries("n", 0, List.of(new LogEntry(1, "a")));
        persistence.appendLogEntries("n", 2, List.of(new LogEntry(1, "c")));

        List<LogEntry> loaded = persistence.loadLog("n", -1);

        assertEquals(1, loaded.size());
        assertEquals("a", loaded.get(0).getCommand());
    }

    @Test
    void deleteFromRemovesTheSuffix() {
        persistence.appendLogEntries("n", 0, List.of(
            new LogEntry(1, "a"), new LogEntry(1, "b"), new LogEntry(1, "c")));

        persistence.deleteLogEntriesFrom("n", 1);

        assertEquals(1, persistence.loadLog("n", -1).size());
    }

    @Test
    void compactionRemovesThePrefix() {
        persistence.appendLogEntries("n", 0, List.of(
            new LogEntry(1, "a"), new LogEntry(1, "b"), new LogEntry(1, "c")));

        persistence.compactLog("n", 1);

        assertEquals(1, logRepository.findByNodeIdOrderByLogIndexAsc("n").size());
    }

    @Test
    void snapshotRoundTripKeepsOnlyTheLatest() {
        ClusterConfiguration config = ClusterConfiguration.createNew(
            Set.of("a"), Map.of("a", new ServerInfo("a", "host", 9, 8)));
        persistence.saveSnapshot("n", new Snapshot(3, 1, List.of("old"), config, 1L, 0L));
        persistence.saveSnapshot("n", new Snapshot(8, 2, List.of("x", "y"), config, 2L, 0L));

        Snapshot loaded = persistence.loadSnapshot("n").orElseThrow();

        assertEquals(8, loaded.getLastIncludedIndex());
        assertEquals(2, loaded.getLastIncludedTerm());
        assertEquals(List.of("x", "y"), loaded.getStateMachineState());
        assertEquals(config, loaded.getConfiguration());
        assertEquals(1, snapshotRepository.count());
    }

    @Test
    void unreadableSnapshotIsFatal() {
        snapshotRepository.save(new SnapshotEntity(null, "n", 1, 1, new byte[]{(byte) 0xFF}, 0L, 1L));

        assertThrows(IllegalStateException.class, () -> persistence.loadSnapshot("n"));
    }
}
