package com.example.raftimplementation.grpc;

import com.example.raftimplementation.service.RaftNode;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RaftGrpcService extends RaftServiceGrpc.RaftServiceImplBase {
    
    private final RaftNode raftNode;
    
    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        try {
            VoteResponse response = raftNode.handleVoteRequest(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error handling vote request", e);
            responseObserver.onError(e);
        }
    }
    
    @Override
    public void appendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
        try {
            AppendEntriesResponse response = raftNode.handleAppendEntries(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error handling append entries", e);
            responseObserver.onError(e);
        }
    }
    
    @Override
    public void installSnapshot(InstallSnapshotRequest request, StreamObserver<InstallSnapshotResponse> responseObserver) {
        try {
            InstallSnapshotResponse response = raftNode.handleInstallSnapshot(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error handling install snapshot", e);
            responseObserver.onError(e);
        }
    }
    
    @Override
    public void preVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        try {
            VoteResponse response = raftNode.handlePreVote(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error handling pre-vote", e);
            responseObserver.onError(e);
        }
    }
}