package com.example.raftimplementation.service;

import com.example.raftimplementation.config.RaftConfig;
import com.example.raftimplementation.grpc.RaftGrpcService;
import com.example.raftimplementation.model.CommandResult;
import com.example.raftimplementation.model.LogEntry;
import com.example.raftimplementation.model.NodeState;
import com.example.raftimplementation.model.ServerInfo;
import com.example.raftimplementation.model.Snapshot;
import com.example.raftimplementation.persistence.RaftPersistenceService;
import com.example.raftimplementation.persistence.RaftStateEntity;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs real nodes that talk to each other over real gRPC connections on this
 * machine. Only the database is replaced, by a store that survives "crashes".
 */
@Timeout(60)
class RaftClusterIntegrationTest {

    private static final long AWAIT_TIMEOUT_MS = 20_000;

    private final List<TestNode> nodes = new ArrayList<>();

    @AfterEach
    void stopAll() {
        nodes.forEach(TestNode::stop);
    }

    @Test
    void electsExactlyOneLeader() {
        startCluster(3, 100);

        TestNode leader = awaitLeader();

        await("the others to follow " + leader.id, () -> nodes.stream()
            .filter(node -> node != leader)
            .allMatch(node -> leader.id.equals(node.raft.getCurrentLeader())));
        assertEquals(1, nodes.stream().filter(node -> node.raft.getState() == NodeState.LEADER).count());
    }

    @Test
    void singleNodeClusterWorksOnItsOwn() {
        startCluster(1, 100);

        TestNode leader = awaitLeader();

        assertEquals(CommandResult.COMMITTED, leader.raft.submitCommand("alone"));
        assertEquals(List.of("alone"), leader.raft.getStateMachine());
        assertTrue(leader.raft.confirmLeadership());
    }

    @Test
    void committedCommandsReachEveryStateMachineInOrder() {
        startCluster(3, 100);
        TestNode leader = awaitLeader();

        List<String> commands = IntStream.range(0, 20).mapToObj(i -> "cmd-" + i).toList();
        for (String command : commands) {
            assertEquals(CommandResult.COMMITTED, leader.raft.submitCommand(command));
        }

        awaitStateMachines(commands, nodes);
    }

    @Test
    void newLeaderKeepsCommittedEntriesAndOldLeaderCatchesUp() {
        startCluster(3, 100);
        TestNode oldLeader = awaitLeader();
        int oldTerm = oldLeader.raft.getCurrentTerm().get();
        assertEquals(CommandResult.COMMITTED, oldLeader.raft.submitCommand("before"));

        oldLeader.stop();
        TestNode newLeader = awaitLeader();
        assertNotSame(oldLeader, newLeader);
        assertTrue(newLeader.raft.getCurrentTerm().get() > oldTerm);
        assertEquals(CommandResult.COMMITTED, newLeader.raft.submitCommand("after"));

        oldLeader.start();
        awaitStateMachines(List.of("before", "after"), nodes);
        assertEquals(NodeState.FOLLOWER, oldLeader.raft.getState());
    }

    @Test
    void wholeClusterRecoversFromStorageAfterACrash() {
        startCluster(3, 100);
        TestNode leader = awaitLeader();
        int term = leader.raft.getCurrentTerm().get();
        assertEquals(CommandResult.COMMITTED, leader.raft.submitCommand("durable"));

        nodes.forEach(TestNode::stop);
        nodes.forEach(TestNode::start);

        awaitStateMachines(List.of("durable"), nodes);
        assertTrue(awaitLeader().raft.getCurrentTerm().get() > term, "terms must never go backwards");
    }

    @Test
    void leaderWithoutQuorumCannotCommitOrServeReads() {
        startCluster(3, 100);
        TestNode leader = awaitLeader();
        nodes.stream().filter(node -> node != leader).forEach(TestNode::stop);

        leader.raft.getConfig().setCommandTimeoutMs(500);

        assertEquals(CommandResult.TIMEOUT, leader.raft.submitCommand("lost"));
        assertFalse(leader.raft.confirmLeadership());
        assertEquals(List.of(), leader.raft.getStateMachine());
    }

