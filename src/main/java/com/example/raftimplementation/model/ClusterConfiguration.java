package com.example.raftimplementation.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Represents a cluster configuration for Raft membership changes.
 * According to the Raft paper, we use joint consensus (C_old,new) during transitions:
 * while joint, every decision needs a majority of the old AND of the new servers.
 */
@Data
@NoArgsConstructor
public class ClusterConfiguration {

    /**
     * The set of server IDs in the old configuration.
     * During normal operation, this equals newServers.
     */
    private Set<String> oldServers = new HashSet<>();

    /**
     * The set of server IDs in the new configuration.
     * During normal operation, this equals oldServers.
     */
    private Set<String> newServers = new HashSet<>();

    /**
     * True if this is a joint consensus configuration (transitioning).
     */
    private boolean isJoint = false;

    /**
     * How to reach each server. Travels with the configuration so that every
     * node, not only the leader that handled the change, can connect to new servers.
     */
    private Map<String, ServerInfo> addresses = new HashMap<>();

    /**
     * Creates a joint consensus configuration.
     */
    public static ClusterConfiguration createJoint(Set<String> oldServers, Set<String> newServers,
                                                   Map<String, ServerInfo> addresses) {
        ClusterConfiguration config = new ClusterConfiguration();
        config.oldServers = new HashSet<>(oldServers);
        config.newServers = new HashSet<>(newServers);
        config.isJoint = true;
        config.addresses = new HashMap<>(addresses);
        return config;
    }

    /**
     * Creates a new (final) configuration after joint consensus.
     */
    public static ClusterConfiguration createNew(Set<String> newServers, Map<String, ServerInfo> addresses) {
        ClusterConfiguration config = new ClusterConfiguration();
        config.oldServers = new HashSet<>(newServers);
        config.newServers = new HashSet<>(newServers);
        config.isJoint = false;
        config.addresses = new HashMap<>(addresses);
        config.addresses.keySet().retainAll(newServers);
        return config;
    }

    /**
     * Returns all servers in the configuration (union of old and new).
     */
    public Set<String> getAllServers() {
        Set<String> all = new HashSet<>(oldServers);
        all.addAll(newServers);
        return all;
    }

    /**
     * Checks if the given server is in the configuration.
     */
    public boolean contains(String serverId) {
        return oldServers.contains(serverId) || newServers.contains(serverId);
    }

    /**
     * Checks whether the given servers form a quorum, as needed to win an election.
     */
    public boolean hasQuorum(Collection<String> servers) {
        return hasMajorityOf(newServers, servers) && (!isJoint || hasMajorityOf(oldServers, servers));
    }

    /**
     * Returns the highest log index stored on a quorum, given each server's match index.
     * Servers outside the configuration do not count, which is how a leader
     * that is removing itself commits C_new without counting its own log.
     */
    public int quorumIndex(Map<String, Integer> matchIndex) {
        int index = majorityIndex(newServers, matchIndex);
        return isJoint ? Math.min(index, majorityIndex(oldServers, matchIndex)) : index;
    }

    private static boolean hasMajorityOf(Set<String> members, Collection<String> servers) {
        long present = servers.stream().filter(members::contains).count();
        return present >= members.size() / 2 + 1;
    }

    private static int majorityIndex(Set<String> members, Map<String, Integer> matchIndex) {
        if (members.isEmpty()) {
            return -1;
        }
        int[] ascending = members.stream()
            .mapToInt(server -> matchIndex.getOrDefault(server, -1))
            .sorted()
            .toArray();
        return ascending[ascending.length - (ascending.length / 2 + 1)];
    }

    @Override
    public String toString() {
        if (isJoint) {
            return "C_old,new{old=" + oldServers + ", new=" + newServers + "}";
        } else {
            return "C{" + newServers + "}";
        }
    }
}
