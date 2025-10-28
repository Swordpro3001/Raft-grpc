package com.example.raftimplementation.service;

import com.example.raftimplementation.config.RaftConfig;
import com.example.raftimplementation.grpc.*;
import com.example.raftimplementation.model.*;
import com.example.raftimplementation.persistence.RaftPersistenceService;
import com.example.raftimplementation.persistence.RaftStateEntity;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@Getter
public class RaftNode {
    private final RaftConfig config;
    private final RaftPersistenceService persistenceService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, RaftServiceGrpc.RaftServiceBlockingStub> stubs = new ConcurrentHashMap<>();
    

    private volatile NodeState state = NodeState.FOLLOWER;
    private final AtomicInteger currentTerm = new AtomicInteger(0);
    private volatile String votedFor = null;
    private volatile String currentLeader = null;
    private final List<LogEntry> raftLog = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger commitIndex = new AtomicInteger(0);
    private final AtomicInteger lastApplied = new AtomicInteger(0);
    

    private final Map<String, Integer> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Integer> matchIndex = new ConcurrentHashMap<>();
    

    private final List<String> stateMachine = Collections.synchronizedList(new ArrayList<>());
    
    // Snapshot state for log compaction
    private volatile Snapshot lastSnapshot = null;
    private final AtomicInteger compactedEntries = new AtomicInteger(0);
    private final AtomicInteger totalSnapshots = new AtomicInteger(0);
    private static final int SNAPSHOT_THRESHOLD = 100; // Create snapshot after 100 entries
    

    private final List<RaftEvent> events =
        Collections.synchronizedList(new LinkedList<>());
    private static final int MAX_EVENTS = 100; 
    

    private volatile long lastHeartbeat = System.currentTimeMillis();
    private ScheduledFuture<?> electionTask;
    private ScheduledFuture<?> heartbeatTask;

    /**
     * -- GETTER --
     *  Checks if the node is currently suspended.
     */
    // Suspension state: keeps Spring Boot running but pauses Raft participation
    private volatile boolean suspended = false;
    
    private static final int ELECTION_TIMEOUT_MIN = 3000;
    private static final int ELECTION_TIMEOUT_MAX = 5000;
    private static final int HEARTBEAT_INTERVAL = 1000;
    
    public RaftNode(RaftConfig config, RaftPersistenceService persistenceService) {
        this.config = config;
        this.persistenceService = persistenceService;
    }
    
    @PostConstruct
    public void init() {
        log.info("Initializing Raft node: {}", config.getNodeId());
        
        // Load persisted state (currentTerm, votedFor)
        RaftStateEntity persistedState = persistenceService.loadState(config.getNodeId());
        currentTerm.set(persistedState.getCurrentTerm());
        votedFor = persistedState.getVotedFor();
        log.info("Loaded persisted state: term={}, votedFor={}", currentTerm.get(), votedFor);
        
        // Load persisted log entries
        List<LogEntry> persistedLog = persistenceService.loadLog(config.getNodeId());
        synchronized (raftLog) {
            raftLog.addAll(persistedLog);
        }
        log.info("Loaded {} persisted log entries", persistedLog.size());
        
        // Load persisted snapshot
        persistenceService.loadSnapshot(config.getNodeId()).ifPresent(snapshot -> {
            lastSnapshot = snapshot;
            synchronized (stateMachine) {
                stateMachine.clear();
                stateMachine.addAll(snapshot.getStateMachineState());
            }
            commitIndex.set(Math.max(commitIndex.get(), snapshot.getLastIncludedIndex()));
            lastApplied.set(Math.max(lastApplied.get(), snapshot.getLastIncludedIndex()));
            log.info("Loaded persisted snapshot: lastIncludedIndex={}, stateMachineSize={}", 
                    snapshot.getLastIncludedIndex(), stateMachine.size());
        });
        
        // Connect to peers
        for (RaftConfig.PeerConfig peer : config.getPeers()) {
            if (!peer.getNodeId().equals(config.getNodeId())) {
                ManagedChannel channel = ManagedChannelBuilder
                    .forAddress(peer.getHost(), peer.getGrpcPort())
                    .usePlaintext()
                    .build();
                channels.put(peer.getNodeId(), channel);
                stubs.put(peer.getNodeId(), RaftServiceGrpc.newBlockingStub(channel));
                log.info("Connected to peer: {} at {}:{}", peer.getNodeId(), peer.getHost(), peer.getGrpcPort());
            }
        }
        

        startElectionTimer();
        

        scheduler.scheduleAtFixedRate(this::applyCommittedEntries, 100, 100, TimeUnit.MILLISECONDS);
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Raft node: {}", config.getNodeId());
        if (electionTask != null) {
            electionTask.cancel(true);
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        scheduler.shutdown();
        channels.values().forEach(ManagedChannel::shutdown);
    }
    
    /**
     * Suspends Raft participation while keeping Spring Boot application running.
     * Stops heartbeats, elections, and command processing but keeps the app alive.
     */
    public synchronized void suspend() {
        if (suspended) {
            log.warn("Node {} is already suspended", config.getNodeId());
            return;
        }
        
        log.info("Suspending Raft node: {}", config.getNodeId());
        suspended = true;
        
        // Cancel timers to stop participating in Raft protocol
        if (electionTask != null) {
            electionTask.cancel(true);
            electionTask = null;
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        
        // Become follower and clear leadership
        if (state == NodeState.LEADER) {
            state = NodeState.FOLLOWER;
        }
        currentLeader = null;
        votedFor = null;
        
        logEvent(RaftEvent.EventType.STATE_CHANGE,
            "Node SUSPENDED - Spring Boot running, Raft paused");
        
        log.info("Node {} suspended successfully. Spring Boot is still running.", config.getNodeId());
    }
    
    /**
     * Resumes Raft participation after suspension.
     * Restarts election timer and rejoins the cluster.
     */
    public synchronized void resume() {
        if (!suspended) {
            log.warn("Node {} is not suspended", config.getNodeId());
            return;
        }
        
        log.info("Resuming Raft node: {}", config.getNodeId());
        suspended = false;
        
        // Restart election timer to rejoin cluster
        state = NodeState.FOLLOWER;
        startElectionTimer();
        
        logEvent(RaftEvent.EventType.STATE_CHANGE,
            "Node RESUMED - Rejoining cluster as FOLLOWER");
        
        log.info("Node {} resumed successfully. Rejoining cluster.", config.getNodeId());
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
            events.add(event);
            while (events.size() > MAX_EVENTS) {
                events.remove(0);
            }
        }
        
        log.info("[EVENT] {}", event.toLogString());
    }
    
    public List<RaftEvent> getEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }
    
    private void startElectionTimer() {
        if (electionTask != null) {
            electionTask.cancel(false);
        }
        
        int timeout = ThreadLocalRandom.current().nextInt(ELECTION_TIMEOUT_MIN, ELECTION_TIMEOUT_MAX);
        electionTask = scheduler.schedule(this::startElection, timeout, TimeUnit.MILLISECONDS);
        log.debug("Election timer started: {}ms", timeout);
    }
    
