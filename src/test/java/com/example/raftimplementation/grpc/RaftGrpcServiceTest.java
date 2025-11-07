package com.example.raftimplementation.grpc;

import com.example.raftimplementation.service.RaftNode;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RaftGrpcService.
 * Tests gRPC service implementation for Raft protocol.
 */
@ExtendWith(MockitoExtension.class)
class RaftGrpcServiceTest {

    @Mock
    private RaftNode raftNode;

    @Mock
    private StreamObserver<VoteResponse> voteResponseObserver;

    @Mock
    private StreamObserver<AppendEntriesResponse> appendEntriesResponseObserver;

    private RaftGrpcService raftGrpcService;

    @BeforeEach
    void setUp() {
        raftGrpcService = new RaftGrpcService(raftNode);
    }

    @Test
    void testRequestVote() {
        VoteRequest request = VoteRequest.newBuilder()
            .setTerm(2)
            .setCandidateId("node2")
            .setLastLogIndex(0)
            .setLastLogTerm(0)
            .build();

        VoteResponse mockResponse = VoteResponse.newBuilder()
            .setTerm(2)
            .setVoteGranted(true)
            .build();

        when(raftNode.handleVoteRequest(any(VoteRequest.class))).thenReturn(mockResponse);

        raftGrpcService.requestVote(request, voteResponseObserver);

        ArgumentCaptor<VoteResponse> responseCaptor = ArgumentCaptor.forClass(VoteResponse.class);
        verify(voteResponseObserver).onNext(responseCaptor.capture());
        verify(voteResponseObserver).onCompleted();

        VoteResponse capturedResponse = responseCaptor.getValue();
        assertEquals(2, capturedResponse.getTerm());
        assertTrue(capturedResponse.getVoteGranted());
    }

    @Test
    void testRequestVoteDenied() {
        VoteRequest request = VoteRequest.newBuilder()
            .setTerm(1)
            .setCandidateId("node3")
            .setLastLogIndex(-1)
            .setLastLogTerm(0)
            .build();

        VoteResponse mockResponse = VoteResponse.newBuilder()
            .setTerm(2)
            .setVoteGranted(false)
            .build();

        when(raftNode.handleVoteRequest(any(VoteRequest.class))).thenReturn(mockResponse);

        raftGrpcService.requestVote(request, voteResponseObserver);

        ArgumentCaptor<VoteResponse> responseCaptor = ArgumentCaptor.forClass(VoteResponse.class);
        verify(voteResponseObserver).onNext(responseCaptor.capture());
        verify(voteResponseObserver).onCompleted();

        VoteResponse capturedResponse = responseCaptor.getValue();
        assertFalse(capturedResponse.getVoteGranted());
    }

    @Test
    void testAppendEntries() {
        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
            .setTerm(1)
            .setLeaderId("node1")
            .setPrevLogIndex(-1)
            .setPrevLogTerm(0)
            .setLeaderCommit(0)
            .build();

        AppendEntriesResponse mockResponse = AppendEntriesResponse.newBuilder()
            .setTerm(1)
            .setSuccess(true)
            .build();

        when(raftNode.handleAppendEntries(any(AppendEntriesRequest.class))).thenReturn(mockResponse);

        raftGrpcService.appendEntries(request, appendEntriesResponseObserver);

        ArgumentCaptor<AppendEntriesResponse> responseCaptor = ArgumentCaptor.forClass(AppendEntriesResponse.class);
        verify(appendEntriesResponseObserver).onNext(responseCaptor.capture());
        verify(appendEntriesResponseObserver).onCompleted();

        AppendEntriesResponse capturedResponse = responseCaptor.getValue();
        assertEquals(1, capturedResponse.getTerm());
        assertTrue(capturedResponse.getSuccess());
    }

    @Test
    void testAppendEntriesWithLogEntry() {
        GrpcLogEntry entry = GrpcLogEntry.newBuilder()
            .setTerm(1)
            .setCommand("SET x=1")
            .build();

        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
            .setTerm(1)
            .setLeaderId("node1")
            .setPrevLogIndex(-1)
            .setPrevLogTerm(0)
            .addEntries(entry)
            .setLeaderCommit(0)
            .build();

        AppendEntriesResponse mockResponse = AppendEntriesResponse.newBuilder()
            .setTerm(1)
            .setSuccess(true)
            .build();

        when(raftNode.handleAppendEntries(any(AppendEntriesRequest.class))).thenReturn(mockResponse);

        raftGrpcService.appendEntries(request, appendEntriesResponseObserver);

        verify(appendEntriesResponseObserver).onNext(any(AppendEntriesResponse.class));
        verify(appendEntriesResponseObserver).onCompleted();
        verify(raftNode).handleAppendEntries(request);
    }

