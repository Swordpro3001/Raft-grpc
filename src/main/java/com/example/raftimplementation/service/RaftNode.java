package com.example.raftimplementation.service;

import com.example.raftimplementation.config.RaftConfig;
import com.example.raftimplementation.grpc.*;
import com.example.raftimplementation.model.NodeState;
import com.example.raftimplementation.model.RaftEvent;
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
    private final List<com.example.raftimplementation.model.LogEntry> raftLog = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger commitIndex = new AtomicInteger(-1);
    private final AtomicInteger lastApplied = new AtomicInteger(-1);
    

    private final Map<String, Integer> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Integer> matchIndex = new ConcurrentHashMap<>();
    

    private final List<String> stateMachine = Collections.synchronizedList(new ArrayList<>());
    

    private final List<com.example.raftimplementation.model.RaftEvent> events = 
        Collections.synchronizedList(new LinkedList<>());
    private static final int MAX_EVENTS = 100; 
    

    private volatile long lastHeartbeat = System.currentTimeMillis();
    private ScheduledFuture<?> electionTask;
    private ScheduledFuture<?> heartbeatTask;
    
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
    
    private void logEvent(com.example.raftimplementation.model.RaftEvent.EventType type, String description) {
        com.example.raftimplementation.model.RaftEvent event = new com.example.raftimplementation.model.RaftEvent(
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
    
    public List<com.example.raftimplementation.model.RaftEvent> getEvents() {
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
        if (state == NodeState.LEADER) {
            return;
        }
        
        log.info("Election timeout! Starting election...");
        logEvent(com.example.raftimplementation.model.RaftEvent.EventType.STATE_CHANGE, 
            "FOLLOWER → CANDIDATE");
        state = NodeState.CANDIDATE;
        currentTerm.incrementAndGet();
        logEvent(com.example.raftimplementation.model.RaftEvent.EventType.TERM_INCREASED, 
            "Term increased to " + currentTerm.get());
        votedFor = config.getNodeId();
        currentLeader = null;
        
        int votesReceived = 1;
        int votesNeeded = (stubs.size() + 2) / 2; 
        
        log.info("Node {} starting election for term {}", config.getNodeId(), currentTerm.get());
        logEvent(com.example.raftimplementation.model.RaftEvent.EventType.ELECTION_START, 
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
                    logEvent(com.example.raftimplementation.model.RaftEvent.EventType.VOTE_GRANTED, 
                        "Vote granted from " + entry.getKey() + " (" + votesReceived + "/" + votesNeeded + ")");
                } else {
                    logEvent(com.example.raftimplementation.model.RaftEvent.EventType.VOTE_DENIED, 
                        "Vote denied from " + entry.getKey());
                }
            } catch (Exception e) {
                log.warn("Failed to request vote from {}: {}", entry.getKey(), e.getMessage());
            }
        }
        
        if (votesReceived >= votesNeeded && state == NodeState.CANDIDATE) {
            logEvent(com.example.raftimplementation.model.RaftEvent.EventType.ELECTION_WON, 
                "Won election with " + votesReceived + " votes");
            becomeLeader();
        } else {
            log.info("Election failed. Votes received: {}, needed: {}", votesReceived, votesNeeded);
            logEvent(com.example.raftimplementation.model.RaftEvent.EventType.ELECTION_LOST, 
                "Lost election: " + votesReceived + "/" + votesNeeded + " votes");
            startElectionTimer();
        }
    }
    
    private void becomeLeader() {
        log.info("Node {} became LEADER for term {}", config.getNodeId(), currentTerm.get());
        logEvent(com.example.raftimplementation.model.RaftEvent.EventType.STATE_CHANGE, 
            "CANDIDATE → LEADER");
        state = NodeState.LEADER;
        currentLeader = config.getNodeId();

        for (String peerId : stubs.keySet()) {
            nextIndex.put(peerId, raftLog.size());
            matchIndex.put(peerId, -1);
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
        logEvent(com.example.raftimplementation.model.RaftEvent.EventType.STATE_CHANGE, 
            oldState + " → FOLLOWER");
        if (newTerm > currentTerm.get()) {
            logEvent(com.example.raftimplementation.model.RaftEvent.EventType.TERM_INCREASED, 
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
            
            List<com.example.raftimplementation.grpc.LogEntry> entries = new ArrayList<>();
            for (int i = peerNextIndex; i < raftLog.size(); i++) {
                com.example.raftimplementation.model.LogEntry entry1 = raftLog.get(i);
                entries.add(com.example.raftimplementation.grpc.LogEntry.newBuilder()
                    .setTerm(entry1.getTerm())
                    .setCommand(entry1.getCommand())
                    .build());
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
                        int newMatchIndex = prevLogIndex + entries.size();
                        matchIndex.put(peerId, newMatchIndex);
                        nextIndex.put(peerId, newMatchIndex + 1);
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
    }
    
    private void updateCommitIndex() {
        List<Integer> indices = new ArrayList<>(matchIndex.values());
        indices.add(raftLog.size() - 1);
        Collections.sort(indices, Collections.reverseOrder());
        
        int majorityIndex = (stubs.size() + 1) / 2;
        if (majorityIndex < indices.size()) {
            int newCommitIndex = indices.get(majorityIndex);
            
            // Allow commitIndex to be set to 0 for first entry
            if (newCommitIndex >= commitIndex.get() && 
                newCommitIndex < raftLog.size() && 
                raftLog.get(newCommitIndex).getTerm() == currentTerm.get()) {
                commitIndex.set(newCommitIndex);
                log.debug("Updated commit index to {}", newCommitIndex);
                applyCommittedEntries();
            }
        }
    }
    
    private void applyCommittedEntries() {
        while (lastApplied.get() < commitIndex.get()) {
            int indexToApply = lastApplied.incrementAndGet();
            if (indexToApply < raftLog.size()) {
                com.example.raftimplementation.model.LogEntry entry = raftLog.get(indexToApply);
                stateMachine.add(entry.getCommand());
                log.debug("Applied entry to state machine: {}", entry.getCommand());
            }
        }
    }
    
    public VoteResponse handleVoteRequest(VoteRequest request) {
        log.info("Received vote request from {} for term {}", request.getCandidateId(), request.getTerm());
        
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
        for (com.example.raftimplementation.grpc.LogEntry entry : request.getEntriesList()) {
            if (newEntryIndex < raftLog.size()) {
                if (raftLog.get(newEntryIndex).getTerm() != entry.getTerm()) {
                    raftLog.subList(newEntryIndex, raftLog.size()).clear();
                }
            }
            
            if (newEntryIndex >= raftLog.size()) {
                raftLog.add(new com.example.raftimplementation.model.LogEntry(entry.getTerm(), entry.getCommand()));
                entriesAdded++;
            }
            newEntryIndex++;
        }
        
        if (entriesAdded > 0) {
            logEvent(RaftEvent.EventType.LOG_REPLICATED, 
                "Replicated " + entriesAdded + " entries from leader " + request.getLeaderId());
        }
        
        if (request.getLeaderCommit() > commitIndex.get()) {
            commitIndex.set(Math.min(request.getLeaderCommit(), raftLog.size() - 1));
        }
        
        return AppendEntriesResponse.newBuilder()
            .setTerm(currentTerm.get())
            .setSuccess(true)
            .setMatchIndex(raftLog.size() - 1)
            .build();
    }
    
    public boolean submitCommand(String command) {
        if (state != NodeState.LEADER) {
            log.warn("Not a leader, cannot submit command");
            return false;
        }
        
        log.info("Leader received command: {}", command);
        logEvent(RaftEvent.EventType.COMMAND_RECEIVED, 
            "Received command from client: " + command);
        
        com.example.raftimplementation.model.LogEntry entry = 
            new com.example.raftimplementation.model.LogEntry(currentTerm.get(), command);
        raftLog.add(entry);
        
        sendHeartbeats();
        
        return true;
    }
    
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", config.getNodeId());
        status.put("state", state.name());
        status.put("currentTerm", currentTerm.get());
        status.put("currentLeader", currentLeader);
        status.put("commitIndex", commitIndex.get());
        status.put("logSize", raftLog.size());
        status.put("stateMachine", new ArrayList<>(stateMachine));
        return status;
    }
}
