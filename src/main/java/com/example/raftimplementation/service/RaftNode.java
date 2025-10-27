package com.example.raftimplementation.service;

import com.example.raftimplementation.config.RaftConfig;
import com.example.raftimplementation.grpc.*;
import com.example.raftimplementation.model.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@Getter
public class RaftNode {
    private final RaftConfig config;
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
    
    public RaftNode(RaftConfig config) {
        this.config = config;
    }
    
    @PostConstruct
    public void init() {
        log.info("Initializing Raft node: {}", config.getNodeId());
        

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
        
        log.info("Election timeout! Starting election...");
        logEvent(RaftEvent.EventType.STATE_CHANGE,
            "FOLLOWER → CANDIDATE");
        state = NodeState.CANDIDATE;
        currentTerm.incrementAndGet();
        logEvent(RaftEvent.EventType.TERM_INCREASED,
            "Term increased to " + currentTerm.get());
        votedFor = config.getNodeId();
        currentLeader = null;
        
        int votesReceived = 1;
        int votesNeeded = (stubs.size() + 2) / 2; 
        
        log.info("Node {} starting election for term {}", config.getNodeId(), currentTerm.get());
        logEvent(RaftEvent.EventType.ELECTION_START,
            "Starting election, need " + votesNeeded + " votes");
        
        int lastLogIndex = raftLog.size() - 1;
        int lastLogTerm = lastLogIndex >= 0 ? raftLog.get(lastLogIndex).getTerm() : 0;
        
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

        for (String peerId : stubs.keySet()) {
            nextIndex.put(peerId, raftLog.size());
            matchIndex.put(peerId, 0);  // COUNT-based: 0 = follower has no entries replicated yet
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
            
            int peerNextIndex = nextIndex.getOrDefault(peerId, 0);
            int prevLogIndex = peerNextIndex - 1;
            int prevLogTerm = prevLogIndex >= 0 && prevLogIndex < raftLog.size() ? raftLog.get(prevLogIndex).getTerm() : 0;
            
            List<GrpcLogEntry> entries = new ArrayList<>();
            for (int i = peerNextIndex; i < raftLog.size(); i++) {
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
                        // COUNT-based: how many entries the follower now has
                        int newMatchCount = prevLogIndex + 1 + entries.size();
                        matchIndex.put(peerId, newMatchCount);
                        nextIndex.put(peerId, newMatchCount);
                        updateCommitIndex();
                        applyCommittedEntries();
                    }
                } else {
                    nextIndex.put(peerId, Math.max(0, peerNextIndex - 1));
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
        List<Integer> indices = new ArrayList<>(matchIndex.values());
        indices.add(raftLog.size());  // COUNT-based: Leader has all entries
        Collections.sort(indices, Collections.reverseOrder());
        
        int majorityIndex = (stubs.size() + 1) / 2;
        if (majorityIndex < indices.size()) {
            int newCommitCount = indices.get(majorityIndex);
            
            // commitIndex is COUNT-based: how many entries are committed
            // newCommitCount > commitIndex means we can advance
            // We need to verify the entry at index (newCommitCount - 1) is from current term
            if (newCommitCount > commitIndex.get() &&
                newCommitCount > 0 &&
                newCommitCount <= raftLog.size() &&
                raftLog.get(newCommitCount - 1).getTerm() == currentTerm.get()) {
                commitIndex.set(newCommitCount);
                log.debug("Updated commit index to {} (count)", newCommitCount);
                applyCommittedEntries();
            }
        }
    }
    
    private void applyCommittedEntries() {
        // lastApplied and commitIndex are COUNT-based (how many entries processed)
        // Apply entries from index lastApplied to index (commitIndex - 1)
        while (lastApplied.get() < commitIndex.get()) {
            int indexToApply = lastApplied.get();  // Current lastApplied count = next index to apply
            if (indexToApply < raftLog.size()) {
                LogEntry entry = raftLog.get(indexToApply);
                
                // Check if this is a configuration entry
                if (entry.isConfigurationEntry()) {
                    applyConfigurationEntry(entry, indexToApply);
                } else if (entry.getCommand() != null) {
                    // Regular command entry (skip if command is null)
                    stateMachine.add(entry.getCommand());
                    log.debug("Applied entry to state machine: {}", entry.getCommand());
                }
            }
            lastApplied.incrementAndGet();  // Increment count after applying
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
            int lastLogIndex = raftLog.size() - 1;
            int lastLogTerm = lastLogIndex >= 0 ? raftLog.get(lastLogIndex).getTerm() : 0;
            
            boolean logUpToDate = request.getLastLogTerm() > lastLogTerm ||
                (request.getLastLogTerm() == lastLogTerm && request.getLastLogIndex() >= lastLogIndex);
            
            if (logUpToDate) {
                votedFor = request.getCandidateId();
                voteGranted = true;
                lastHeartbeat = System.currentTimeMillis();
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
        
        if (request.getPrevLogIndex() >= 0) {
            if (request.getPrevLogIndex() >= raftLog.size() ||
                raftLog.get(request.getPrevLogIndex()).getTerm() != request.getPrevLogTerm()) {
                return AppendEntriesResponse.newBuilder()
                    .setTerm(currentTerm.get())
                    .setSuccess(false)
                    .build();
            }
        }
        
        int newEntryIndex = request.getPrevLogIndex() + 1;
        int entriesAdded = 0;
        for (GrpcLogEntry entry : request.getEntriesList()) {
            if (newEntryIndex < raftLog.size()) {
                if (raftLog.get(newEntryIndex).getTerm() != entry.getTerm()) {
                    raftLog.subList(newEntryIndex, raftLog.size()).clear();
                }
            }
            
            if (newEntryIndex >= raftLog.size()) {
                // Convert gRPC entry back to internal LogEntry
                LogEntry logEntry = convertFromGrpcLogEntry(entry);
                raftLog.add(logEntry);
                entriesAdded++;
            }
            newEntryIndex++;
        }
        
        if (entriesAdded > 0) {
            logEvent(RaftEvent.EventType.LOG_REPLICATED, 
                "Replicated " + entriesAdded + " entries from leader " + request.getLeaderId());
        }
        
        // Update commit index based on leader's commit index (COUNT-based)
        if (request.getLeaderCommit() > commitIndex.get()) {
            commitIndex.set(Math.min(request.getLeaderCommit(), raftLog.size()));
        }
        
        return AppendEntriesResponse.newBuilder()
            .setTerm(currentTerm.get())
            .setSuccess(true)
            .setMatchIndex(raftLog.size())  // COUNT-based: how many entries we have
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
        
        LogEntry entry =
            new LogEntry(currentTerm.get(), command);
        raftLog.add(entry);
        
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
        status.put("logSize", raftLog.size());
        status.put("stateMachine", new ArrayList<>(stateMachine));
        status.put("suspended", suspended);
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
            // Get the term of the last applied entry
            int lastTerm = raftLog.get(lastIndex - 1).getTerm();
            
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
            
            // Compact the log: remove entries up to lastIndex
            int entriesRemoved = compactLog(lastIndex);
            compactedEntries.addAndGet(entriesRemoved);
            
            log.info("Created snapshot at index {} (term {}), compacted {} entries, size: {} bytes",
                lastIndex, lastTerm, entriesRemoved, sizeBytes);
            
            logEvent(RaftEvent.EventType.SNAPSHOT_CREATED,
                String.format("Created snapshot at index %d, compacted %d entries", lastIndex, entriesRemoved));
            
        } catch (Exception e) {
            log.error("Failed to create snapshot", e);
        }
    }
    
    /**
     * Compact the log by removing entries up to (but not including) the snapshot index.
     * Returns the number of entries removed.
     */
    private int compactLog(int snapshotIndex) {
        if (snapshotIndex <= 0 || raftLog.isEmpty()) {
            return 0;
        }
        
        // Remove entries from 0 to snapshotIndex-1
        int toRemove = Math.min(snapshotIndex, raftLog.size());
        
        synchronized (raftLog) {
            for (int i = 0; i < toRemove; i++) {
                raftLog.remove(0); // Always remove first element
            }
        }
        
        // Update indices (they're now relative to snapshot)
        // Note: In a full implementation, you'd need to adjust all index references
        
        return toRemove;
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
    
    /**
     * Get the base index for log entries (accounting for snapshot).
     * This is the index of the last entry in the snapshot.
     */
    public int getLogBaseIndex() {
        return lastSnapshot != null ? lastSnapshot.getLastIncludedIndex() : 0;
    }
    
    /**
     * Get the base term for log entries (accounting for snapshot).
     */
    public int getLogBaseTerm() {
        return lastSnapshot != null ? lastSnapshot.getLastIncludedTerm() : 0;
    }
}


