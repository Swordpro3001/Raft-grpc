package com.example.raftimplementation.grpc;

import com.example.raftimplementation.config.RaftConfig;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;

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
        server = ServerBuilder
            .forPort(config.getGrpcPort())
            .addService(raftGrpcService)
            .build()
            .start();
        
        log.info("gRPC Server started on port: {}", config.getGrpcPort());
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down gRPC server");
            GrpcServerStarter.this.stop();
        }));
    }
    
    @PreDestroy
    public void stop() {
        if (server != null) {
            server.shutdown();
            log.info("gRPC Server stopped");
        }
    }
}