    @Test
    void linearizableReadSucceedsWithQuorum() {
        startCluster(3, 100);
        TestNode leader = awaitLeader();
        await("the no-op of the new term to commit", leader.raft::confirmLeadership);

        nodes.stream().filter(node -> node != leader).findFirst().orElseThrow().stop();

        assertTrue(leader.raft.confirmLeadership(), "one follower plus the leader is a quorum");
    }

    @Test
    void suspendedFollowerDoesNotDisruptTheLeader() throws Exception {
        startCluster(3, 100);
        TestNode leader = awaitLeader();
        int term = leader.raft.getCurrentTerm().get();
        TestNode follower = nodes.stream().filter(node -> node != leader).findFirst().orElseThrow();

        // Long enough for several election timeouts
        follower.raft.suspend();
        Thread.sleep(800);
        follower.raft.resume();
        Thread.sleep(800);

        assertEquals(NodeState.LEADER, leader.raft.getState());
        assertEquals(term, leader.raft.getCurrentTerm().get());
        assertEquals(term, follower.raft.getCurrentTerm().get());
    }

    @Test
    void suspendedLeaderIsReplacedAndRejoinsAsFollower() {
        startCluster(3, 100);
        TestNode oldLeader = awaitLeader();

        oldLeader.raft.suspend();
        TestNode newLeader = awaitLeader();
        assertEquals(CommandResult.COMMITTED, newLeader.raft.submitCommand("while suspended"));

        oldLeader.raft.resume();
        awaitStateMachines(List.of("while suspended"), nodes);
        assertEquals(NodeState.FOLLOWER, oldLeader.raft.getState());
    }

    @Test
    void laggingFollowerCatchesUpFromSnapshot() {
        startCluster(3, 10);
        TestNode leader = awaitLeader();
        TestNode follower = nodes.stream().filter(node -> node != leader).findFirst().orElseThrow();
        follower.stop();

        List<String> commands = IntStream.range(0, 35).mapToObj(i -> "cmd-" + i).toList();
        for (String command : commands) {
            assertEquals(CommandResult.COMMITTED, leader.raft.submitCommand(command));
        }
        assertNotNull(leader.raft.getLastSnapshot(), "the leader must have compacted its log by now");
        assertTrue(leader.raft.getRaftLog().size() < 15);

        follower.start();

        awaitStateMachines(commands, nodes);
        assertNotNull(follower.raft.getLastSnapshot(), "the follower must have received a snapshot");

        // Replication has to continue normally on top of the snapshot
        assertEquals(CommandResult.COMMITTED, leader.raft.submitCommand("after snapshot"));
        List<String> all = new ArrayList<>(commands);
        all.add("after snapshot");
        awaitStateMachines(all, nodes);
    }

    @Test
    void snapshotSurvivesRestart() {
        startCluster(3, 10);
        TestNode leader = awaitLeader();
        List<String> commands = IntStream.range(0, 25).mapToObj(i -> "cmd-" + i).toList();
        for (String command : commands) {
            assertEquals(CommandResult.COMMITTED, leader.raft.submitCommand(command));
        }
        awaitStateMachines(commands, nodes);

        nodes.forEach(TestNode::stop);
        nodes.forEach(TestNode::start);

        awaitStateMachines(commands, nodes);
        assertEquals(CommandResult.COMMITTED, awaitLeader().raft.submitCommand("more"));
    }

    @Test
    void addedServerReceivesTheLogAndCountsTowardsTheQuorum() {
        startCluster(3, 100);
        TestNode leader = awaitLeader();
        assertEquals(CommandResult.COMMITTED, leader.raft.submitCommand("before"));
        await("the leader to be able to change membership", () -> leader.raft.addServer(joiningAddress()));

        TestNode newNode = startJoiningNode();

        await("the change to finish", () -> nodes.stream().allMatch(node ->
            node.raft.getClusterMembers().size() == 4 && !isJoint(node)));
        awaitStateMachines(List.of("before"), nodes);

        // All old servers but one are gone: only the new server can complete a quorum of 3 out of 4
        TestNode survivor = nodes.stream().filter(node -> node != leader && node != newNode).findFirst().orElseThrow();
        survivor.stop();
        assertEquals(CommandResult.COMMITTED, leader.raft.submitCommand("after"));
        awaitStateMachines(List.of("before", "after"), List.of(leader, newNode));
    }

