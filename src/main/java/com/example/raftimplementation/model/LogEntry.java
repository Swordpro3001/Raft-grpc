package com.example.raftimplementation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogEntry {
    private int term;
    private String command;
    private ClusterConfiguration configuration;
    
    public LogEntry(int term, String command) {
        this.term = term;
        this.command = command;
        this.configuration = null;
    }
    
    /**
     * Returns true if this is a configuration change entry.
     */
    public boolean isConfigurationEntry() {
        return configuration != null;
    }
}
