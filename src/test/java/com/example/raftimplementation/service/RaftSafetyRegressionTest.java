package com.example.raftimplementation.service;

import com.example.raftimplementation.config.RaftConfig;
import com.example.raftimplementation.grpc.AppendEntriesRequest;
import com.example.raftimplementation.grpc.AppendEntriesResponse;
import com.example.raftimplementation.grpc.GrpcLogEntry;
import com.example.raftimplementation.grpc.VoteRequest;
import com.example.raftimplementation.model.LogEntry;
import com.example.raftimplementation.model.NodeState;
import com.example.raftimplementation.persistence.RaftPersistenceService;
import com.example.raftimplementation.persistence.RaftStateEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * One test per protocol bug found while auditing RaftNode against the Raft paper.
 */
class RaftSafetyRegressionTest {

    private RaftNode node;
    private RaftConfig config;
    private RaftPersistenceService persistence;

    @BeforeEach
    void setUp() {
        config = new RaftConfig();
        config.setNodeId("node1");
        config.setGrpcPort(9091);
        config.setHttpPort(8081);
        config.setPeers(new ArrayList<>());

        persistence = mock(RaftPersistenceService.class);
        when(persistence.loadState(anyString())).thenReturn(new RaftStateEntity("node1", 0, null, 0L));
        when(persistence.loadSnapshot(anyString())).thenReturn(Optional.empty());

        node = new RaftNode(config, persistence);
    }

    @AfterEach
    void tearDown() {
        node.shutdown();
    }

    /** Snapshot must physically remove the compacted prefix (logical indices stay aligned). */
    @Test
    void snapshotCompactsTheLog() {
        append(1, -1, 0, 4, "a", "b", "c", "d", "e");

        node.createSnapshot();

        assertEquals(0, node.getRaftLog().size(), "compacted entries must leave the in-memory log");
        assertEquals(5, node.getStatus().get("logSize"), "logical log size must not change");
    }

    /** Figure 2: a server votes for at most one candidate per term, even after stepping down. */
    @Test
    void candidateSteppingDownInSameTermKeepsItsVote() throws Exception {
        setField("state", NodeState.CANDIDATE);
        node.getCurrentTerm().set(2);
        setField("votedFor", "node1");

        append(2, -1, 0, -1);
        assertEquals(NodeState.FOLLOWER, node.getState());

        assertFalse(node.handleVoteRequest(vote(2, "node3")).getVoteGranted(),
            "node already voted for itself in term 2");
    }

    /** Figure 2: commitIndex = min(leaderCommit, index of last NEW entry), not the last log entry. */
    @Test
    void followerDoesNotCommitEntriesTheLeaderDidNotVerify() {
        append(1, -1, 0, -1, "a", "b", "c");

        append(1, 0, 1, 2);

        assertEquals(0, node.getCommitIndex().get(),
            "entries after prevLogIndex are unverified and possibly stale");
    }

    /** Re-delivered entries must not shift the persisted index of the entries that follow them. */
    @Test
    void overlappingAppendPersistsAtCorrectIndex() {
        append(1, -1, 0, -1, "a");
        clearInvocations(persistence);

        append(1, -1, 0, -1, "a", "b");

        ArgumentCaptor<List<LogEntry>> persisted = ArgumentCaptor.captor();
        verify(persistence).appendLogEntries(eq("node1"), eq(1), persisted.capture());
        assertEquals("b", persisted.getValue().get(0).getCommand());
    }

    /** Suspending must not forget the vote of the current term. */
    @Test
    void suspendAndResumeKeepTheVote() {
        assertTrue(node.handleVoteRequest(vote(2, "node2")).getVoteGranted());

        node.suspend();
        node.resume();

        assertFalse(node.handleVoteRequest(vote(2, "node3")).getVoteGranted());
    }

    /** A success response reports what the leader's request verified, not the whole local log. */
    @Test
    void matchIndexOnlyCoversVerifiedEntries() {
        append(1, -1, 0, -1, "a", "b", "c");

        AppendEntriesResponse response = append(1, 0, 1, -1);

        assertTrue(response.getSuccess());
        assertEquals(0, response.getMatchIndex());
    }

    /** Section 5.3: a conflicting entry and everything after it go, in memory and on disk. */
    @Test
    void conflictingEntriesAreTruncated() {
        append(1, -1, 0, -1, "a", "b", "c");

        append(2, 0, 1, -1, "x");

        verify(persistence).deleteLogEntriesFrom("node1", 1);
        assertEquals(List.of("a", "x"), node.getRaftLog().stream().map(LogEntry::getCommand).toList());
    }

    /** Figure 3 (State Machine Safety): an applied entry can never be replaced. */
    @Test
    void committedEntriesAreNeverTruncated() {
        append(1, -1, 0, 1, "a", "b");

        assertThrows(IllegalStateException.class, () -> append(2, 0, 1, -1, "x"));
        assertEquals(List.of("a", "b"), node.getStateMachine());
    }

    @Test
    void rejectionTellsTheLeaderWhereToContinue() {
        append(1, -1, 0, -1, "a", "b");

        AppendEntriesResponse tooFarAhead = append(1, 9, 1, -1);
        assertFalse(tooFarAhead.getSuccess());
        assertEquals(2, tooFarAhead.getConflictIndex());
        assertEquals(0, tooFarAhead.getConflictTerm());

        AppendEntriesResponse wrongTerm = append(2, 1, 2, -1);
        assertFalse(wrongTerm.getSuccess());
        assertEquals(0, wrongTerm.getConflictIndex(), "first entry of the conflicting term");
        assertEquals(1, wrongTerm.getConflictTerm());
    }

    /** Thesis 9.6: pre-vote only succeeds if the server has lost contact with its leader. */
    @Test
    void preVoteIsDeniedWhileTheLeaderIsAlive() {
        append(1, -1, 0, -1);

        assertFalse(node.handlePreVote(vote(2, "node3")).getVoteGranted());
    }

    @Test
    void preVoteIsGrantedWithoutChangingAnyState() {
        assertTrue(node.handlePreVote(vote(1, "node3")).getVoteGranted());

        assertEquals(0, node.getCurrentTerm().get());
        assertNull(node.getVotedFor());
        verify(persistence, never()).saveState(any(), anyInt(), any());
    }

    @Test
    void preVoteIsDeniedForAnOutdatedLog() {
        config.setElectionTimeoutMinMs(0);
        append(1, -1, 0, -1, "a");

        assertFalse(node.handlePreVote(vote(2, "node3")).getVoteGranted());
    }

    private AppendEntriesResponse append(int term, int prevIndex, int prevTerm, int leaderCommit, String... commands) {
        AppendEntriesRequest.Builder request = AppendEntriesRequest.newBuilder()
            .setTerm(term)
            .setLeaderId("node2")
            .setPrevLogIndex(prevIndex)
            .setPrevLogTerm(prevTerm)
            .setLeaderCommit(leaderCommit);
        for (String command : commands) {
            request.addEntries(GrpcLogEntry.newBuilder().setTerm(term).setCommand(command));
        }
        return node.handleAppendEntries(request.build());
    }

    private VoteRequest vote(int term, String candidate) {
        return VoteRequest.newBuilder()
            .setTerm(term)
            .setCandidateId(candidate)
            .setLastLogIndex(-1)
            .setLastLogTerm(0)
            .build();
    }

    private void setField(String name, Object value) throws Exception {
        Field field = RaftNode.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(node, value);
    }
}
