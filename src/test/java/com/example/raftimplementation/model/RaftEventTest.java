package com.example.raftimplementation.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RaftEvent model.
 * Tests event creation, types, and formatting.
 */
class RaftEventTest {

    @Test
    void testEventCreation() {
        LocalDateTime now = LocalDateTime.now();
        RaftEvent event = new RaftEvent(
            now,
            RaftEvent.EventType.STATE_CHANGE,
            "Node became LEADER",
            1,
            "node1"
        );

        assertEquals(now, event.getTimestamp());
        assertEquals(RaftEvent.EventType.STATE_CHANGE, event.getType());
        assertEquals("Node became LEADER", event.getDescription());
        assertEquals(1, event.getTerm());
        assertEquals("node1", event.getNodeId());
    }

    @Test
    void testAllEventTypes() {
        // Verify all event types exist
        assertNotNull(RaftEvent.EventType.STATE_CHANGE);
        assertNotNull(RaftEvent.EventType.ELECTION_START);
        assertNotNull(RaftEvent.EventType.VOTE_GRANTED);
        assertNotNull(RaftEvent.EventType.VOTE_DENIED);
        assertNotNull(RaftEvent.EventType.ELECTION_WON);
        assertNotNull(RaftEvent.EventType.ELECTION_LOST);
        assertNotNull(RaftEvent.EventType.HEARTBEAT_RECEIVED);
        assertNotNull(RaftEvent.EventType.TERM_INCREASED);
        assertNotNull(RaftEvent.EventType.LOG_REPLICATED);
        assertNotNull(RaftEvent.EventType.COMMAND_RECEIVED);
    }

    @Test
    void testToLogString() {
        LocalDateTime now = LocalDateTime.now();
        RaftEvent event = new RaftEvent(
            now,
            RaftEvent.EventType.VOTE_GRANTED,
            "Granted vote to node2",
            2,
            "node1"
        );

        String logString = event.toLogString();
        
        assertNotNull(logString);
        assertTrue(logString.contains("VOTE_GRANTED"));
        assertTrue(logString.contains("Granted vote to node2"));
        assertTrue(logString.contains("term=2"));
        assertTrue(logString.contains("node=node1"));
    }

    @Test
    void testEventWithZeroTerm() {
        RaftEvent event = new RaftEvent(
            LocalDateTime.now(),
            RaftEvent.EventType.HEARTBEAT_RECEIVED,
            "Received heartbeat",
            0,
            "node2"
        );

        assertEquals(0, event.getTerm());
        assertEquals("node2", event.getNodeId());
    }

    @Test
    void testMultipleEventsWithSameType() {
        LocalDateTime now = LocalDateTime.now();
        
        RaftEvent event1 = new RaftEvent(
            now,
            RaftEvent.EventType.LOG_REPLICATED,
            "Replicated entry 1",
            1,
            "node1"
        );
        
        RaftEvent event2 = new RaftEvent(
            now.plusSeconds(1),
            RaftEvent.EventType.LOG_REPLICATED,
            "Replicated entry 2",
            1,
            "node1"
        );

        assertEquals(event1.getType(), event2.getType());
        assertNotEquals(event1.getDescription(), event2.getDescription());
    }

    @Test
    void testEventTimestampOrdering() {
        LocalDateTime t1 = LocalDateTime.now();
        LocalDateTime t2 = t1.plusSeconds(1);
        LocalDateTime t3 = t2.plusSeconds(1);

        RaftEvent event1 = new RaftEvent(t1, RaftEvent.EventType.ELECTION_START, "Start", 1, "node1");
        RaftEvent event2 = new RaftEvent(t2, RaftEvent.EventType.VOTE_GRANTED, "Vote", 1, "node1");
        RaftEvent event3 = new RaftEvent(t3, RaftEvent.EventType.ELECTION_WON, "Won", 1, "node1");

        assertTrue(event1.getTimestamp().isBefore(event2.getTimestamp()));
        assertTrue(event2.getTimestamp().isBefore(event3.getTimestamp()));
    }

    @Test
    void testEventTypeEnum() {
        // Test that enum values work correctly
        RaftEvent.EventType type1 = RaftEvent.EventType.STATE_CHANGE;
        RaftEvent.EventType type2 = RaftEvent.EventType.STATE_CHANGE;
        RaftEvent.EventType type3 = RaftEvent.EventType.ELECTION_START;

        assertEquals(type1, type2);
        assertNotEquals(type1, type3);
    }

    @Test
    void testEventDescriptionContent() {
        RaftEvent event = new RaftEvent(
            LocalDateTime.now(),
            RaftEvent.EventType.COMMAND_RECEIVED,
            "Received command: SET x=1",
            3,
            "node3"
        );

        assertTrue(event.getDescription().contains("SET x=1"));
        assertTrue(event.getDescription().contains("Received command"));
    }
}