    private void startElection() {
        if (suspended || state == NodeState.LEADER) {
            return;
        }
        
        // Pre-vote phase: Check if election would be successful before disrupting cluster
        log.info("Starting pre-vote phase for node {}", config.getNodeId());
        boolean preVoteSuccessful = performPreVote();
        
        if (!preVoteSuccessful) {
            log.info("Pre-vote failed for node {}, not starting actual election", config.getNodeId());
            startElectionTimer();
            return;
        }
        
        log.info("Pre-vote successful! Starting actual election...");
        logEvent(RaftEvent.EventType.STATE_CHANGE,
            "FOLLOWER → CANDIDATE");
        state = NodeState.CANDIDATE;
        currentTerm.incrementAndGet();
        logEvent(RaftEvent.EventType.TERM_INCREASED,
            "Term increased to " + currentTerm.get());
        votedFor = config.getNodeId();
        currentLeader = null;
        
        // Persist state change (CRITICAL for Raft safety - must persist before sending RequestVote RPCs)
        persistenceService.saveState(config.getNodeId(), currentTerm.get(), votedFor);
        
        int votesReceived = 1;
        int votesNeeded = (stubs.size() + 2) / 2; 
        
        log.info("Node {} starting election for term {}", config.getNodeId(), currentTerm.get());
        logEvent(RaftEvent.EventType.ELECTION_START,
            "Starting election, need " + votesNeeded + " votes");
        
        int lastLogIndex = getLastLogIndex();
        int lastLogTerm = getLogTermAt(lastLogIndex);
        
        VoteRequest voteRequest = VoteRequest.newBuilder()
            .setTerm(currentTerm.get())
            .setCandidateId(config.getNodeId())
            .setLastLogIndex(lastLogIndex)
            .setLastLogTerm(lastLogTerm)
            .build();

        for (Map.Entry<String, RaftServiceGrpc.RaftServiceBlockingStub> entry : stubs.entrySet()) {
            try {
                VoteResponse response = entry.getValue().requestVote(voteRequest);
                
                if (response.getTerm() > currentTerm.get()) {
                    becomeFollower(response.getTerm());
                    return;
                }
                
                if (response.getVoteGranted()) {
                    votesReceived++;
                    log.info("Received vote from {} ({}/{})", entry.getKey(), votesReceived, votesNeeded);
                    logEvent(RaftEvent.EventType.VOTE_GRANTED,
                        "Vote granted from " + entry.getKey() + " (" + votesReceived + "/" + votesNeeded + ")");
                } else {
                    logEvent(RaftEvent.EventType.VOTE_DENIED,
                        "Vote denied from " + entry.getKey());
                }
            } catch (Exception e) {
                log.warn("Failed to request vote from {}: {}", entry.getKey(), e.getMessage());
            }
        }
        
        if (votesReceived >= votesNeeded && state == NodeState.CANDIDATE) {
            logEvent(RaftEvent.EventType.ELECTION_WON,
                "Won election with " + votesReceived + " votes");
            becomeLeader();
        } else {
            log.info("Election failed. Votes received: {}, needed: {}", votesReceived, votesNeeded);
            logEvent(RaftEvent.EventType.ELECTION_LOST,
                "Lost election: " + votesReceived + "/" + votesNeeded + " votes");
            startElectionTimer();
        }
    }
    
    private void becomeLeader() {
        log.info("Node {} became LEADER for term {}", config.getNodeId(), currentTerm.get());
        logEvent(RaftEvent.EventType.STATE_CHANGE,
            "CANDIDATE → LEADER");
        state = NodeState.LEADER;
        currentLeader = config.getNodeId();

        // Initialize nextIndex for each peer to leader's last log index + 1 (logical index)
        int lastLogIndexPlusOne = getLastLogIndex() + 1;
        for (String peerId : stubs.keySet()) {
            nextIndex.put(peerId, lastLogIndexPlusOne);
            matchIndex.put(peerId, 0);  // Logical index: 0 = follower has no entries replicated yet
        }
        
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeats, 0, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
        
        if (electionTask != null) {
            electionTask.cancel(false);
        }
    }
    
    private void becomeFollower(int newTerm) {
        log.info("Node {} becoming FOLLOWER for term {}", config.getNodeId(), newTerm);
        String oldState = state.toString();
        logEvent(RaftEvent.EventType.STATE_CHANGE,
            oldState + " → FOLLOWER");
        if (newTerm > currentTerm.get()) {
            logEvent(RaftEvent.EventType.TERM_INCREASED,
                "Term increased to " + newTerm);
        }
        state = NodeState.FOLLOWER;
        currentTerm.set(newTerm);
        votedFor = null;
        
        // Persist state change (CRITICAL for Raft safety)
        persistenceService.saveState(config.getNodeId(), newTerm, null);
        
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        
        startElectionTimer();
    }
    
    private void sendHeartbeats() {
        if (state != NodeState.LEADER) {
            return;
        }
        
        for (Map.Entry<String, RaftServiceGrpc.RaftServiceBlockingStub> entry : stubs.entrySet()) {
            String peerId = entry.getKey();
            RaftServiceGrpc.RaftServiceBlockingStub stub = entry.getValue();
            
            int peerNextIndex = nextIndex.getOrDefault(peerId, getLogBaseIndex());
            
            // Check if follower is too far behind and needs snapshot
            if (lastSnapshot != null && peerNextIndex <= lastSnapshot.getLastIncludedIndex()) {
                log.info("Follower {} is too far behind (nextIndex={}, snapshotIndex={}), sending snapshot",
                        peerId, peerNextIndex, lastSnapshot.getLastIncludedIndex());
                try {
                    boolean success = sendInstallSnapshot(peerId);
                    if (success) {
                        log.info("Successfully sent snapshot to {}", peerId);
                    }
                } catch (Exception e) {
                    log.error("Failed to send snapshot to {}: {}", peerId, e.getMessage());
                }
                continue;  // Skip AppendEntries for this peer
            }
            
            int prevLogIndex = peerNextIndex - 1;
            int prevLogTerm;
            
            if (prevLogIndex < 0) {
                prevLogTerm = 0;
            } else if (lastSnapshot != null && prevLogIndex == lastSnapshot.getLastIncludedIndex()) {
                prevLogTerm = lastSnapshot.getLastIncludedTerm();
            } else {
                prevLogTerm = getLogTermAt(prevLogIndex);
            }
            
            List<GrpcLogEntry> entries = new ArrayList<>();
            int startPhysicalIndex = logicalToPhysical(peerNextIndex);
            for (int i = Math.max(0, startPhysicalIndex); i < raftLog.size(); i++) {
                LogEntry entry1 = raftLog.get(i);
                entries.add(convertToGrpcLogEntry(entry1));
            }
            
            AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                .setTerm(currentTerm.get())
                .setLeaderId(config.getNodeId())
                .setPrevLogIndex(prevLogIndex)
                .setPrevLogTerm(prevLogTerm)
                .addAllEntries(entries)
                .setLeaderCommit(commitIndex.get())
                .build();
            
            try {
                AppendEntriesResponse response = stub.appendEntries(request);
                
                if (response.getTerm() > currentTerm.get()) {
                    becomeFollower(response.getTerm());
                    return;
                }
                
                if (response.getSuccess()) {
                    if (!entries.isEmpty()) {
                        // Update match and next indices (logical indices)
                        int newMatchIndex = prevLogIndex + entries.size();
                        int newNextIndex = newMatchIndex + 1;
                        matchIndex.put(peerId, newMatchIndex);
                        nextIndex.put(peerId, newNextIndex);
                        updateCommitIndex();
                        applyCommittedEntries();
                    }
                } else {
                    // Decrement nextIndex but don't go below base index
                    int baseIndex = getLogBaseIndex();
                    nextIndex.put(peerId, Math.max(baseIndex, peerNextIndex - 1));
                }
            } catch (Exception e) {
                log.debug("Failed to send AppendEntries to {}: {}", peerId, e.getMessage());
            }
        }
        
        // After sending to all followers, update commit index based on majority
        // This ensures we commit as soon as majority has replicated
        updateCommitIndex();
    }
    
