package com.example.raftimplementation.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@AllArgsConstructor
public class RaftEvent {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    
    private LocalDateTime timestamp;
    private EventType type;
    private String description;
    private Integer term;
    private String nodeId;
    
    public enum EventType {
        STATE_CHANGE,      
        ELECTION_START,
        VOTE_GRANTED,
        VOTE_DENIED,
        ELECTION_WON,
        ELECTION_LOST,
        HEARTBEAT_RECEIVED,
        TERM_INCREASED,
        LOG_REPLICATED,
        COMMAND_RECEIVED,
        MEMBERSHIP_CHANGE_START,
        MEMBERSHIP_CHANGE_COMMITTED,
        SERVER_ADDED,
        SERVER_REMOVED,
        SNAPSHOT_CREATED,
        SNAPSHOT_INSTALLED
    }
    
    public String getFormattedTimestamp() {
        return timestamp.format(FORMATTER);
    }
    
    public String toLogString() {
        return String.format("[%s] %s (Term %d): %s", 
            getFormattedTimestamp(), 
            type, 
            term != null ? term : 0, 
            description);
    }
}
