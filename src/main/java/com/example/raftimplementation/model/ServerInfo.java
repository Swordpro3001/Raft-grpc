package com.example.raftimplementation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Information about a server in the cluster.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerInfo {
    private String nodeId;
    private String host;
    private int grpcPort;
    private int httpPort;
    
    @Override
    public String toString() {
        return String.format("%s@%s:%d", nodeId, host, grpcPort);
    }
}
