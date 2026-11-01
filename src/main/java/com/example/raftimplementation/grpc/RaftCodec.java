package com.example.raftimplementation.grpc;

import com.example.raftimplementation.model.ClusterConfiguration;
import com.example.raftimplementation.model.LogEntry;
import com.example.raftimplementation.model.ServerInfo;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts between the internal model and its protobuf form, which is used
 * both on the wire and for the binary columns in the database.
 */
public final class RaftCodec {

    private RaftCodec() {
    }

    public static GrpcLogEntry toGrpc(LogEntry entry) {
        GrpcLogEntry.Builder builder = GrpcLogEntry.newBuilder().setTerm(entry.getTerm());
        if (entry.getCommand() != null) {
            builder.setCommand(entry.getCommand());
        }
        if (entry.isConfigurationEntry()) {
            builder.setConfiguration(toGrpc(entry.getConfiguration()));
        }
        return builder.build();
    }

    public static LogEntry fromGrpc(GrpcLogEntry entry) {
        if (entry.hasConfiguration()) {
            return new LogEntry(entry.getTerm(), null, fromGrpc(entry.getConfiguration()));
        }
        String command = entry.getCommand();
        return new LogEntry(entry.getTerm(), command.isEmpty() ? null : command);
    }

    public static ConfigurationEntry toGrpc(ClusterConfiguration config) {
        ConfigurationEntry.Builder builder = ConfigurationEntry.newBuilder().setIsJoint(config.isJoint());
        for (String nodeId : config.getOldServers()) {
            builder.addOldServers(toServerEntry(nodeId, config.getAddresses()));
        }
        for (String nodeId : config.getNewServers()) {
            builder.addNewServers(toServerEntry(nodeId, config.getAddresses()));
        }
        return builder.build();
    }

    public static ClusterConfiguration fromGrpc(ConfigurationEntry entry) {
        Map<String, ServerInfo> addresses = new HashMap<>();
        Set<String> oldServers = collectServers(entry.getOldServersList(), addresses);
        Set<String> newServers = collectServers(entry.getNewServersList(), addresses);
        return entry.getIsJoint()
            ? ClusterConfiguration.createJoint(oldServers, newServers, addresses)
            : ClusterConfiguration.createNew(newServers, addresses);
    }

    public static byte[] encodeConfiguration(ClusterConfiguration config) {
        return config == null ? null : toGrpc(config).toByteArray();
    }

    public static ClusterConfiguration decodeConfiguration(byte[] data) throws InvalidProtocolBufferException {
        return data == null ? null : fromGrpc(ConfigurationEntry.parseFrom(data));
    }

    public static byte[] encodeSnapshot(List<String> stateMachine, ClusterConfiguration config) {
        SnapshotData.Builder builder = SnapshotData.newBuilder().addAllStateMachine(stateMachine);
        if (config != null) {
            builder.setConfiguration(toGrpc(config));
        }
        return builder.build().toByteArray();
    }

    public static List<String> decodeSnapshotState(SnapshotData data) {
        return new ArrayList<>(data.getStateMachineList());
    }

    public static ClusterConfiguration decodeSnapshotConfiguration(SnapshotData data) {
        return data.hasConfiguration() ? fromGrpc(data.getConfiguration()) : null;
    }

    private static ServerEntry toServerEntry(String nodeId, Map<String, ServerInfo> addresses) {
        ServerEntry.Builder builder = ServerEntry.newBuilder().setNodeId(nodeId);
        ServerInfo address = addresses.get(nodeId);
        if (address != null) {
            builder.setHost(address.getHost())
                .setGrpcPort(address.getGrpcPort())
                .setHttpPort(address.getHttpPort());
        }
        return builder.build();
    }

    private static Set<String> collectServers(List<ServerEntry> entries, Map<String, ServerInfo> addresses) {
        Set<String> servers = new HashSet<>();
        for (ServerEntry entry : entries) {
            servers.add(entry.getNodeId());
            if (!entry.getHost().isEmpty()) {
                addresses.put(entry.getNodeId(),
                    new ServerInfo(entry.getNodeId(), entry.getHost(), entry.getGrpcPort(), entry.getHttpPort()));
            }
        }
        return servers;
    }
}