    @Test
    void removedFollowerStopsCountingAndCannotDisruptTheCluster() throws Exception {
        startCluster(3, 100);
        TestNode leader = awaitLeader();
        TestNode removed = nodes.stream().filter(node -> node != leader).findFirst().orElseThrow();
        await("the no-op of the new term to commit", leader.raft::confirmLeadership);

        assertTrue(leader.raft.removeServer(removed.id));
        await("the change to finish", () -> leader.raft.getClusterMembers().size() == 2 && !isJoint(leader));

        int term = leader.raft.getCurrentTerm().get();
        Thread.sleep(800);
        assertEquals(NodeState.LEADER, leader.raft.getState());
        assertEquals(term, leader.raft.getCurrentTerm().get());
        assertEquals(CommandResult.COMMITTED, leader.raft.submitCommand("smaller cluster"));
    }

    @Test
    void leaderCanRemoveItselfAndStepsDown() {
        startCluster(3, 100);
        TestNode oldLeader = awaitLeader();
        await("the no-op of the new term to commit", oldLeader.raft::confirmLeadership);

        assertTrue(oldLeader.raft.removeServer(oldLeader.id));

        await("the old leader to step down", () -> oldLeader.raft.getState() == NodeState.FOLLOWER);
        await("a new leader among the rest", () -> nodes.stream()
            .anyMatch(node -> node != oldLeader && node.raft.getState() == NodeState.LEADER));
        TestNode newLeader = nodes.stream()
            .filter(node -> node != oldLeader && node.raft.getState() == NodeState.LEADER)
            .findFirst().orElseThrow();
        assertEquals(CommandResult.COMMITTED, newLeader.raft.submitCommand("without the old leader"));
        assertEquals(Set.of(newLeader.id, nodes.stream()
            .filter(node -> node != oldLeader && node != newLeader).findFirst().orElseThrow().id),
            newLeader.raft.getClusterMembers());
    }

    @Test
    void onlyOneMembershipChangeAtATime() {
        startCluster(3, 100);
        TestNode leader = awaitLeader();
        await("the no-op of the new term to commit", leader.raft::confirmLeadership);
        // Take away the quorum so that the first change cannot finish
        nodes.stream().filter(node -> node != leader).forEach(TestNode::stop);

        assertTrue(leader.raft.removeServer(nodes.stream().filter(n -> n != leader).findFirst().orElseThrow().id));
        assertFalse(leader.raft.addServer(new ServerInfo("node9", "localhost", 1, 1)));
    }

    // ==================== Helpers ====================

