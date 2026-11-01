package com.example.raftimplementation.config;

import com.example.raftimplementation.model.ServerInfo;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "raft")
@Data
public class RaftConfig {
    private static final int FIRST_HTTP_PORT = 8080;
    private static final int FIRST_GRPC_PORT = 9090;

    private String nodeId;
    private String bindAddress = "127.0.0.1";
    private int grpcPort;
    private int httpPort;

    /** Initial cluster size. Ignored when peers are listed explicitly. */
    private int clusterSize = 5;
    private List<PeerConfig> peers = new ArrayList<>();

    /** Start without a configuration and wait for the leader to add this node. */
    private boolean join = false;

    private int electionTimeoutMinMs = 1500;
    private int electionTimeoutMaxMs = 3000;
    private int heartbeatIntervalMs = 500;
    private int rpcTimeoutMs = 1000;
    private int snapshotRpcTimeoutMs = 5000;
    private int commandTimeoutMs = 5000;
    private int snapshotThreshold = 100;

    /**
     * The servers of the initial configuration, including this node. Unless peers are
     * listed explicitly they follow the local convention: nodeN uses 8080+N and 9090+N.
     */
    public Map<String, ServerInfo> initialServers() {
        Map<String, ServerInfo> servers = new LinkedHashMap<>();
        if (peers.isEmpty()) {
            for (int number = 1; number <= clusterSize; number++) {
                servers.put("node" + number, conventionalAddress(number));
            }
        }
        for (PeerConfig peer : peers) {
            servers.put(peer.getNodeId(),
                new ServerInfo(peer.getNodeId(), peer.getHost(), peer.getGrpcPort(), peer.getHttpPort()));
        }
        ServerInfo listed = servers.get(nodeId);
        String ownHost = listed != null ? listed.getHost() : "localhost";
        servers.put(nodeId, new ServerInfo(nodeId, ownHost, grpcPort, httpPort));
        return servers;
    }

    public static ServerInfo conventionalAddress(int nodeNumber) {
        return new ServerInfo("node" + nodeNumber, "localhost",
            FIRST_GRPC_PORT + nodeNumber, FIRST_HTTP_PORT + nodeNumber);
    }

    @Data
    public static class PeerConfig {
        private String nodeId;
        private String host;
        private int grpcPort;
        private int httpPort;
    }
}
