package com.example.raftimplementation.grpc;

import com.example.raftimplementation.service.RaftNode;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.function.Function;

@Service
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RaftGrpcService extends RaftServiceGrpc.RaftServiceImplBase {

    private final RaftNode raftNode;

    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        handle(request, responseObserver, raftNode::handleVoteRequest);
    }

    @Override
    public void appendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
        handle(request, responseObserver, raftNode::handleAppendEntries);
    }

    @Override
    public void installSnapshot(InstallSnapshotRequest request, StreamObserver<InstallSnapshotResponse> responseObserver) {
        handle(request, responseObserver, raftNode::handleInstallSnapshot);
    }

    @Override
    public void preVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        handle(request, responseObserver, raftNode::handlePreVote);
    }

    /**
     * To its peers a suspended node looks like a crashed one: it does not
     * vote, and it cannot help a leader to confirm that it still has a quorum.
     */
    private <Req, Res> void handle(Req request, StreamObserver<Res> responseObserver, Function<Req, Res> handler) {
        if (raftNode.isSuspended()) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("Node is suspended").asRuntimeException());
            return;
        }
        try {
            Res response = handler.apply(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error handling {}", request.getClass().getSimpleName(), e);
            responseObserver.onError(e);
        }
    }
}
