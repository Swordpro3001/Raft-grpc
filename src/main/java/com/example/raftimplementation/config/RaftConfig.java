package com.example.raftimplementation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "raft")
@Data
public class RaftConfig {
    private String nodeId;
    private int grpcPort;
    private int httpPort;
    private List<PeerConfig> peers;
    
    @Data
    public static class PeerConfig {
        private String nodeId;
        private String host;
        private int grpcPort;
    }
}