    @Test
    void testAppendEntriesFailed() {
        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
            .setTerm(1)
            .setLeaderId("node1")
            .setPrevLogIndex(5)  // Log inconsistency
            .setPrevLogTerm(3)
            .setLeaderCommit(0)
            .build();

        AppendEntriesResponse mockResponse = AppendEntriesResponse.newBuilder()
            .setTerm(1)
            .setSuccess(false)
            .build();

        when(raftNode.handleAppendEntries(any(AppendEntriesRequest.class))).thenReturn(mockResponse);

        raftGrpcService.appendEntries(request, appendEntriesResponseObserver);

        ArgumentCaptor<AppendEntriesResponse> responseCaptor = ArgumentCaptor.forClass(AppendEntriesResponse.class);
        verify(appendEntriesResponseObserver).onNext(responseCaptor.capture());
        verify(appendEntriesResponseObserver).onCompleted();

        AppendEntriesResponse capturedResponse = responseCaptor.getValue();
        assertFalse(capturedResponse.getSuccess());
    }

    @Test
    void testMultipleVoteRequests() {
        VoteRequest request1 = VoteRequest.newBuilder()
            .setTerm(2)
            .setCandidateId("node2")
            .setLastLogIndex(0)
            .setLastLogTerm(0)
            .build();

        VoteRequest request2 = VoteRequest.newBuilder()
            .setTerm(2)
            .setCandidateId("node3")
            .setLastLogIndex(0)
            .setLastLogTerm(0)
            .build();

        VoteResponse response1 = VoteResponse.newBuilder()
            .setTerm(2)
            .setVoteGranted(true)
            .build();

        VoteResponse response2 = VoteResponse.newBuilder()
            .setTerm(2)
            .setVoteGranted(false)
            .build();

        when(raftNode.handleVoteRequest(request1)).thenReturn(response1);
        when(raftNode.handleVoteRequest(request2)).thenReturn(response2);

        raftGrpcService.requestVote(request1, voteResponseObserver);
        raftGrpcService.requestVote(request2, voteResponseObserver);

        verify(voteResponseObserver, times(2)).onNext(any(VoteResponse.class));
        verify(voteResponseObserver, times(2)).onCompleted();
    }

    @Test
    void testHeartbeat() {
        // Heartbeat is empty AppendEntries
        AppendEntriesRequest heartbeat = AppendEntriesRequest.newBuilder()
            .setTerm(1)
            .setLeaderId("node1")
            .setPrevLogIndex(-1)
            .setPrevLogTerm(0)
            .setLeaderCommit(0)
            .build();

        AppendEntriesResponse mockResponse = AppendEntriesResponse.newBuilder()
            .setTerm(1)
            .setSuccess(true)
            .build();

        when(raftNode.handleAppendEntries(any(AppendEntriesRequest.class))).thenReturn(mockResponse);

        raftGrpcService.appendEntries(heartbeat, appendEntriesResponseObserver);

        verify(appendEntriesResponseObserver).onNext(any(AppendEntriesResponse.class));
        verify(appendEntriesResponseObserver).onCompleted();
        
        // Verify heartbeat was processed
        verify(raftNode).handleAppendEntries(heartbeat);
    }

    @Test
    void testRequestVoteWithHigherTerm() {
        VoteRequest request = VoteRequest.newBuilder()
            .setTerm(10)
            .setCandidateId("node4")
            .setLastLogIndex(5)
            .setLastLogTerm(9)
            .build();

        VoteResponse mockResponse = VoteResponse.newBuilder()
            .setTerm(10)
            .setVoteGranted(true)
            .build();

        when(raftNode.handleVoteRequest(any(VoteRequest.class))).thenReturn(mockResponse);

        raftGrpcService.requestVote(request, voteResponseObserver);

        ArgumentCaptor<VoteResponse> responseCaptor = ArgumentCaptor.forClass(VoteResponse.class);
        verify(voteResponseObserver).onNext(responseCaptor.capture());

        VoteResponse response = responseCaptor.getValue();
        assertEquals(10, response.getTerm());
    }

    @Test
    void testAppendEntriesWithMultipleEntries() {
        GrpcLogEntry entry1 = GrpcLogEntry.newBuilder().setTerm(1).setCommand("CMD1").build();
        GrpcLogEntry entry2 = GrpcLogEntry.newBuilder().setTerm(1).setCommand("CMD2").build();
        GrpcLogEntry entry3 = GrpcLogEntry.newBuilder().setTerm(1).setCommand("CMD3").build();

        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
            .setTerm(1)
            .setLeaderId("node1")
            .setPrevLogIndex(-1)
            .setPrevLogTerm(0)
            .addEntries(entry1)
            .addEntries(entry2)
            .addEntries(entry3)
            .setLeaderCommit(2)
            .build();

        AppendEntriesResponse mockResponse = AppendEntriesResponse.newBuilder()
            .setTerm(1)
            .setSuccess(true)
            .build();

        when(raftNode.handleAppendEntries(any(AppendEntriesRequest.class))).thenReturn(mockResponse);

        raftGrpcService.appendEntries(request, appendEntriesResponseObserver);

        verify(raftNode).handleAppendEntries(request);
        verify(appendEntriesResponseObserver).onNext(any(AppendEntriesResponse.class));
        verify(appendEntriesResponseObserver).onCompleted();
    }
}