    private void startCluster(int size, int snapshotThreshold) {
        List<RaftConfig.PeerConfig> peers = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            RaftConfig.PeerConfig peer = new RaftConfig.PeerConfig();
            peer.setNodeId("node" + i);
            peer.setHost("localhost");
            peer.setGrpcPort(freePort());
            peers.add(peer);
        }
        for (RaftConfig.PeerConfig peer : peers) {
            RaftConfig config = baseConfig(peer.getNodeId(), peer.getGrpcPort(), snapshotThreshold);
            config.setPeers(peers);
            nodes.add(new TestNode(config));
        }
        nodes.forEach(TestNode::start);
    }

    private int joiningPort = -1;

    private ServerInfo joiningAddress() {
        if (joiningPort < 0) {
            joiningPort = freePort();
        }
        return new ServerInfo("node4", "localhost", joiningPort, 0);
    }

    private TestNode startJoiningNode() {
        RaftConfig config = baseConfig("node4", joiningPort, 100);
        config.setJoin(true);
        TestNode node = new TestNode(config);
        nodes.add(node);
        node.start();
        return node;
    }

    private RaftConfig baseConfig(String nodeId, int grpcPort, int snapshotThreshold) {
        RaftConfig config = new RaftConfig();
        config.setNodeId(nodeId);
        config.setGrpcPort(grpcPort);
        config.setElectionTimeoutMinMs(150);
        config.setElectionTimeoutMaxMs(300);
        config.setHeartbeatIntervalMs(40);
        config.setRpcTimeoutMs(200);
        config.setCommandTimeoutMs(5000);
        config.setSnapshotThreshold(snapshotThreshold);
        return config;
    }

    private TestNode awaitLeader() {
        await("a leader", () -> currentLeader().isPresent());
        return currentLeader().orElseThrow();
    }

    /** The leader of the highest term; a deposed leader may not have noticed yet. */
    private Optional<TestNode> currentLeader() {
        return nodes.stream()
            .filter(TestNode::isRunning)
            .filter(node -> node.raft.getState() == NodeState.LEADER)
            .max((a, b) -> a.raft.getCurrentTerm().get() - b.raft.getCurrentTerm().get());
    }

    private void awaitStateMachines(List<String> expected, List<TestNode> targets) {
        await("state machines to contain " + expected.size() + " commands", () -> targets.stream()
            .allMatch(node -> expected.equals(node.raft.getStateMachine())));
    }

    private boolean isJoint(TestNode node) {
        return (boolean) node.raft.getStatus().get("jointConsensus");
    }

    private void await(String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("Timed out waiting for " + description);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class TestNode {
        final String id;
        final RaftConfig config;
        final InMemoryPersistence persistence = new InMemoryPersistence();
        RaftNode raft;
        Server server;

        TestNode(RaftConfig config) {
            this.id = config.getNodeId();
            this.config = config;
        }

        void start() {
            raft = new RaftNode(config, persistence);
            raft.init();
            try {
                server = NettyServerBuilder.forPort(config.getGrpcPort())
                    .addService(new RaftGrpcService(raft))
                    .build()
                    .start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void stop() {
            if (server != null) {
                server.shutdownNow();
                try {
                    server.awaitTermination();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                server = null;
                raft.shutdown();
            }
        }

        boolean isRunning() {
            return server != null;
        }
    }

    /** Stores what the database would, across restarts of a node. */
    private static final class InMemoryPersistence extends RaftPersistenceService {
        private final TreeMap<Integer, LogEntry> log = new TreeMap<>();
        private int term = 0;
        private String votedFor = null;
        private Snapshot snapshot = null;

        InMemoryPersistence() {
            super(null, null, null);
        }

        @Override
        public synchronized RaftStateEntity loadState(String nodeId) {
            return new RaftStateEntity(nodeId, term, votedFor, 0L);
        }

        @Override
        public synchronized void saveState(String nodeId, int currentTerm, String vote) {
            term = currentTerm;
            votedFor = vote;
        }

        @Override
        public synchronized List<LogEntry> loadLog(String nodeId, int afterIndex) {
            return new ArrayList<>(log.tailMap(afterIndex, false).values());
        }

        @Override
        public synchronized void appendLogEntries(String nodeId, int startIndex, List<LogEntry> entries) {
            for (int i = 0; i < entries.size(); i++) {
                assertNull(log.put(startIndex + i, entries.get(i)), "an entry was overwritten without truncating");
            }
        }

        @Override
        public synchronized void deleteLogEntriesFrom(String nodeId, int fromIndex) {
            log.tailMap(fromIndex, true).clear();
        }

        @Override
        public synchronized void compactLog(String nodeId, int lastIncludedIndex) {
            log.headMap(lastIncludedIndex, true).clear();
        }

        @Override
        public synchronized Optional<Snapshot> loadSnapshot(String nodeId) {
            return Optional.ofNullable(snapshot);
        }

        @Override
        public synchronized void saveSnapshot(String nodeId, Snapshot newSnapshot) {
            snapshot = newSnapshot;
        }
    }
}