    private void updateCommitIndex() {
        if (state != NodeState.LEADER) {
            return;
        }
        
        // Collect match indices (logical indices) from all peers + self
        List<Integer> indices = new ArrayList<>(matchIndex.values());
        indices.add(getLastLogIndex());  // Leader has all entries
        Collections.sort(indices, Collections.reverseOrder());
        
        // Find the median (majority) index
        int majorityIndex = (stubs.size() + 1) / 2;
        if (majorityIndex < indices.size()) {
            int newCommitIndex = indices.get(majorityIndex);
            
            // Only commit entries from current term (Raft safety requirement)
            if (newCommitIndex > commitIndex.get() && getLogTermAt(newCommitIndex) == currentTerm.get()) {
                commitIndex.set(newCommitIndex);
                log.debug("Updated commit index to {} (logical)", newCommitIndex);
                applyCommittedEntries();
            }
        }
    }
    
    private void applyCommittedEntries() {
        // Apply entries from lastApplied+1 to commitIndex (both logical indices)
        while (lastApplied.get() < commitIndex.get()) {
            int logicalIndexToApply = lastApplied.get() + 1;
            int physicalIndex = logicalToPhysical(logicalIndexToApply);
            
            if (physicalIndex >= 0 && physicalIndex < raftLog.size()) {
                LogEntry entry = raftLog.get(physicalIndex);
                
                // Check if this is a configuration entry
                if (entry.isConfigurationEntry()) {
                    applyConfigurationEntry(entry, logicalIndexToApply);
                } else if (entry.getCommand() != null) {
                    // Regular command entry (skip if command is null)
                    stateMachine.add(entry.getCommand());
                    log.debug("Applied entry at logical index {} to state machine: {}", logicalIndexToApply, entry.getCommand());
                }
            }
            lastApplied.set(logicalIndexToApply);
        }
        
        // Check if we should create a snapshot
        checkSnapshotThreshold();
    }
    
    /**
     * Apply a configuration change entry.
     * When C_old,new is committed, we add C_new to the log.
     */
    private void applyConfigurationEntry(LogEntry entry, int index) {
        ClusterConfiguration config = entry.getConfiguration();
        
        log.info("Applying configuration entry at index {}: {}", index, config);
        
        if (config.isJoint()) {
            // C_old,new has been committed
            // Now add C_new configuration entry (only leader does this)
            // BUT: Only if we haven't already added it!
            if (state == NodeState.LEADER) {
                // Check if C_new already exists in the log after this entry
                boolean cNewExists = false;
                for (int i = index + 1; i < raftLog.size(); i++) {
                    LogEntry nextEntry = raftLog.get(i);
                    if (nextEntry.isConfigurationEntry() && !nextEntry.getConfiguration().isJoint()) {
                        cNewExists = true;
                        break;
                    }
                }
                
                if (!cNewExists) {
                    Set<String> newServers = config.getNewServers();
                    
                    ClusterConfiguration newConfig =
                        ClusterConfiguration.createNew(newServers);
                    
                    LogEntry newConfigEntry =
                        new LogEntry(currentTerm.get(), null, newConfig);
                    
                    raftLog.add(newConfigEntry);
                    
                    logEvent(RaftEvent.EventType.MEMBERSHIP_CHANGE_COMMITTED, 
                        "C_old,new committed. Added C_new configuration to log");
                    
                    sendHeartbeats();
                } else {
                    log.debug("C_new already exists in log, skipping duplicate creation");
                }
            }
        } else {
            // C_new has been committed
            // Finalize the configuration change
            Set<String> newServers = config.getNewServers();
            Set<String> oldServers = new HashSet<>(stubs.keySet());
            oldServers.add(this.config.getNodeId());
            
            // Disconnect from removed servers
            for (String nodeId : oldServers) {
                if (!newServers.contains(nodeId) && !nodeId.equals(this.config.getNodeId())) {
                    disconnectFromServer(nodeId);
                    logEvent(RaftEvent.EventType.MEMBERSHIP_CHANGE_COMMITTED,
                        "C_new committed. Disconnected from removed server: " + nodeId);
                }
            }
            
            log.info("Configuration change finalized. New cluster: {}", newServers);
        }
    }
    
    /**
     * Disconnect from a server and clean up resources.
     */
    private void disconnectFromServer(String nodeId) {
        ManagedChannel channel = channels.remove(nodeId);
        if (channel != null) {
            channel.shutdown();
            log.info("Disconnected from server: {}", nodeId);
        }
        stubs.remove(nodeId);
        nextIndex.remove(nodeId);
        matchIndex.remove(nodeId);
    }
    
    public VoteResponse handleVoteRequest(VoteRequest request) {
        log.info("Received vote request from {} for term {}", request.getCandidateId(), request.getTerm());
        
        // Deny votes when suspended
        if (suspended) {
            log.info("Denying vote request - node is suspended");
            return VoteResponse.newBuilder()
                .setTerm(currentTerm.get())
                .setVoteGranted(false)
                .build();
        }
        
        if (request.getTerm() > currentTerm.get()) {
            becomeFollower(request.getTerm());
        }
        
        boolean voteGranted = false;
        
        if (request.getTerm() < currentTerm.get()) {
            voteGranted = false;
            logEvent(RaftEvent.EventType.VOTE_DENIED, 
                "Denied vote to " + request.getCandidateId() + " (stale term " + request.getTerm() + ")");
        } else if (votedFor == null || votedFor.equals(request.getCandidateId())) {
            int lastLogIndex = getLastLogIndex();
            int lastLogTerm = getLogTermAt(lastLogIndex);
            
            boolean logUpToDate = request.getLastLogTerm() > lastLogTerm ||
                (request.getLastLogTerm() == lastLogTerm && request.getLastLogIndex() >= lastLogIndex);
            
            if (logUpToDate) {
                votedFor = request.getCandidateId();
                voteGranted = true;
                lastHeartbeat = System.currentTimeMillis();
                
                // Persist vote (CRITICAL for Raft safety - must persist before responding)
                persistenceService.saveState(config.getNodeId(), currentTerm.get(), votedFor);
                
                startElectionTimer();
                log.info("Voted for {}", request.getCandidateId());
                logEvent(RaftEvent.EventType.VOTE_GRANTED, 
                    "Granted vote to " + request.getCandidateId() + " for term " + request.getTerm());
            } else {
                logEvent(RaftEvent.EventType.VOTE_DENIED, 
                    "Denied vote to " + request.getCandidateId() + " (log not up-to-date)");
            }
        } else {
            logEvent(RaftEvent.EventType.VOTE_DENIED, 
                "Denied vote to " + request.getCandidateId() + " (already voted for " + votedFor + ")");
        }
        
        return VoteResponse.newBuilder()
            .setTerm(currentTerm.get())
            .setVoteGranted(voteGranted)
            .build();
    }
    
