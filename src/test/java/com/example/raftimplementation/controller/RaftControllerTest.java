package com.example.raftimplementation.controller;

import com.example.raftimplementation.model.RaftEvent;
import com.example.raftimplementation.service.RaftNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RaftController.
 * Tests REST API endpoints for Raft node operations.
 */
@ExtendWith(MockitoExtension.class)
class RaftControllerTest {

    @Mock
    private RaftNode raftNode;

    @InjectMocks
    private RaftController raftController;

    private Map<String, Object> mockStatus;
    private List<RaftEvent> mockEvents;

    @BeforeEach
    void setUp() {
        mockStatus = new HashMap<>();
        mockStatus.put("nodeId", "node1");
        mockStatus.put("state", "FOLLOWER");
        mockStatus.put("currentTerm", 0);
        mockStatus.put("commitIndex", 0);
        mockStatus.put("lastApplied", 0);
        mockStatus.put("log", Collections.emptyList());
        mockStatus.put("stateMachine", Collections.emptyList());

        mockEvents = new ArrayList<>();
        mockEvents.add(new RaftEvent(
            java.time.LocalDateTime.now(),
            RaftEvent.EventType.STATE_CHANGE,
            "Node became FOLLOWER",
            0,
            "node1"
        ));
    }

    @Test
    void testGetStatus() {
        when(raftNode.getStatus()).thenReturn(mockStatus);

        ResponseEntity<Map<String, Object>> response = raftController.getStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("node1", response.getBody().get("nodeId"));
        assertEquals("FOLLOWER", response.getBody().get("state"));
        
        verify(raftNode, times(1)).getStatus();
    }

    @Test
    void testSubmitCommand_Success() {
        Map<String, String> request = new HashMap<>();
        request.put("command", "SET x=1");

        when(raftNode.submitCommand("SET x=1")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = raftController.submitCommand(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        
        verify(raftNode, times(1)).submitCommand("SET x=1");
    }

    @Test
    void testSubmitCommand_Failure() {
        Map<String, String> request = new HashMap<>();
        request.put("command", "SET x=1");

        when(raftNode.submitCommand("SET x=1")).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = raftController.submitCommand(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse((Boolean) response.getBody().get("success"));
        assertEquals("Not leader or failed to replicate", response.getBody().get("message"));
        
        verify(raftNode, times(1)).submitCommand("SET x=1");
    }

    @Test
    void testSubmitCommand_MissingCommand() {
        Map<String, String> request = new HashMap<>();
        // No command provided

        ResponseEntity<Map<String, Object>> response = raftController.submitCommand(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse((Boolean) response.getBody().get("success"));
        assertEquals("Command is required", response.getBody().get("message"));
        
        verify(raftNode, never()).submitCommand(anyString());
    }

    @Test
    void testSubmitCommand_EmptyCommand() {
        Map<String, String> request = new HashMap<>();
        request.put("command", "");

        ResponseEntity<Map<String, Object>> response = raftController.submitCommand(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse((Boolean) response.getBody().get("success"));
        
        verify(raftNode, never()).submitCommand(anyString());
    }

    @Test
    void testGetEvents() {
        when(raftNode.getEvents()).thenReturn(mockEvents);

        ResponseEntity<List<RaftEvent>> response = raftController.getEvents();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(RaftEvent.EventType.STATE_CHANGE, response.getBody().get(0).getType());
        
        verify(raftNode, times(1)).getEvents();
    }

    @Test
    void testGetEvents_EmptyList() {
        when(raftNode.getEvents()).thenReturn(Collections.emptyList());

        ResponseEntity<List<RaftEvent>> response = raftController.getEvents();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
        
        verify(raftNode, times(1)).getEvents();
    }

    @Test
    void testSubmitCommand_Exception() {
        Map<String, String> request = new HashMap<>();
        request.put("command", "SET x=1");

        when(raftNode.submitCommand("SET x=1")).thenThrow(new RuntimeException("Internal error"));

        ResponseEntity<Map<String, Object>> response = raftController.submitCommand(request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse((Boolean) response.getBody().get("success"));
        assertTrue(response.getBody().get("message").toString().contains("error"));
    }

    @Test
    void testGetStatus_Exception() {
        when(raftNode.getStatus()).thenThrow(new RuntimeException("Node failure"));

        ResponseEntity<Map<String, Object>> response = raftController.getStatus();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    void testMultipleCommands() {
        Map<String, String> request1 = new HashMap<>();
        request1.put("command", "SET x=1");
        
        Map<String, String> request2 = new HashMap<>();
        request2.put("command", "SET y=2");

        when(raftNode.submitCommand(anyString())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response1 = raftController.submitCommand(request1);
        ResponseEntity<Map<String, Object>> response2 = raftController.submitCommand(request2);

        assertEquals(HttpStatus.OK, response1.getStatusCode());
        assertEquals(HttpStatus.OK, response2.getStatusCode());
        
        verify(raftNode, times(2)).submitCommand(anyString());
    }
}
