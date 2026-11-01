package com.example.raftimplementation.grpc;

import com.example.raftimplementation.config.RaftConfig;
import com.example.raftimplementation.service.RaftNode;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetSocketAddress;

@Component
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class GrpcServerStarter {

    private final RaftConfig config;
    private final RaftGrpcService raftGrpcService;
    private Server server;

    @PostConstruct
    public void start() throws IOException {
        server = NettyServerBuilder
            .forAddress(new InetSocketAddress(config.getBindAddress(), config.getGrpcPort()))
            .maxInboundMessageSize(RaftNode.MAX_GRPC_MESSAGE_BYTES)
            .addService(raftGrpcService)
            .build()
            .start();

        log.info("gRPC Server started on {}:{}", config.getBindAddress(), config.getGrpcPort());
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.shutdownNow();
            log.info("gRPC Server stopped");
        }
    }
}