    public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        // Reject AppendEntries when suspended
        if (suspended) {
            log.debug("Rejecting AppendEntries - node is suspended");
            return AppendEntriesResponse.newBuilder()
                .setTerm(currentTerm.get())
                .setSuccess(false)
                .build();
        }
        
        lastHeartbeat = System.currentTimeMillis();
        
        if (request.getTerm() > currentTerm.get()) {
            becomeFollower(request.getTerm());
        }
        
        if (request.getTerm() < currentTerm.get()) {
            return AppendEntriesResponse.newBuilder()
                .setTerm(currentTerm.get())
                .setSuccess(false)
                .build();
        }
        
        if (state != NodeState.FOLLOWER) {
            becomeFollower(request.getTerm());
        }
        
        currentLeader = request.getLeaderId();
        startElectionTimer();
        
        if (request.getEntriesCount() == 0) {
            logEvent(RaftEvent.EventType.HEARTBEAT_RECEIVED, 
                "Heartbeat from leader " + request.getLeaderId());
        }
        
        // Check prevLogIndex/prevLogTerm consistency (logical indices)
        int prevLogIndex = request.getPrevLogIndex();
        if (prevLogIndex >= 0) {
            // Check if we have the entry at prevLogIndex
            int prevLogTerm = getLogTermAt(prevLogIndex);
            
            if (prevLogTerm == 0 || prevLogTerm != request.getPrevLogTerm()) {
                log.debug("Log consistency check failed at index {}: expected term {}, got {}", 
                         prevLogIndex, request.getPrevLogTerm(), prevLogTerm);
                return AppendEntriesResponse.newBuilder()
                    .setTerm(currentTerm.get())
                    .setSuccess(false)
                    .build();
            }
        }
        
        // Process new entries (logical indices)
        int newEntryLogicalIndex = prevLogIndex + 1;
        int entriesAdded = 0;
        List<LogEntry> entriesToPersist = new ArrayList<>();
        
        for (GrpcLogEntry entry : request.getEntriesList()) {
            int physicalIndex = logicalToPhysical(newEntryLogicalIndex);
            
            // Check for conflicts
            if (physicalIndex >= 0 && physicalIndex < raftLog.size()) {
                if (raftLog.get(physicalIndex).getTerm() != entry.getTerm()) {
                    // Conflict detected - delete conflicting entries
                    synchronized (raftLog) {
                        raftLog.subList(physicalIndex, raftLog.size()).clear();
                        
                        // Persist log deletion (CRITICAL for consistency)
                        persistenceService.deleteLogEntriesFrom(config.getNodeId(), newEntryLogicalIndex);
                    }
                }
            }
            
            // Append if we don't have this entry
            physicalIndex = logicalToPhysical(newEntryLogicalIndex);
            if (physicalIndex >= raftLog.size()) {
                // Convert gRPC entry back to internal LogEntry
                LogEntry logEntry = convertFromGrpcLogEntry(entry);
                synchronized (raftLog) {
                    raftLog.add(logEntry);
                }
                entriesToPersist.add(logEntry);
                entriesAdded++;
            }
            newEntryLogicalIndex++;
        }
        
        // Persist all new entries in batch (CRITICAL for durability)
        if (!entriesToPersist.isEmpty()) {
            int startLogicalIndex = prevLogIndex + 1;
            persistenceService.appendLogEntries(config.getNodeId(), startLogicalIndex, entriesToPersist);
        }
        
        if (entriesAdded > 0) {
            logEvent(RaftEvent.EventType.LOG_REPLICATED, 
                "Replicated " + entriesAdded + " entries from leader " + request.getLeaderId());
        }
        
        // Update commit index based on leader's commit index (logical indices)
        if (request.getLeaderCommit() > commitIndex.get()) {
            commitIndex.set(Math.min(request.getLeaderCommit(), getLastLogIndex()));
            applyCommittedEntries();
        }
        
