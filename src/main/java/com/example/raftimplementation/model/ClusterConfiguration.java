package com.example.raftimplementation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents a cluster configuration for Raft membership changes.
 * According to the Raft paper, we use joint consensus (C_old,new) during transitions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClusterConfiguration {
    
    /**
     * The set of server IDs in the old configuration.
     * During normal operation, this equals newServers.
     * During a transition, this contains the old configuration.
     */
    private Set<String> oldServers = new HashSet<>();
    
    /**
     * The set of server IDs in the new configuration.
     * During normal operation, this equals oldServers.
     * During a transition, this contains the new configuration.
     */
    private Set<String> newServers = new HashSet<>();
    
    /**
     * True if this is a joint consensus configuration (transitioning).
     */
    private boolean isJoint = false;
    
    public ClusterConfiguration(Set<String> servers) {
        this.oldServers = new HashSet<>(servers);
        this.newServers = new HashSet<>(servers);
        this.isJoint = false;
    }
    
    /**
     * Creates a joint consensus configuration.
     */
    public static ClusterConfiguration createJoint(Set<String> oldServers, Set<String> newServers) {
        ClusterConfiguration config = new ClusterConfiguration();
        config.oldServers = new HashSet<>(oldServers);
        config.newServers = new HashSet<>(newServers);
        config.isJoint = true;
        return config;
    }
    
    /**
     * Creates a new (final) configuration after joint consensus.
     */
    public static ClusterConfiguration createNew(Set<String> newServers) {
        ClusterConfiguration config = new ClusterConfiguration();
        config.oldServers = new HashSet<>(newServers);
        config.newServers = new HashSet<>(newServers);
        config.isJoint = false;
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
     * Returns the majority count needed in the old configuration.
     */
    public int getOldMajority() {
        return (oldServers.size() / 2) + 1;
    }
    
    /**
     * Returns the majority count needed in the new configuration.
     */
    public int getNewMajority() {
        return (newServers.size() / 2) + 1;
    }
    
    /**
     * Checks if we have a majority in both old and new configurations (for joint consensus).
     */
    public boolean hasMajority(int oldCount, int newCount) {
        if (isJoint) {
            return oldCount >= getOldMajority() && newCount >= getNewMajority();
        } else {
            return newCount >= getNewMajority();
        }
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
