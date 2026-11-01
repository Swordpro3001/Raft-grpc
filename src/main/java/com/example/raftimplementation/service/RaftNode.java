package com.example.raftimplementation.service;

import com.example.raftimplementation.config.RaftConfig;
import com.example.raftimplementation.grpc.*;
import com.example.raftimplementation.model.*;
import com.example.raftimplementation.persistence.RaftPersistenceService;
import com.example.raftimplementation.persistence.RaftStateEntity;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The Raft consensus algorithm (leader election, log replication, membership changes, snapshots).
 *
 * Concurrency: all consensus state is guarded by this object's monitor. Timers, RPC handlers and
 * RPC callbacks each take the monitor, change state, and start any outgoing RPCs asynchronously,
 * so no thread ever waits for the network while holding it. State is written to the database
 * while the monitor is held, which guarantees that it is durable before any reply is sent.
 *
 * Log indices are logical and start at 0; -1 means "no entry". The in-memory log only holds the
 * entries after the latest snapshot, see {@link #getLogBaseIndex()}.
 */
@Service
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class RaftNode {
    public static final int MAX_GRPC_MESSAGE_BYTES = 64 * 1024 * 1024;

    private static final int MAX_EVENTS = 100;
    private static final int MAX_ENTRIES_PER_APPEND = 256;
    private static final long THROUGHPUT_WINDOW_MS = 60_000;
    private static final double LATENCY_SMOOTHING = 0.2;

    @Getter
    private final RaftConfig config;
    private final RaftPersistenceService persistenceService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "raft-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, ServerInfo> knownServers = new ConcurrentHashMap<>();

    @Getter
    private volatile NodeState state = NodeState.FOLLOWER;
    @Getter
    private final AtomicInteger currentTerm = new AtomicInteger(0);
    @Getter
    private volatile String votedFor = null;
    @Getter
    private volatile String currentLeader = null;
    private final List<LogEntry> raftLog = new ArrayList<>();
    @Getter
    private final AtomicInteger commitIndex = new AtomicInteger(-1);
    @Getter
    private final AtomicInteger lastApplied = new AtomicInteger(-1);

    private final Map<String, Integer> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Integer> matchIndex = new ConcurrentHashMap<>();

    private final List<String> stateMachine = new ArrayList<>();

    // Membership: the latest configuration in the log is always the one in effect
    private ClusterConfiguration bootstrapConfiguration = new ClusterConfiguration();
    private ClusterConfiguration configuration = bootstrapConfiguration;
    private int configurationIndex = -1;

    // Snapshot state for log compaction
    @Getter
    private volatile Snapshot lastSnapshot = null;
    @Getter
    private final AtomicInteger totalSnapshots = new AtomicInteger(0);

    // Clients waiting for their command or read to be confirmed by a quorum
    private final SortedMap<Integer, PendingCommand> pendingCommands = new TreeMap<>();
    private final List<ReadBarrier> readBarriers = new ArrayList<>();
    private long rpcSequence = 0;

    private final Deque<RaftEvent> events = new ArrayDeque<>();
    private final Deque<Long> recentCommits = new ArrayDeque<>();
    private double averageCommitLatencyMs = 0;
    private long leaderSinceMillis = 0;

    private volatile long lastLeaderContact = 0;
    private ScheduledFuture<?> electionTask;
    private ScheduledFuture<?> heartbeatTask;
    private long electionTimerGeneration = 0;
    // Bumped whenever an election round is over, so that late responses to it are ignored
    private long electionEpoch = 0;

    // Suspension state: keeps Spring Boot running but pauses Raft participation
    @Getter
    private volatile boolean suspended = false;

    public RaftNode(RaftConfig config, RaftPersistenceService persistenceService) {
        this.config = config;
        this.persistenceService = persistenceService;
    }

    // ==================== Lifecycle ====================

    @PostConstruct
    public synchronized void init() {
        String self = config.getNodeId();
        if (self == null || self.isBlank()) {
            throw new IllegalStateException("raft.node-id is required to run as a Raft node");
        }
        log.info("Initializing Raft node: {}", self);

        RaftStateEntity persistedState = persistenceService.loadState(self);
        currentTerm.set(persistedState.getCurrentTerm());
        votedFor = persistedState.getVotedFor();

        persistenceService.loadSnapshot(self).ifPresent(snapshot -> {
            lastSnapshot = snapshot;
            stateMachine.addAll(snapshot.getStateMachineState());
            commitIndex.set(snapshot.getLastIncludedIndex());
            lastApplied.set(snapshot.getLastIncludedIndex());
        });
        raftLog.addAll(persistenceService.loadLog(self, snapshotIndex()));
        log.info("Recovered term={}, votedFor={}, log up to index {}", currentTerm.get(), votedFor, lastLogIndex());

        if (!config.isJoin()) {
            Map<String, ServerInfo> servers = config.initialServers();
            bootstrapConfiguration = ClusterConfiguration.createNew(servers.keySet(), servers);
        }
        reloadConfiguration();
        resetElectionTimer();
    }

    @PreDestroy
    public synchronized void shutdown() {
        log.info("Shutting down Raft node: {}", config.getNodeId());
        cancelTimers();
        failPendingRequests();
        scheduler.shutdownNow();
        peers.values().forEach(peer -> peer.channel.shutdownNow());
    }

    /**
     * Suspends Raft participation while keeping Spring Boot application running.
     * The term and vote stay as they are: forgetting them would allow voting twice in a term.
     */
    public synchronized void suspend() {
        if (suspended) {
            log.warn("Node {} is already suspended", config.getNodeId());
            return;
        }
        suspended = true;
        cancelTimers();
        if (state == NodeState.LEADER) {
            failPendingRequests();
        }
        state = NodeState.FOLLOWER;
        currentLeader = null;
        logEvent(RaftEvent.EventType.STATE_CHANGE, "Node suspended, Raft paused");
    }

    /**
     * Resumes Raft participation after suspension.
     */
    public synchronized void resume() {
        if (!suspended) {
            log.warn("Node {} is not suspended", config.getNodeId());
            return;
        }
        suspended = false;
        resetElectionTimer();
        logEvent(RaftEvent.EventType.STATE_CHANGE, "Node resumed, rejoining cluster as FOLLOWER");
    }

    // ==================== Leader Election ====================

    private void resetElectionTimer() {
        if (suspended || scheduler.isShutdown()) {
            return;
        }
        if (electionTask != null) {
            electionTask.cancel(false);
        }
        long generation = ++electionTimerGeneration;
        int timeout = ThreadLocalRandom.current().nextInt(config.getElectionTimeoutMinMs(), config.getElectionTimeoutMaxMs() + 1);
        electionTask = scheduler.schedule(() -> onElectionTimeout(generation), timeout, TimeUnit.MILLISECONDS);
    }

    private synchronized void onElectionTimeout(long generation) {
        // The timer may have fired just while another thread was resetting it
        if (generation != electionTimerGeneration || suspended || state == NodeState.LEADER) {
            return;
        }
        try {
            if (state == NodeState.CANDIDATE) {
                logEvent(RaftEvent.EventType.ELECTION_LOST, "No majority for term " + currentTerm.get());
            }
            // Servers that are not part of the configuration must not disturb the cluster
            if (configuration.contains(config.getNodeId())) {
                startPreVote();
            }
        } catch (RuntimeException e) {
            log.error("Election attempt failed", e);
        } finally {
            if (state != NodeState.LEADER) {
                resetElectionTimer();
            }
        }
    }

    /**
     * Pre-vote (Raft thesis, section 9.6): find out whether an election could succeed before
     * increasing the term, so a server that was partitioned away cannot depose a healthy leader.
     */
    private void startPreVote() {
        long epoch = ++electionEpoch;
        currentLeader = null;

        Set<String> granted = new HashSet<>(Set.of(config.getNodeId()));
        if (configuration.hasQuorum(granted)) {
            startElection();
            return;
        }

        VoteRequest request = buildVoteRequest(currentTerm.get() + 1);
        for (Peer peer : votingPeers()) {
            peer.stub(config.getRpcTimeoutMs()).preVote(request, responseHandler(
                response -> onPreVoteResponse(epoch, peer, granted, response),
                error -> onRpcFailure(peer, "PreVote", error)));
        }
    }

    private synchronized void onPreVoteResponse(long epoch, Peer peer, Set<String> granted, VoteResponse response) {
        if (response.getTerm() > currentTerm.get()) {
            stepDown(response.getTerm());
            return;
        }
        if (epoch != electionEpoch || state == NodeState.LEADER || suspended || !response.getVoteGranted()) {
            return;
        }
        granted.add(peer.id);
        if (configuration.hasQuorum(granted)) {
            startElection();
        }
    }

    private void startElection() {
        int newTerm = currentTerm.get() + 1;
        String self = config.getNodeId();
        // Must be durable before the first RequestVote leaves this node
        persistenceService.saveState(self, newTerm, self);

        long epoch = ++electionEpoch;
        if (state != NodeState.CANDIDATE) {
            logEvent(RaftEvent.EventType.STATE_CHANGE, state + " -> CANDIDATE");
        }
        state = NodeState.CANDIDATE;
        currentTerm.set(newTerm);
        votedFor = self;
        currentLeader = null;
        logEvent(RaftEvent.EventType.TERM_INCREASED, "Term increased to " + newTerm);
        logEvent(RaftEvent.EventType.ELECTION_START, "Starting election with " + configuration);
        resetElectionTimer();

        Set<String> granted = new HashSet<>(Set.of(self));
        if (configuration.hasQuorum(granted)) {
            becomeLeader();
            return;
        }

        VoteRequest request = buildVoteRequest(newTerm);
        for (Peer peer : votingPeers()) {
            peer.stub(config.getRpcTimeoutMs()).requestVote(request, responseHandler(
                response -> onVoteResponse(epoch, peer, granted, response),
                error -> onRpcFailure(peer, "RequestVote", error)));
        }
    }

    private synchronized void onVoteResponse(long epoch, Peer peer, Set<String> granted, VoteResponse response) {
        if (response.getTerm() > currentTerm.get()) {
            stepDown(response.getTerm());
            return;
        }
        if (epoch != electionEpoch || state != NodeState.CANDIDATE) {
            return;
        }
        if (!response.getVoteGranted()) {
            logEvent(RaftEvent.EventType.VOTE_DENIED, "Vote denied by " + peer.id);
            return;
        }
        granted.add(peer.id);
        logEvent(RaftEvent.EventType.VOTE_GRANTED, "Vote granted by " + peer.id);
        if (configuration.hasQuorum(granted)) {
            becomeLeader();
        }
    }

    private void becomeLeader() {
        logEvent(RaftEvent.EventType.ELECTION_WON, "Won election for term " + currentTerm.get());
        logEvent(RaftEvent.EventType.STATE_CHANGE, "CANDIDATE -> LEADER");
        electionEpoch++;
        state = NodeState.LEADER;
        currentLeader = config.getNodeId();
        leaderSinceMillis = System.currentTimeMillis();
        if (electionTask != null) {
            electionTask.cancel(false);
        }

        int next = lastLogIndex() + 1;
        matchIndex.clear();
        nextIndex.clear();
        for (String peerId : peers.keySet()) {
            nextIndex.put(peerId, next);
            matchIndex.put(peerId, -1);
        }

        // Entries of earlier terms can only commit together with one of the current term (section 5.4.2),
        // and reads must not be served before that (section 8), so commit a no-op right away
        appendToOwnLog(new LogEntry(currentTerm.get(), null));
        advanceCommitIndex();

        heartbeatTask = scheduler.scheduleWithFixedDelay(this::onHeartbeatTick,
            0, config.getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Becomes a follower of the given term. Only a higher term clears the vote:
     * a server gets a single vote per term, even if it stepped down within that term.
     */
    private void stepDown(int term) {
        if (term > currentTerm.get()) {
            persistenceService.saveState(config.getNodeId(), term, null);
            currentTerm.set(term);
            votedFor = null;
            currentLeader = null;
            logEvent(RaftEvent.EventType.TERM_INCREASED, "Term increased to " + term);
        }
        if (state != NodeState.FOLLOWER) {
            logEvent(RaftEvent.EventType.STATE_CHANGE, state + " -> FOLLOWER");
        }
        if (state == NodeState.LEADER) {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
                heartbeatTask = null;
            }
            currentLeader = null;
            failPendingRequests();
        }
        state = NodeState.FOLLOWER;
        electionEpoch++;
        resetElectionTimer();
    }

    public synchronized VoteResponse handleVoteRequest(VoteRequest request) {
        log.debug("Received vote request from {} for term {}", request.getCandidateId(), request.getTerm());

        if (request.getTerm() > currentTerm.get()) {
            stepDown(request.getTerm());
        }

        boolean voteGranted = false;
        String candidate = request.getCandidateId();

        if (request.getTerm() < currentTerm.get()) {
            logEvent(RaftEvent.EventType.VOTE_DENIED,
                "Denied vote to " + candidate + " (stale term " + request.getTerm() + ")");
        } else if (votedFor != null && !votedFor.equals(candidate)) {
            logEvent(RaftEvent.EventType.VOTE_DENIED,
                "Denied vote to " + candidate + " (already voted for " + votedFor + ")");
        } else if (!isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm())) {
            logEvent(RaftEvent.EventType.VOTE_DENIED, "Denied vote to " + candidate + " (log not up-to-date)");
        } else {
            // Must be durable before the candidate learns about the vote
            persistenceService.saveState(config.getNodeId(), currentTerm.get(), candidate);
            votedFor = candidate;
            voteGranted = true;
            resetElectionTimer();
            logEvent(RaftEvent.EventType.VOTE_GRANTED,
                "Granted vote to " + candidate + " for term " + request.getTerm());
        }

        return VoteResponse.newBuilder()
            .setTerm(currentTerm.get())
            .setVoteGranted(voteGranted)
            .build();
    }

    /**
     * Grants a pre-vote without changing any state, if the candidate could win a real election
     * and this server has not heard from a leader within the minimum election timeout.
     */
    public synchronized VoteResponse handlePreVote(VoteRequest request) {
        boolean leaderAlive = state == NodeState.LEADER
            || System.currentTimeMillis() - lastLeaderContact < config.getElectionTimeoutMinMs();
        boolean granted = request.getTerm() > currentTerm.get()
            && !leaderAlive
            && isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm());

        log.debug("PreVote for {}: leaderAlive={}, granted={}", request.getCandidateId(), leaderAlive, granted);

        return VoteResponse.newBuilder()
            .setTerm(currentTerm.get())
            .setVoteGranted(granted)
            .build();
    }

    private VoteRequest buildVoteRequest(int term) {
        int lastIndex = lastLogIndex();
        return VoteRequest.newBuilder()
            .setTerm(term)
            .setCandidateId(config.getNodeId())
            .setLastLogIndex(lastIndex)
            .setLastLogTerm(termAt(lastIndex))
            .build();
    }

    private boolean isLogUpToDate(int otherLastIndex, int otherLastTerm) {
        int lastIndex = lastLogIndex();
        int lastTerm = termAt(lastIndex);
        return otherLastTerm > lastTerm || (otherLastTerm == lastTerm && otherLastIndex >= lastIndex);
    }

    private List<Peer> votingPeers() {
        return peers.values().stream().filter(peer -> configuration.contains(peer.id)).toList();
    }

    // ==================== Log Replication: Leader ====================

    private synchronized void onHeartbeatTick() {
        try {
            broadcast();
        } catch (RuntimeException e) {
            log.error("Heartbeat round failed", e);
        }
    }

    private void broadcast() {
        peers.values().forEach(this::replicate);
    }

    /**
     * Sends the next batch of entries to a peer, or an empty heartbeat if it is up to date.
     * Each peer has at most one RPC in flight. Anything that arrives in the meantime
     * goes out with the next one, which batches entries automatically under load.
     */
    private void replicate(Peer peer) {
        if (state != NodeState.LEADER) {
            return;
        }
        if (peer.inFlight) {
            peer.resend = true;
            return;
        }

        int next = nextIndex.computeIfAbsent(peer.id, id -> lastLogIndex() + 1);
        if (next <= snapshotIndex()) {
            sendSnapshot(peer);
            return;
        }

        int prevIndex = next - 1;
        int from = logicalToPhysical(next);
        int to = Math.min(raftLog.size(), from + MAX_ENTRIES_PER_APPEND);

        AppendEntriesRequest.Builder request = AppendEntriesRequest.newBuilder()
            .setTerm(currentTerm.get())
            .setLeaderId(config.getNodeId())
            .setPrevLogIndex(prevIndex)
            .setPrevLogTerm(termAt(prevIndex))
            .setLeaderCommit(commitIndex.get());
        for (LogEntry entry : raftLog.subList(from, to)) {
            request.addEntries(RaftCodec.toGrpc(entry));
        }

        int sentTerm = currentTerm.get();
        int sentEntries = to - from;
        long sequence = rpcSequence;
        peer.inFlight = true;
        peer.resend = false;
        peer.stub(config.getRpcTimeoutMs()).appendEntries(request.build(), responseHandler(
            response -> onAppendEntriesResponse(peer, sentTerm, prevIndex, sentEntries, sequence, response),
            error -> onReplicationFailure(peer, "AppendEntries", error)));
    }

    private synchronized void onAppendEntriesResponse(Peer peer, int sentTerm, int prevIndex, int sentEntries,
                                                      long sequence, AppendEntriesResponse response) {
        peer.inFlight = false;
        if (response.getTerm() > currentTerm.get()) {
            stepDown(response.getTerm());
            return;
        }
        if (state != NodeState.LEADER || currentTerm.get() != sentTerm || peers.get(peer.id) != peer) {
            return;
        }

        acknowledgeReads(peer.id, sequence);

        if (response.getSuccess()) {
            int match = Math.max(matchIndex.getOrDefault(peer.id, -1), prevIndex + sentEntries);
            matchIndex.put(peer.id, match);
            nextIndex.put(peer.id, Math.max(nextIndex.getOrDefault(peer.id, 0), match + 1));
            advanceCommitIndex();
        } else {
            nextIndex.put(peer.id, nextIndexAfterConflict(peer, response));
        }

        if (peer.resend || nextIndex.get(peer.id) <= lastLogIndex()) {
            replicate(peer);
        }
    }

    private int nextIndexAfterConflict(Peer peer, AppendEntriesResponse response) {
        int next = response.getConflictIndex();
        if (response.getConflictTerm() > 0) {
            // If we also have entries of that term, everything up to our last one of them can match
            for (int index = lastLogIndex(); index > snapshotIndex(); index--) {
                int term = termAt(index);
                if (term == response.getConflictTerm()) {
                    next = index + 1;
                    break;
                }
                if (term < response.getConflictTerm()) {
                    break;
                }
            }
        }
        // Always make progress, and never go back past what the peer has confirmed
        int current = nextIndex.getOrDefault(peer.id, next);
        return Math.max(matchIndex.getOrDefault(peer.id, -1) + 1, Math.min(next, current - 1));
    }

    private synchronized void onReplicationFailure(Peer peer, String rpc, Throwable error) {
        peer.inFlight = false;
        onRpcFailure(peer, rpc, error);
    }

    private void onRpcFailure(Peer peer, String rpc, Throwable error) {
        log.debug("{} to {} failed: {}", rpc, peer.id, error.getMessage());
        // gRPC backs off for up to two minutes; a restarted peer should be found on the next tick
        peer.channel.resetConnectBackoff();
    }

    /**
     * Commits the highest index stored on a quorum. Only entries of the current term
     * are committed by counting replicas (section 5.4.2); earlier ones follow indirectly.
     */
    private void advanceCommitIndex() {
        if (state != NodeState.LEADER) {
            return;
        }
        int candidate = configuration.quorumIndex(matchIndex);
        if (candidate > commitIndex.get() && termAt(candidate) == currentTerm.get()) {
            commitIndex.set(candidate);
            onCommitAdvanced();
            completeReadBarriers();
            broadcast();
        }
    }

    private void onCommitAdvanced() {
        applyCommittedEntries();

        if (state == NodeState.LEADER && configurationIndex <= commitIndex.get()) {
            if (configuration.isJoint()) {
                // C_old,new is committed, so the leader can move on to C_new
                appendToOwnLog(new LogEntry(currentTerm.get(), null,
                    ClusterConfiguration.createNew(configuration.getNewServers(), configuration.getAddresses())));
                logEvent(RaftEvent.EventType.MEMBERSHIP_CHANGE_COMMITTED,
                    "C_old,new committed, appended " + configuration);
                advanceCommitIndex();
            } else if (!configuration.contains(config.getNodeId())) {
                logEvent(RaftEvent.EventType.SERVER_REMOVED, "This node left the cluster, stepping down");
                stepDown(currentTerm.get());
            }
        }

        syncPeers();

        if (raftLog.size() >= config.getSnapshotThreshold()
                && lastApplied.get() - snapshotIndex() >= config.getSnapshotThreshold() / 2) {
            createSnapshot();
        }
    }

    private void applyCommittedEntries() {
        while (lastApplied.get() < commitIndex.get()) {
            int index = lastApplied.get() + 1;
            LogEntry entry = raftLog.get(logicalToPhysical(index));

            if (entry.getCommand() != null) {
                stateMachine.add(entry.getCommand());
            }
            if (entry.isConfigurationEntry()) {
                logEvent(RaftEvent.EventType.MEMBERSHIP_CHANGE_COMMITTED,
                    "Configuration committed: " + entry.getConfiguration());
            }
            lastApplied.set(index);

            PendingCommand pending = pendingCommands.remove(index);
            if (pending != null) {
                recordCommit(pending);
                pending.result.complete(CommandResult.COMMITTED);
            }
        }
    }

    private void recordCommit(PendingCommand pending) {
        long now = System.currentTimeMillis();
        double latency = now - pending.startMillis;
        averageCommitLatencyMs = averageCommitLatencyMs == 0
            ? latency
            : LATENCY_SMOOTHING * latency + (1 - LATENCY_SMOOTHING) * averageCommitLatencyMs;
        recentCommits.addLast(now);
        pruneRecentCommits(now);
    }

    private void pruneRecentCommits(long now) {
        while (!recentCommits.isEmpty() && now - recentCommits.peekFirst() > THROUGHPUT_WINDOW_MS) {
            recentCommits.removeFirst();
        }
    }

    private void failPendingRequests() {
        pendingCommands.values().forEach(pending -> pending.result.complete(CommandResult.LEADERSHIP_LOST));
        pendingCommands.clear();
        readBarriers.forEach(barrier -> barrier.confirmed.complete(false));
        readBarriers.clear();
    }

    // ==================== Log Replication: Follower ====================

    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        if (request.getTerm() < currentTerm.get()) {
            return rejectAppend(-1, 0);
        }
        if (state == NodeState.LEADER && request.getTerm() == currentTerm.get()) {
            log.error("Two leaders in term {}: {} and this node", request.getTerm(), request.getLeaderId());
            return rejectAppend(-1, 0);
        }

        observeLeader(request.getTerm(), request.getLeaderId());

        // Consistency check. Anything up to the snapshot is committed and therefore matches.
        int prevIndex = request.getPrevLogIndex();
        if (prevIndex > lastLogIndex()) {
            return rejectAppend(lastLogIndex() + 1, 0);
        }
        if (prevIndex > snapshotIndex() && termAt(prevIndex) != request.getPrevLogTerm()) {
            int conflictTerm = termAt(prevIndex);
            int firstOfTerm = prevIndex;
            while (firstOfTerm - 1 > snapshotIndex() && termAt(firstOfTerm - 1) == conflictTerm) {
                firstOfTerm--;
            }
            log.debug("Log mismatch at {}: local term {}, leader has {}", prevIndex, conflictTerm, request.getPrevLogTerm());
            return rejectAppend(firstOfTerm, conflictTerm);
        }

        // Skip what we already have, drop what conflicts, append the rest
        int index = prevIndex;
        List<LogEntry> newEntries = new ArrayList<>();
        for (GrpcLogEntry entry : request.getEntriesList()) {
            index++;
            if (index <= snapshotIndex()) {
                continue;
            }
            if (index <= lastLogIndex()) {
                if (termAt(index) == entry.getTerm()) {
                    continue;
                }
                truncateLogFrom(index);
            }
            newEntries.add(RaftCodec.fromGrpc(entry));
        }
        int lastVerifiedIndex = index;

        if (!newEntries.isEmpty()) {
            int startIndex = lastLogIndex() + 1;
            persistenceService.appendLogEntries(config.getNodeId(), startIndex, newEntries);
            raftLog.addAll(newEntries);
            for (int i = 0; i < newEntries.size(); i++) {
                if (newEntries.get(i).isConfigurationEntry()) {
                    adoptConfiguration(newEntries.get(i).getConfiguration(), startIndex + i);
                }
            }
            logEvent(RaftEvent.EventType.LOG_REPLICATED,
                "Replicated " + newEntries.size() + " entries from leader " + request.getLeaderId());
        }

        // Entries beyond the last verified one may be left over from an old leader
        int newCommit = Math.min(request.getLeaderCommit(), lastVerifiedIndex);
        if (newCommit > commitIndex.get()) {
            commitIndex.set(newCommit);
            onCommitAdvanced();
        }

        return AppendEntriesResponse.newBuilder()
            .setTerm(currentTerm.get())
            .setSuccess(true)
            .setMatchIndex(lastVerifiedIndex)
            .build();
    }

    private AppendEntriesResponse rejectAppend(int conflictIndex, int conflictTerm) {
        return AppendEntriesResponse.newBuilder()
            .setTerm(currentTerm.get())
            .setSuccess(false)
            .setConflictIndex(conflictIndex)
            .setConflictTerm(conflictTerm)
            .build();
    }

    private void observeLeader(int term, String leaderId) {
        if (term > currentTerm.get() || state != NodeState.FOLLOWER) {
            stepDown(term);
        }
        if (!leaderId.equals(currentLeader)) {
            currentLeader = leaderId;
            logEvent(RaftEvent.EventType.HEARTBEAT_RECEIVED, "Following leader " + leaderId);
        }
        lastLeaderContact = System.currentTimeMillis();
        electionEpoch++;
        resetElectionTimer();
    }

    private void truncateLogFrom(int index) {
        if (index <= commitIndex.get()) {
            throw new IllegalStateException("Refusing to truncate committed entry " + index
                + " (commitIndex " + commitIndex.get() + ")");
        }
        persistenceService.deleteLogEntriesFrom(config.getNodeId(), index);
        raftLog.subList(logicalToPhysical(index), raftLog.size()).clear();
        if (configurationIndex >= index) {
            reloadConfiguration();
        }
    }

    // ==================== Client Requests ====================

    /**
     * Appends a command to the log and waits until it is committed and applied.
     * This is the point where Raft can promise the client that the command will survive.
     */
    public CommandResult submitCommand(String command) {
        CompletableFuture<CommandResult> result = new CompletableFuture<>();
        synchronized (this) {
            if (suspended) {
                return CommandResult.SUSPENDED;
            }
            if (state != NodeState.LEADER) {
                return CommandResult.NOT_LEADER;
            }
            logEvent(RaftEvent.EventType.COMMAND_RECEIVED, "Received command from client: " + command);
            int index = appendToOwnLog(new LogEntry(currentTerm.get(), command));
            pendingCommands.put(index, new PendingCommand(result, System.currentTimeMillis()));
            advanceCommitIndex();
            broadcast();
        }
        try {
            return result.get(config.getCommandTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            synchronized (this) {
                pendingCommands.values().removeIf(pending -> pending.result == result);
            }
            return CommandResult.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandResult.TIMEOUT;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * Linearizable read barrier (ReadIndex, section 8): true means this node was still the
     * leader after the call started, so reading its state machine now returns the latest state.
     * It first needs an entry committed in its own term to know what the latest state is.
     */
    public boolean confirmLeadership() {
        CompletableFuture<Boolean> confirmed = new CompletableFuture<>();
        synchronized (this) {
            if (state != NodeState.LEADER || termAt(commitIndex.get()) != currentTerm.get()) {
                return false;
            }
            ReadBarrier barrier = new ReadBarrier(++rpcSequence, new HashSet<>(Set.of(config.getNodeId())), confirmed);
            readBarriers.add(barrier);
            completeReadBarriers();
            broadcast();
        }
        try {
            return confirmed.get(config.getCommandTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            synchronized (this) {
                readBarriers.removeIf(barrier -> barrier.confirmed == confirmed);
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * Only an RPC sent after a barrier was created proves leadership for it. A follower in the
     * same term acknowledges this node as leader even if its log did not match yet.
     */
    private void acknowledgeReads(String peerId, long sequence) {
        for (ReadBarrier barrier : readBarriers) {
            if (sequence >= barrier.sequence) {
                barrier.acks.add(peerId);
            }
        }
        completeReadBarriers();
    }

    private void completeReadBarriers() {
        readBarriers.removeIf(barrier -> {
            boolean done = configuration.hasQuorum(barrier.acks) && lastApplied.get() >= commitIndex.get();
            if (done) {
                barrier.confirmed.complete(true);
            }
            return done;
        });
    }

    public List<String> getStateMachine() {
        synchronized (this) {
            return new ArrayList<>(stateMachine);
        }
    }

    // ==================== Membership Changes ====================

    /**
     * Adds a server with joint consensus (section 6): C_old,new is appended here, and
     * onCommitAdvanced follows up with C_new. Returns false if this node cannot do it now.
     */
    public synchronized boolean addServer(ServerInfo serverInfo) {
        if (!canChangeMembership() || configuration.contains(serverInfo.getNodeId())) {
            log.warn("Cannot add server {}: not leader, already a member, or a change is in progress", serverInfo);
            return false;
        }
        Set<String> newServers = new HashSet<>(configuration.getNewServers());
        newServers.add(serverInfo.getNodeId());
        Map<String, ServerInfo> addresses = new HashMap<>(configuration.getAddresses());
        addresses.put(serverInfo.getNodeId(), serverInfo);

        logEvent(RaftEvent.EventType.SERVER_ADDED, "Adding server " + serverInfo);
        startMembershipChange(newServers, addresses);
        return true;
    }

    /**
     * Removes a server with joint consensus. The leader may remove
     * itself: it steps down as soon as C_new is committed.
     */
    public synchronized boolean removeServer(String nodeId) {
        Set<String> newServers = new HashSet<>(configuration.getNewServers());
        if (!canChangeMembership() || !newServers.remove(nodeId) || newServers.isEmpty()) {
            log.warn("Cannot remove server {}: not leader, not a member, last member, or a change is in progress", nodeId);
            return false;
        }
        logEvent(RaftEvent.EventType.SERVER_REMOVED, "Removing server " + nodeId);
        startMembershipChange(newServers, configuration.getAddresses());
        return true;
    }

    /**
     * One change at a time, and only once the leader has committed an entry in its own term:
     * otherwise a configuration it does not know of yet could overlap with this one.
     */
    private boolean canChangeMembership() {
        return state == NodeState.LEADER
            && !configuration.isJoint()
            && configurationIndex <= commitIndex.get()
            && termAt(commitIndex.get()) == currentTerm.get();
    }

    private void startMembershipChange(Set<String> newServers, Map<String, ServerInfo> addresses) {
        ClusterConfiguration joint = ClusterConfiguration.createJoint(configuration.getNewServers(), newServers, addresses);
        appendToOwnLog(new LogEntry(currentTerm.get(), null, joint));
        logEvent(RaftEvent.EventType.MEMBERSHIP_CHANGE_START, "Appended " + joint);
        advanceCommitIndex();
    }

    /**
     * A server uses the latest configuration in its log as soon as it appears there, committed or not.
     */
    private void adoptConfiguration(ClusterConfiguration newConfiguration, int index) {
        configuration = newConfiguration;
        configurationIndex = index;
        syncPeers();
    }

    /**
     * Finds the latest configuration, after the log changed in a way that may have removed it.
     */
    private void reloadConfiguration() {
        for (int index = lastLogIndex(); index > snapshotIndex(); index--) {
            LogEntry entry = raftLog.get(logicalToPhysical(index));
            if (entry.isConfigurationEntry()) {
                adoptConfiguration(entry.getConfiguration(), index);
                return;
            }
        }
        boolean fromSnapshot = lastSnapshot != null && lastSnapshot.getConfiguration() != null;
        adoptConfiguration(fromSnapshot ? lastSnapshot.getConfiguration() : bootstrapConfiguration, snapshotIndex());
    }

    /**
     * Connects to every server in the configuration. Departing servers stay connected until
     * the change is committed, which gives them the chance to learn about their removal.
     */
    private void syncPeers() {
        String self = config.getNodeId();
        knownServers.putAll(configuration.getAddresses());

        for (String nodeId : configuration.getAllServers()) {
            ServerInfo address = knownServers.get(nodeId);
            if (nodeId.equals(self) || peers.containsKey(nodeId)) {
                continue;
            }
            if (address == null) {
                log.warn("No address known for server {}", nodeId);
                continue;
            }
            ManagedChannel channel = ManagedChannelBuilder
                .forAddress(address.getHost(), address.getGrpcPort())
                .usePlaintext()
                .maxInboundMessageSize(MAX_GRPC_MESSAGE_BYTES)
                .build();
            peers.put(nodeId, new Peer(nodeId, channel));
            nextIndex.put(nodeId, lastLogIndex() + 1);
            matchIndex.put(nodeId, -1);
            log.info("Connected to peer {}", address);
        }

        if (configurationIndex <= commitIndex.get()) {
            for (String nodeId : new ArrayList<>(peers.keySet())) {
                if (!configuration.contains(nodeId)) {
                    peers.remove(nodeId).channel.shutdown();
                    nextIndex.remove(nodeId);
                    matchIndex.remove(nodeId);
                    log.info("Disconnected from removed server {}", nodeId);
                }
            }
        }
    }

    public synchronized Set<String> getClusterMembers() {
        return new TreeSet<>(configuration.getAllServers());
    }

    public ServerInfo getServerInfo(String nodeId) {
        return knownServers.get(nodeId);
    }

    // ==================== Snapshots ====================

    /**
     * Writes the applied state to a snapshot and drops the log entries it covers.
     */
    public synchronized void createSnapshot() {
        int lastIndex = lastApplied.get();
        if (lastIndex < 0 || lastIndex <= snapshotIndex()) {
            log.debug("Nothing to snapshot: lastApplied={}, snapshotIndex={}", lastIndex, snapshotIndex());
            return;
        }
        try {
            List<String> stateCopy = new ArrayList<>(stateMachine);
            ClusterConfiguration snapshotConfiguration = configurationAt(lastIndex);
            long sizeBytes = RaftCodec.encodeSnapshot(stateCopy, snapshotConfiguration).length;
            Snapshot snapshot = new Snapshot(lastIndex, termAt(lastIndex), stateCopy, snapshotConfiguration,
                System.currentTimeMillis(), sizeBytes);

            // In this order, a crash at any point leaves a state that init() can recover from
            persistenceService.saveSnapshot(config.getNodeId(), snapshot);
            persistenceService.compactLog(config.getNodeId(), lastIndex);

            int entriesRemoved = logicalToPhysical(lastIndex) + 1;
            raftLog.subList(0, entriesRemoved).clear();
            lastSnapshot = snapshot;
            totalSnapshots.incrementAndGet();

            logEvent(RaftEvent.EventType.SNAPSHOT_CREATED,
                String.format("Created snapshot at index %d, compacted %d entries", lastIndex, entriesRemoved));
        } catch (RuntimeException e) {
            log.error("Failed to create snapshot", e);
        }
    }

    private ClusterConfiguration configurationAt(int index) {
        for (int i = index; i > snapshotIndex(); i--) {
            LogEntry entry = raftLog.get(logicalToPhysical(i));
            if (entry.isConfigurationEntry()) {
                return entry.getConfiguration();
            }
        }
        return lastSnapshot != null && lastSnapshot.getConfiguration() != null
            ? lastSnapshot.getConfiguration()
            : bootstrapConfiguration;
    }

    private void sendSnapshot(Peer peer) {
        Snapshot snapshot = lastSnapshot;
        log.info("Peer {} is behind the log, sending snapshot at index {}", peer.id, snapshot.getLastIncludedIndex());

        InstallSnapshotRequest request = InstallSnapshotRequest.newBuilder()
            .setTerm(currentTerm.get())
            .setLeaderId(config.getNodeId())
            .setLastIncludedIndex(snapshot.getLastIncludedIndex())
            .setLastIncludedTerm(snapshot.getLastIncludedTerm())
            .setData(ByteString.copyFrom(
                RaftCodec.encodeSnapshot(snapshot.getStateMachineState(), snapshot.getConfiguration())))
            .setDone(true)
            .setOffset(0)
            .build();

        int sentTerm = currentTerm.get();
        long sequence = rpcSequence;
        peer.inFlight = true;
        peer.resend = false;
        peer.stub(config.getSnapshotRpcTimeoutMs()).installSnapshot(request, responseHandler(
            response -> onInstallSnapshotResponse(peer, sentTerm, snapshot.getLastIncludedIndex(), sequence, response),
            error -> onReplicationFailure(peer, "InstallSnapshot", error)));
    }

    private synchronized void onInstallSnapshotResponse(Peer peer, int sentTerm, int lastIncludedIndex,
                                                        long sequence, InstallSnapshotResponse response) {
        peer.inFlight = false;
        if (response.getTerm() > currentTerm.get()) {
            stepDown(response.getTerm());
            return;
        }
        if (state != NodeState.LEADER || currentTerm.get() != sentTerm || peers.get(peer.id) != peer) {
            return;
        }
        acknowledgeReads(peer.id, sequence);
        if (response.getSuccess()) {
            int match = Math.max(matchIndex.getOrDefault(peer.id, -1), lastIncludedIndex);
            matchIndex.put(peer.id, match);
            nextIndex.put(peer.id, match + 1);
            advanceCommitIndex();
            replicate(peer);
        }
    }

    /**
     * InstallSnapshot RPC (section 7): replaces the state of a follower that is too far behind.
     */
    public synchronized InstallSnapshotResponse handleInstallSnapshot(InstallSnapshotRequest request) {
        if (request.getTerm() < currentTerm.get()) {
            return installSnapshotResponse(false);
        }
        observeLeader(request.getTerm(), request.getLeaderId());

        int lastIncludedIndex = request.getLastIncludedIndex();
        int lastIncludedTerm = request.getLastIncludedTerm();
        if (lastIncludedIndex <= commitIndex.get()) {
            return installSnapshotResponse(true);
        }

        SnapshotData data;
        try {
            data = SnapshotData.parseFrom(request.getData());
        } catch (InvalidProtocolBufferException e) {
            log.error("Received corrupt snapshot from {}", request.getLeaderId(), e);
            return installSnapshotResponse(false);
        }

        List<String> snapshotState = RaftCodec.decodeSnapshotState(data);
        Snapshot snapshot = new Snapshot(lastIncludedIndex, lastIncludedTerm, snapshotState,
            RaftCodec.decodeSnapshotConfiguration(data), System.currentTimeMillis(), request.getData().size());

        persistenceService.saveSnapshot(config.getNodeId(), snapshot);

        // Entries after the snapshot stay only if our log agrees with it at the snapshot's last entry
        if (lastIncludedIndex <= lastLogIndex() && termAt(lastIncludedIndex) == lastIncludedTerm) {
            persistenceService.compactLog(config.getNodeId(), lastIncludedIndex);
            raftLog.subList(0, logicalToPhysical(lastIncludedIndex) + 1).clear();
        } else {
            persistenceService.deleteLogEntriesFrom(config.getNodeId(), 0);
            raftLog.clear();
        }

        lastSnapshot = snapshot;
        stateMachine.clear();
        stateMachine.addAll(snapshotState);
        commitIndex.set(lastIncludedIndex);
        lastApplied.set(lastIncludedIndex);
        reloadConfiguration();

        logEvent(RaftEvent.EventType.SNAPSHOT_INSTALLED,
            String.format("Installed snapshot from %s at index %d", request.getLeaderId(), lastIncludedIndex));
        return installSnapshotResponse(true);
    }

    private InstallSnapshotResponse installSnapshotResponse(boolean success) {
        return InstallSnapshotResponse.newBuilder()
            .setTerm(currentTerm.get())
            .setSuccess(success)
            .build();
    }

    // ==================== Log Access ====================

    private int appendToOwnLog(LogEntry entry) {
        int index = lastLogIndex() + 1;
        // Durable first: the leader counts itself towards the quorum for this entry
        persistenceService.appendLogEntries(config.getNodeId(), index, List.of(entry));
        raftLog.add(entry);
        matchIndex.put(config.getNodeId(), index);
        if (entry.isConfigurationEntry()) {
            adoptConfiguration(entry.getConfiguration(), index);
        }
        return index;
    }

    private int snapshotIndex() {
        return lastSnapshot != null ? lastSnapshot.getLastIncludedIndex() : -1;
    }

    /**
     * The logical index of the first entry in raftLog.
     */
    private int getLogBaseIndex() {
        return snapshotIndex() + 1;
    }

    private int logicalToPhysical(int logicalIndex) {
        return logicalIndex - getLogBaseIndex();
    }

    private int lastLogIndex() {
        return getLogBaseIndex() + raftLog.size() - 1;
    }

    /**
     * The term of a logical index, or 0 if this node does not have that entry (terms start at 1).
     */
    private int termAt(int logicalIndex) {
        if (lastSnapshot != null && logicalIndex == lastSnapshot.getLastIncludedIndex()) {
            return lastSnapshot.getLastIncludedTerm();
        }
        int physicalIndex = logicalToPhysical(logicalIndex);
        if (physicalIndex < 0 || physicalIndex >= raftLog.size()) {
            return 0;
        }
        return raftLog.get(physicalIndex).getTerm();
    }

    // ==================== Monitoring ====================

    public synchronized Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", config.getNodeId());
        status.put("state", suspended ? "SUSPENDED" : state.name());
        status.put("currentTerm", currentTerm.get());
        status.put("currentLeader", currentLeader);
        // Counts of entries, not indices: 0 means none, 1 means the entry at index 0, and so on
        status.put("commitIndex", commitIndex.get() + 1);
        status.put("lastApplied", lastApplied.get() + 1);
        status.put("logSize", lastLogIndex() + 1);
        status.put("stateMachine", new ArrayList<>(stateMachine));
        status.put("suspended", suspended);
        status.put("members", new TreeSet<>(configuration.getAllServers()));
        status.put("member", configuration.contains(config.getNodeId()));
        status.put("jointConsensus", configuration.isJoint());
        status.put("hasSnapshot", lastSnapshot != null);
        status.put("snapshotBaseIndex", lastSnapshot != null ? lastSnapshot.getLastIncludedIndex() : 0);
        return status;
    }

    public synchronized Map<String, Map<String, Object>> getReplicationStatus() {
        Map<String, Map<String, Object>> replication = new TreeMap<>();
        int lastIndex = lastLogIndex();
        for (String peerId : peers.keySet()) {
            int match = matchIndex.getOrDefault(peerId, -1);
            Map<String, Object> status = new HashMap<>();
            status.put("nextIndex", nextIndex.getOrDefault(peerId, 0));
            status.put("matchIndex", match);
            status.put("lag", lastIndex - match);
            status.put("upToDate", match >= lastIndex);
            replication.put(peerId, status);
        }
        return replication;
    }

    /**
     * The latest entries of the log with their logical indices, for inspecting the log from outside.
     */
    public synchronized Map<String, Object> getLogView(int limit) {
        List<Map<String, Object>> entries = new ArrayList<>();
        int first = Math.max(getLogBaseIndex(), lastLogIndex() - limit + 1);
        for (int index = first; index <= lastLogIndex(); index++) {
            LogEntry entry = raftLog.get(logicalToPhysical(index));
            Map<String, Object> view = new HashMap<>();
            view.put("index", index);
            view.put("term", entry.getTerm());
            if (entry.isConfigurationEntry()) {
                view.put("type", "CONFIGURATION");
                view.put("command", entry.getConfiguration().toString());
            } else if (entry.getCommand() == null) {
                view.put("type", "NOOP");
            } else {
                view.put("type", "COMMAND");
                view.put("command", entry.getCommand());
            }
            entries.add(view);
        }
        
        Map<String, Object> log = new HashMap<>();
        log.put("entries", entries);
        log.put("lastIndex", lastLogIndex());
        log.put("commitIndex", commitIndex.get());
        log.put("lastApplied", lastApplied.get());
        log.put("snapshotIndex", snapshotIndex());
        return log;
    }
    
    public synchronized List<LogEntry> getRaftLog() {
        return new ArrayList<>(raftLog);
    }

    public synchronized int getLastLogIndex() {
        return lastLogIndex();
    }

    public int getCompactedEntries() {
        return getLogBaseIndex();
    }

    public synchronized double getAverageCommitLatencyMs() {
        return averageCommitLatencyMs;
    }

    public synchronized double getCommandsPerSecond() {
        pruneRecentCommits(System.currentTimeMillis());
        return recentCommits.size() / (THROUGHPUT_WINDOW_MS / 1000.0);
    }

    public synchronized long getLeaderUptimeMs() {
        return state == NodeState.LEADER ? System.currentTimeMillis() - leaderSinceMillis : 0;
    }

    private void logEvent(RaftEvent.EventType type, String description) {
        RaftEvent event = new RaftEvent(
            java.time.LocalDateTime.now(),
            type,
            description,
            currentTerm.get(),
            config.getNodeId()
        );

        synchronized (events) {
            events.addLast(event);
            if (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
        }

        log.info("[EVENT] {}", event.toLogString());
    }

    public List<RaftEvent> getEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    private void cancelTimers() {
        if (electionTask != null) {
            electionTask.cancel(false);
            electionTask = null;
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        electionTimerGeneration++;
    }

    private static <T> StreamObserver<T> responseHandler(Consumer<T> onResponse, Consumer<Throwable> onFailure) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T response) {
                onResponse.accept(response);
            }

            @Override
            public void onError(Throwable error) {
                onFailure.accept(error);
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    private static final class Peer {
        final String id;
        final ManagedChannel channel;
        final RaftServiceGrpc.RaftServiceStub stub;
        boolean inFlight = false;
        boolean resend = false;

        Peer(String id, ManagedChannel channel) {
            this.id = id;
            this.channel = channel;
            this.stub = RaftServiceGrpc.newStub(channel);
        }

        RaftServiceGrpc.RaftServiceStub stub(long timeoutMs) {
            return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private record PendingCommand(CompletableFuture<CommandResult> result, long startMillis) {
    }

    private record ReadBarrier(long sequence, Set<String> acks, CompletableFuture<Boolean> confirmed) {
    }
}