        return AppendEntriesResponse.newBuilder()
            .setTerm(currentTerm.get())
            .setSuccess(true)
            .setMatchIndex(getLastLogIndex())  // Logical index of last entry
            .build();
    }
    
    public boolean submitCommand(String command) {
        if (suspended) {
            log.warn("Cannot submit command - node is suspended");
            return false;
        }
        
        if (state != NodeState.LEADER) {
            log.warn("Not a leader, cannot submit command");
            return false;
        }
        
        log.info("Leader received command: {}", command);
        logEvent(RaftEvent.EventType.COMMAND_RECEIVED, 
            "Received command from client: " + command);
        
        LogEntry entry = new LogEntry(currentTerm.get(), command);
        synchronized (raftLog) {
            raftLog.add(entry);
            
            // Persist log entry (CRITICAL for durability)
            int logicalIndex = getLastLogIndex();
            persistenceService.appendLogEntry(config.getNodeId(), logicalIndex, entry);
        }
        
        sendHeartbeats();
        
        return true;
    }
    
    /**
     * Add a server to the cluster (membership change).
     * Uses the two-phase approach from the Raft paper:
     * 1. Add C_old,new configuration
     * 2. After it's committed, add C_new configuration
     */
    public synchronized boolean addServer(ServerInfo serverInfo) {
        if (state != NodeState.LEADER) {
            log.warn("Not a leader, cannot add server");
            return false;
        }
        
        log.info("Leader initiating server addition: {}", serverInfo);
        logEvent(RaftEvent.EventType.SERVER_ADDED, 
            "Initiating addition of server: " + serverInfo.getNodeId());
        
        // Create joint consensus configuration C_old,new
        Set<String> oldServers = new HashSet<>(stubs.keySet());
        oldServers.add(config.getNodeId()); // Include self
        
        Set<String> newServers = new HashSet<>(oldServers);
        newServers.add(serverInfo.getNodeId());
        
        ClusterConfiguration jointConfig =
            ClusterConfiguration.createJoint(oldServers, newServers);
        
        // Add C_old,new to log
        LogEntry configEntry =
            new LogEntry(currentTerm.get(), null, jointConfig);
        raftLog.add(configEntry);
        
        logEvent(RaftEvent.EventType.MEMBERSHIP_CHANGE_START, 
            "Added C_old,new configuration entry to log");
        
        // Connect to new server
        try {
            ManagedChannel channel = ManagedChannelBuilder
                .forAddress(serverInfo.getHost(), serverInfo.getGrpcPort())
                .usePlaintext()
                .build();
            channels.put(serverInfo.getNodeId(), channel);
            stubs.put(serverInfo.getNodeId(), RaftServiceGrpc.newBlockingStub(channel));
            nextIndex.put(serverInfo.getNodeId(), raftLog.size());
            matchIndex.put(serverInfo.getNodeId(), 0);  // COUNT-based: 0 = new server has no entries replicated yet
            log.info("Connected to new server: {}", serverInfo);
        } catch (Exception e) {
            log.error("Failed to connect to new server: {}", serverInfo, e);
            return false;
        }
        
        sendHeartbeats();
        
        return true;
    }
    
    /**
     * Remove a server from the cluster (membership change).
     */
    public synchronized boolean removeServer(String nodeId) {
        if (state != NodeState.LEADER) {
            log.warn("Not a leader, cannot remove server");
            return false;
        }
        
        if (nodeId.equals(config.getNodeId())) {
            log.warn("Cannot remove self from cluster");
            return false;
        }
        
        log.info("Leader initiating server removal: {}", nodeId);
        logEvent(RaftEvent.EventType.SERVER_REMOVED, 
            "Initiating removal of server: " + nodeId);
        
        // Create joint consensus configuration C_old,new
        Set<String> oldServers = new HashSet<>(stubs.keySet());
        oldServers.add(config.getNodeId()); // Include self
        
        Set<String> newServers = new HashSet<>(oldServers);
        newServers.remove(nodeId);
        
        ClusterConfiguration jointConfig =
            ClusterConfiguration.createJoint(oldServers, newServers);
        
        // Add C_old,new to log
        LogEntry configEntry =
            new LogEntry(currentTerm.get(), null, jointConfig);
        raftLog.add(configEntry);
        
        logEvent(RaftEvent.EventType.MEMBERSHIP_CHANGE_START, 
            "Added C_old,new configuration entry to log");
        
        sendHeartbeats();
        
        return true;
    }
    
    /**
     * Get the current cluster configuration.
     */
    public Set<String> getClusterMembers() {
        Set<String> members = new HashSet<>(stubs.keySet());
        members.add(config.getNodeId());
        return members;
    }
    
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", config.getNodeId());
        status.put("state", suspended ? "SUSPENDED" : state.name());
        status.put("currentTerm", currentTerm.get());
        status.put("currentLeader", currentLeader);
        status.put("commitIndex", commitIndex.get());
        status.put("lastApplied", lastApplied.get());
        // Return logical log size: total entries including those in snapshot
        int logicalLogSize = getLogBaseIndex() + raftLog.size();
        status.put("logSize", logicalLogSize);
        status.put("stateMachine", new ArrayList<>(stateMachine));
        status.put("suspended", suspended);
        
        // Add snapshot information for correct state machine indexing
        if (lastSnapshot != null) {
            status.put("snapshotBaseIndex", lastSnapshot.getLastIncludedIndex());
            status.put("hasSnapshot", true);
        } else {
            status.put("snapshotBaseIndex", 0);
            status.put("hasSnapshot", false);
        }
        
        return status;
    }
    
    /**
     * Convert internal LogEntry to gRPC LogEntry for network transmission.
     */
    private GrpcLogEntry convertToGrpcLogEntry(LogEntry entry) {
        GrpcLogEntry.Builder builder = GrpcLogEntry.newBuilder()
            .setTerm(entry.getTerm());
        
        // Set command if present
        if (entry.getCommand() != null) {
            builder.setCommand(entry.getCommand());
        }
        
        // Set configuration if this is a configuration entry
        if (entry.isConfigurationEntry() && entry.getConfiguration() != null) {
            builder.setConfiguration(convertToGrpcConfiguration(entry.getConfiguration()));
        }
        
        return builder.build();
    }
    
    /**
     * Convert gRPC LogEntry back to internal LogEntry.
     */
    private LogEntry convertFromGrpcLogEntry(GrpcLogEntry grpcEntry) {
        // Check if this is a configuration entry
        if (grpcEntry.hasConfiguration()) {
            ClusterConfiguration config = convertFromGrpcConfiguration(grpcEntry.getConfiguration());
            return new LogEntry(grpcEntry.getTerm(), null, config);
        } else {
            // Regular command entry
            String command = grpcEntry.getCommand();
            if (command != null && command.isEmpty()) {
                command = null; // Treat empty string as null
            }
            return new LogEntry(grpcEntry.getTerm(), command);
        }
    }
    
    /**
     * Convert ClusterConfiguration to gRPC ConfigurationEntry.
     */
    private com.example.raftimplementation.grpc.ConfigurationEntry convertToGrpcConfiguration(
            ClusterConfiguration config) {
        com.example.raftimplementation.grpc.ConfigurationEntry.Builder builder = 
            com.example.raftimplementation.grpc.ConfigurationEntry.newBuilder()
                .setIsJoint(config.isJoint());
        
        // Add old servers - just nodeIds since we don't have full ServerInfo
        for (String nodeId : config.getOldServers()) {
            builder.addOldServers(
                com.example.raftimplementation.grpc.ServerEntry.newBuilder()
                    .setNodeId(nodeId)
                    .setHost("") // Unknown in this context
                    .setGrpcPort(0)
                    .setHttpPort(0)
                    .build()
            );
        }
        
        // Add new servers
        for (String nodeId : config.getNewServers()) {
            builder.addNewServers(
                com.example.raftimplementation.grpc.ServerEntry.newBuilder()
                    .setNodeId(nodeId)
                    .setHost("") // Unknown in this context
                    .setGrpcPort(0)
                    .setHttpPort(0)
                    .build()
            );
        }
        
        return builder.build();
    }
    
    /**
     * Convert gRPC ConfigurationEntry back to ClusterConfiguration.
     */
    private ClusterConfiguration convertFromGrpcConfiguration(
            com.example.raftimplementation.grpc.ConfigurationEntry grpcConfig) {
        Set<String> oldServers = new HashSet<>();
        for (com.example.raftimplementation.grpc.ServerEntry server : grpcConfig.getOldServersList()) {
            oldServers.add(server.getNodeId());
        }
        
        Set<String> newServers = new HashSet<>();
        for (com.example.raftimplementation.grpc.ServerEntry server : grpcConfig.getNewServersList()) {
            newServers.add(server.getNodeId());
        }
        
        if (grpcConfig.getIsJoint()) {
            return ClusterConfiguration.createJoint(oldServers, newServers);
        } else {
            return ClusterConfiguration.createNew(newServers);
        }
    }
    
    /**
     * Create a snapshot of the current state machine.
     * This is used for log compaction to prevent unbounded log growth.
     */
    public synchronized void createSnapshot() {
        if (raftLog.isEmpty()) {
            log.debug("Cannot create snapshot: log is empty");
            return;
        }
        
        int lastIndex = lastApplied.get();
        if (lastIndex <= 0) {
            log.debug("Cannot create snapshot: no entries applied yet");
            return;
        }
        
        // Don't create snapshot if we already have a recent one
        if (lastSnapshot != null && lastIndex <= lastSnapshot.getLastIncludedIndex()) {
            log.debug("Snapshot already exists for index {}", lastIndex);
            return;
        }
        
        try {
            // Get the term of the last applied entry (lastIndex is logical)
            int lastTerm = getLogTermAt(lastIndex);
            
            // Copy state machine state
            List<String> stateCopy = new ArrayList<>(stateMachine);
            
            // Find current configuration
            ClusterConfiguration currentConfig = findCurrentConfiguration();
            
            // Calculate size
            long sizeBytes = estimateSnapshotSize(stateCopy);
            
            // Create snapshot
            Snapshot snapshot = new Snapshot(
                lastIndex,
                lastTerm,
                stateCopy,
                currentConfig,
                System.currentTimeMillis(),
                sizeBytes
            );
            
            // Update snapshot state
            lastSnapshot = snapshot;
            totalSnapshots.incrementAndGet();
            
            // Persist snapshot (CRITICAL for recovery after compaction)
            persistenceService.saveSnapshot(config.getNodeId(), snapshot);
            
            // Compact the log: remove entries up to lastIndex
            int entriesRemoved = compactLog(lastIndex);
            compactedEntries.addAndGet(entriesRemoved);
            
            // Persist log compaction (delete old entries from database)
            persistenceService.compactLog(config.getNodeId(), lastIndex);
            
            log.info("Created snapshot at index {} (term {}), compacted {} entries, size: {} bytes",
                lastIndex, lastTerm, entriesRemoved, sizeBytes);
            
            logEvent(RaftEvent.EventType.SNAPSHOT_CREATED,
                String.format("Created snapshot at index %d, compacted %d entries", lastIndex, entriesRemoved));
            
        } catch (Exception e) {
            log.error("Failed to create snapshot", e);
        }
    }
    
    /**
     * Compact the log by removing entries up to AND INCLUDING the snapshot index.
     * After snapshot at index N, we only keep entries from index N+1 onwards.
     * Returns the number of entries removed.
     */
    private int compactLog(int snapshotIndex) {
        if (snapshotIndex <= 0 || raftLog.isEmpty()) {
            return 0;
        }
        
        // Calculate how many entries to remove based on current baseIndex
        int currentBaseIndex = getLogBaseIndex();
        
        // If we don't have a snapshot yet, base is 0, so we remove entries 0..snapshotIndex (inclusive)
        // If we already have a snapshot, we need to calculate physical positions
        int physicalIndexOfSnapshot = logicalToPhysical(snapshotIndex);
        
        // Remove all entries up to and including the snapshot index
        // After snapshot at index N, first entry should be at logical index N+1
        int toRemove = physicalIndexOfSnapshot + 1;
        toRemove = Math.max(0, Math.min(toRemove, raftLog.size()));
        
        synchronized (raftLog) {
            for (int i = 0; i < toRemove; i++) {
                raftLog.remove(0); // Always remove first element
            }
        }
        
        return toRemove;
    }
    
    /**
     * Get the base index - the logical index of the first entry in raftLog.
     * If we have a snapshot, baseIndex = lastIncludedIndex + 1
     * Otherwise, baseIndex = 0
     */
    private int getLogBaseIndex() {
        return lastSnapshot != null ? lastSnapshot.getLastIncludedIndex() + 1 : 0;
    }
    
    /**
     * Convert logical index to physical index in raftLog array.
     * Logical index: absolute position (0, 1, 2, 3, ...)
     * Physical index: position in raftLog array after compaction
     * 
     * Example: If snapshot at index 10, raftLog[0] is logically at index 11
     */
    private int logicalToPhysical(int logicalIndex) {
        return logicalIndex - getLogBaseIndex();
    }
    
    /**
     * Convert physical index in raftLog array to logical index.
     */
    private int physicalToLogical(int physicalIndex) {
        return physicalIndex + getLogBaseIndex();
    }
    
    /**
     * Get the last log index (logical).
     */
    private int getLastLogIndex() {
        if (raftLog.isEmpty()) {
            return lastSnapshot != null ? lastSnapshot.getLastIncludedIndex() : -1;
        }
        return physicalToLogical(raftLog.size() - 1);
    }
    
    /**
     * Get the term at a logical index.
     * Returns 0 if index is before snapshot, or snapshot term if at snapshot boundary.
     */
    private int getLogTermAt(int logicalIndex) {
        if (lastSnapshot != null && logicalIndex == lastSnapshot.getLastIncludedIndex()) {
            return lastSnapshot.getLastIncludedTerm();
        }
        
        int physicalIndex = logicalToPhysical(logicalIndex);
        if (physicalIndex < 0 || physicalIndex >= raftLog.size()) {
            return 0;
        }
        
        return raftLog.get(physicalIndex).getTerm();
    }
    
    /**
     * Find the current cluster configuration from the log.
     */
    private ClusterConfiguration findCurrentConfiguration() {
        // Search backwards through log for most recent configuration
        for (int i = raftLog.size() - 1; i >= 0; i--) {
            LogEntry entry = raftLog.get(i);
            if (entry.isConfigurationEntry()) {
                return entry.getConfiguration();
            }
        }
        
        // If no configuration found, create default from current peers
        Set<String> currentServers = new HashSet<>(stubs.keySet());
        currentServers.add(config.getNodeId());
        return ClusterConfiguration.createNew(currentServers);
    }
    
    /**
     * Estimate the size of a snapshot in bytes.
     */
    private long estimateSnapshotSize(List<String> state) {
        // Rough estimate: each string averages 50 bytes
        return state.size() * 50L;
    }
    
    /**
     * Check if we should create a snapshot based on log size.
     */
    private void checkSnapshotThreshold() {
        int logSize = raftLog.size();
        int appliedCount = lastApplied.get();
        
        // Create snapshot if we have enough applied entries
        if (logSize >= SNAPSHOT_THRESHOLD && appliedCount >= SNAPSHOT_THRESHOLD / 2) {
            log.info("Log size ({}) reached snapshot threshold ({}), creating snapshot", 
                logSize, SNAPSHOT_THRESHOLD);
            createSnapshot();
        }
    }
    
    /**
     * Install a snapshot received from the leader.
     * This is used when a follower is too far behind.
     */
    public synchronized void installSnapshot(Snapshot snapshot) {
        if (snapshot == null) {
            log.warn("Cannot install null snapshot");
            return;
        }
        
        // Verify snapshot is newer than our current one
        if (lastSnapshot != null && snapshot.getLastIncludedIndex() <= lastSnapshot.getLastIncludedIndex()) {
            log.debug("Ignoring older snapshot (index {})", snapshot.getLastIncludedIndex());
            return;
        }
        
        log.info("Installing snapshot at index {} (term {})", 
            snapshot.getLastIncludedIndex(), snapshot.getLastIncludedTerm());
        
        // Discard entire log if snapshot is more recent
        if (snapshot.getLastIncludedIndex() >= raftLog.size()) {
            raftLog.clear();
        } else {
            // Keep only log entries after snapshot
            synchronized (raftLog) {
                int toRemove = snapshot.getLastIncludedIndex();
                for (int i = 0; i < toRemove && !raftLog.isEmpty(); i++) {
                    raftLog.remove(0);
                }
            }
        }
        
        // Install snapshot
        lastSnapshot = snapshot;
        
        // Restore state machine
        stateMachine.clear();
        stateMachine.addAll(snapshot.getStateMachineState());
        
        // Update indices
        lastApplied.set(snapshot.getLastIncludedIndex());
        commitIndex.set(Math.max(commitIndex.get(), snapshot.getLastIncludedIndex()));
        
        log.info("Snapshot installed successfully, state machine size: {}", stateMachine.size());
        
        logEvent(RaftEvent.EventType.SNAPSHOT_INSTALLED,
            String.format("Installed snapshot at index %d", snapshot.getLastIncludedIndex()));
    }
    
    // ==================== Advanced Raft Features ====================
    
    /**
     * InstallSnapshot RPC - Leader sends snapshot to follower that's too far behind.
     */
    public InstallSnapshotResponse handleInstallSnapshot(InstallSnapshotRequest request) {
        log.info("Received InstallSnapshot RPC from {} for term {}, lastIncludedIndex: {}", 
                 request.getLeaderId(), request.getTerm(), request.getLastIncludedIndex());
        
        // Reply false if term < currentTerm
        if (request.getTerm() < currentTerm.get()) {
            log.debug("Rejecting snapshot from {} due to stale term {} < {}", 
                     request.getLeaderId(), request.getTerm(), currentTerm.get());
            return InstallSnapshotResponse.newBuilder()
                .setTerm(currentTerm.get())
                .setSuccess(false)
                .build();
        }
        
        // Update term if necessary
        if (request.getTerm() > currentTerm.get()) {
            currentTerm.set(request.getTerm());
            state = NodeState.FOLLOWER;
            votedFor = null;
            
            // Persist state change
            persistenceService.saveState(config.getNodeId(), request.getTerm(), null);
            
            logEvent(RaftEvent.EventType.TERM_INCREASED, 
                    "Term increased to " + request.getTerm() + " by InstallSnapshot from " + request.getLeaderId());
        }
        
        // Reset election timer
        lastHeartbeat = System.currentTimeMillis();
        currentLeader = request.getLeaderId();
        
        // If snapshot is outdated, reject it
        if (request.getLastIncludedIndex() <= commitIndex.get()) {
            log.debug("Snapshot is outdated (lastIncludedIndex: {} <= commitIndex: {}), ignoring", 
                     request.getLastIncludedIndex(), commitIndex.get());
            return InstallSnapshotResponse.newBuilder()
                .setTerm(currentTerm.get())
                .setSuccess(true)  // Already have this data
                .build();
        }
        
        try {
            // Deserialize snapshot data
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(request.getData().toByteArray());
            java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bis);
            
            @SuppressWarnings("unchecked")
            List<String> snapshotStateMachine = (List<String>) ois.readObject();
            ois.readInt(); // snapshotTerm - not used in current implementation
            
            // Calculate size for monitoring
            long sizeBytes = request.getData().size();
            
            // Create Snapshot object
            Snapshot snapshot = new Snapshot(
                request.getLastIncludedIndex(),
                request.getLastIncludedTerm(),
                new ArrayList<>(snapshotStateMachine),
                null,  // ClusterConfiguration - not included in this implementation
                System.currentTimeMillis(),
                sizeBytes
            );
            
            // Install snapshot
            synchronized (stateMachine) {
                stateMachine.clear();
                stateMachine.addAll(snapshotStateMachine);
            }
            
            synchronized (raftLog) {
                // Discard entire log if snapshot is newer
                if (request.getLastIncludedIndex() >= raftLog.size()) {
                    int discarded = raftLog.size();
                    raftLog.clear();
                    log.info("Discarded entire log ({} entries) due to snapshot", discarded);
                } else {
                    // Discard log entries up to lastIncludedIndex
                    int entriesToDiscard = Math.min(request.getLastIncludedIndex(), raftLog.size());
                    if (entriesToDiscard > 0) {
                        raftLog.subList(0, entriesToDiscard).clear();
                        log.info("Discarded {} log entries due to snapshot", entriesToDiscard);
                    }
                }
            }
            
            // Update snapshot and indices
            lastSnapshot = snapshot;
            commitIndex.set(Math.max(commitIndex.get(), request.getLastIncludedIndex()));
            lastApplied.set(Math.max(lastApplied.get(), request.getLastIncludedIndex()));
            compactedEntries.addAndGet(request.getLastIncludedIndex());
            
            // Persist snapshot and compacted log (CRITICAL for recovery)
            persistenceService.saveSnapshot(config.getNodeId(), snapshot);
            persistenceService.compactLog(config.getNodeId(), request.getLastIncludedIndex());
            
            logEvent(RaftEvent.EventType.SNAPSHOT_INSTALLED, 
                    String.format("Installed snapshot from %s (index: %d, term: %d, size: %d entries)",
                                request.getLeaderId(), request.getLastIncludedIndex(), 
                                request.getLastIncludedTerm(), snapshotStateMachine.size()));
            
            log.info("Successfully installed snapshot from {} (lastIncludedIndex: {}, stateMachineSize: {})",
                    request.getLeaderId(), request.getLastIncludedIndex(), snapshotStateMachine.size());
            
            return InstallSnapshotResponse.newBuilder()
                .setTerm(currentTerm.get())
                .setSuccess(true)
                .build();
                
        } catch (Exception e) {
            log.error("Failed to install snapshot from {}: {}", request.getLeaderId(), e.getMessage(), e);
            return InstallSnapshotResponse.newBuilder()
                .setTerm(currentTerm.get())
                .setSuccess(false)
                .build();
        }
    }
    
    /**
     * Send InstallSnapshot RPC to a follower that's too far behind.
     */
    public boolean sendInstallSnapshot(String peerId) {
        if (lastSnapshot == null) {
            log.warn("Cannot send snapshot to {} - no snapshot available", peerId);
            return false;
        }
        
        try {
            // Serialize snapshot data
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos);
            oos.writeObject(lastSnapshot.getStateMachineState());
            oos.writeInt(lastSnapshot.getLastIncludedTerm());
            oos.flush();
            
            InstallSnapshotRequest request = InstallSnapshotRequest.newBuilder()
                .setTerm(currentTerm.get())
                .setLeaderId(config.getNodeId())
                .setLastIncludedIndex(lastSnapshot.getLastIncludedIndex())
                .setLastIncludedTerm(lastSnapshot.getLastIncludedTerm())
                .setData(com.google.protobuf.ByteString.copyFrom(bos.toByteArray()))
                .setDone(true)
                .setOffset(0)
                .build();
            
            RaftServiceGrpc.RaftServiceBlockingStub stub = stubs.get(peerId);
            if (stub == null) {
                log.warn("No stub available for peer {}", peerId);
                return false;
            }
            
            InstallSnapshotResponse response = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                .installSnapshot(request);
            
            if (response.getTerm() > currentTerm.get()) {
                currentTerm.set(response.getTerm());
                state = NodeState.FOLLOWER;
                votedFor = null;
                
                // Persist state change
                persistenceService.saveState(config.getNodeId(), response.getTerm(), null);
                
                logEvent(RaftEvent.EventType.TERM_INCREASED, 
                        "Term increased to " + response.getTerm() + " by InstallSnapshot response from " + peerId);
                return false;
            }
            
            if (response.getSuccess()) {
                // Update nextIndex and matchIndex for this follower
                nextIndex.put(peerId, lastSnapshot.getLastIncludedIndex() + 1);
                matchIndex.put(peerId, lastSnapshot.getLastIncludedIndex());
                
                log.info("Successfully sent snapshot to {} (lastIncludedIndex: {})", 
                        peerId, lastSnapshot.getLastIncludedIndex());
                return true;
            } else {
                log.warn("Follower {} rejected snapshot", peerId);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Failed to send snapshot to {}: {}", peerId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Pre-vote RPC - Check if candidate would win election before disrupting cluster.
     */
    public VoteResponse handlePreVote(VoteRequest request) {
        log.debug("Received PreVote RPC from {} for term {}", request.getCandidateId(), request.getTerm());
        
        // Grant pre-vote if:
        // 1. Candidate's log is at least as up-to-date as receiver's log
        // 2. Haven't heard from current leader recently (election timeout elapsed)
        
        boolean logUpToDate = isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm());
        boolean electionTimeoutElapsed = (System.currentTimeMillis() - lastHeartbeat) > 
            (ELECTION_TIMEOUT_MIN + ELECTION_TIMEOUT_MAX) / 2;
        
        boolean grantVote = logUpToDate && electionTimeoutElapsed;
        
        log.debug("PreVote for {}: logUpToDate={}, timeoutElapsed={}, granted={}", 
                 request.getCandidateId(), logUpToDate, electionTimeoutElapsed, grantVote);
        
        return VoteResponse.newBuilder()
            .setTerm(currentTerm.get())
            .setVoteGranted(grantVote)
            .build();
    }
    
    /**
     * Perform pre-vote phase before actual election.
     * Returns true if pre-vote was successful (would win election).
     */
    public boolean performPreVote() {
        log.info("Starting pre-vote phase for term {}", currentTerm.get() + 1);
        
        int votesReceived = 1; // Vote for self
        int votesNeeded = (stubs.size() + 1) / 2 + 1;
        
        int lastLogIndex = raftLog.size();
        int lastLogTerm = lastLogIndex > 0 ? 
            raftLog.get(lastLogIndex - 1).getTerm() : 0;
        
        VoteRequest preVoteRequest = VoteRequest.newBuilder()
            .setTerm(currentTerm.get() + 1)
            .setCandidateId(config.getNodeId())
            .setLastLogIndex(lastLogIndex)
            .setLastLogTerm(lastLogTerm)
            .build();
        
        for (String peerId : stubs.keySet()) {
            try {
                VoteResponse response = stubs.get(peerId)
                    .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                    .preVote(preVoteRequest);
                
                if (response.getVoteGranted()) {
                    votesReceived++;
                    log.debug("PreVote granted by {} ({}/{})", peerId, votesReceived, votesNeeded);
                }
                
                if (votesReceived >= votesNeeded) {
                    log.info("Pre-vote successful! Proceeding with actual election ({}/{})", 
                            votesReceived, votesNeeded);
                    return true;
                }
            } catch (Exception e) {
                log.debug("Failed to get pre-vote from {}: {}", peerId, e.getMessage());
            }
        }
        
        log.info("Pre-vote failed ({}/{}), not starting election", votesReceived, votesNeeded);
        return false;
    }
    
    /**
     * Linearizable read optimization - confirm leadership before responding to reads.
     * 
     * @return true if still leader and can safely respond to reads
     */
    public boolean confirmLeadership() {
        if (state != NodeState.LEADER) {
            return false;
        }
        
        // Send heartbeats to majority of followers and wait for acknowledgment
        int acks = 1; // Self
        int needed = (stubs.size() + 1) / 2 + 1;
        
        for (String peerId : stubs.keySet()) {
            try {
                AppendEntriesRequest heartbeat = AppendEntriesRequest.newBuilder()
                    .setTerm(currentTerm.get())
                    .setLeaderId(config.getNodeId())
                    .setPrevLogIndex(raftLog.size())
                    .setPrevLogTerm(raftLog.isEmpty() ? 0 : 
                        raftLog.get(raftLog.size() - 1).getTerm())
                    .setLeaderCommit(commitIndex.get())
                    .build();
                
                AppendEntriesResponse response = stubs.get(peerId)
                    .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                    .appendEntries(heartbeat);
                
                if (response.getTerm() == currentTerm.get() && response.getSuccess()) {
                    acks++;
                }
                
                if (response.getTerm() > currentTerm.get()) {
                    // Step down if higher term discovered
                    currentTerm.set(response.getTerm());
                    state = NodeState.FOLLOWER;
                    
                    // Persist state change
                    persistenceService.saveState(config.getNodeId(), response.getTerm(), null);
                    
                    return false;
                }
                
                if (acks >= needed) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("Failed to confirm leadership with {}: {}", peerId, e.getMessage());
            }
        }
        
        return acks >= needed;
    }
    
    /**
     * Simple leadership check without heartbeat confirmation.
     * Less strict than confirmLeadership(), suitable for dashboard reads.
     * 
     * @return true if this node is currently the leader
     */
    public boolean isLeader() {
        return state == NodeState.LEADER;
    }
    
    /**
     * Check if a log is at least as up-to-date as our log.
     */
    private boolean isLogUpToDate(int lastLogIndex, int lastLogTerm) {
        int myLastIndex = raftLog.size();
        int myLastTerm = myLastIndex > 0 ? raftLog.get(myLastIndex - 1).getTerm() : 0;
        
        if (lastLogTerm != myLastTerm) {
            return lastLogTerm > myLastTerm;
        }
        return lastLogIndex >= myLastIndex;
    }
    
    // Getters
    
    public NodeState getState() {
        return state;
    }
    
    public AtomicInteger getCurrentTerm() {
        return currentTerm;
    }
    
    public String getVotedFor() {
        return votedFor;
    }
    
    public String getCurrentLeader() {
        return currentLeader;
    }
    
    public AtomicInteger getCommitIndex() {
        return commitIndex;
    }
    
    public AtomicInteger getLastApplied() {
        return lastApplied;
    }
    
    public List<LogEntry> getRaftLog() {
        return raftLog;
    }
    
    public List<String> getStateMachine() {
        return stateMachine;
    }
    
    public RaftConfig getConfig() {
        return config;
    }
    
    public Map<String, RaftServiceGrpc.RaftServiceBlockingStub> getStubs() {
        return stubs;
    }
    
    public Map<String, Integer> getNextIndex() {
        return nextIndex;
    }
    
    public Map<String, Integer> getMatchIndex() {
        return matchIndex;
    }
    
    public boolean isSuspended() {
        return suspended;
    }
    
    public Snapshot getLastSnapshot() {
        return lastSnapshot;
    }
    
    public AtomicInteger getCompactedEntries() {
        return compactedEntries;
    }
    
    public AtomicInteger getTotalSnapshots() {
        return totalSnapshots;
    }
    
    // Setters for compatibility
    
    public void setState(NodeState newState) {
        this.state = newState;
    }
    
    public void setVotedFor(String nodeId) {
        this.votedFor = nodeId;
        // Persist state change
        persistenceService.saveState(config.getNodeId(), currentTerm.get(), nodeId);
    }
    
    public void setCurrentLeader(String leaderId) {
        this.currentLeader = leaderId;
    }
    
    public void setLastHeartbeat(long timestamp) {
        this.lastHeartbeat = timestamp;
    }
    
    public void setLastSnapshot(Snapshot snapshot) {
        this.lastSnapshot = snapshot;
    }
    
    public long getLastHeartbeat() {
        return this.lastHeartbeat;
    }
}


