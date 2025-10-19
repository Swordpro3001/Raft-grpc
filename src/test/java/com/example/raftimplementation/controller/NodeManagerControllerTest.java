package com.example.raftimplementation.controller;

import com.example.raftimplementation.service.NodeManagerService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NodeManagerController.
 * Tests REST API endpoints for node lifecycle management.
 */
@ExtendWith(MockitoExtension.class)
class NodeManagerControllerTest {

    @Mock
    private NodeManagerService nodeManagerService;

    @InjectMocks
    private NodeManagerController nodeManagerController;

    @BeforeEach
    void setUp() {
        // Setup is done via @Mock and @InjectMocks
    }

    @Test
    void testStartNode_Success() {
        when(nodeManagerService.startNode("node1")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = nodeManagerController.startNode("node1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals("Node node1 started successfully", response.getBody().get("message"));
        
        verify(nodeManagerService, times(1)).startNode("node1");
    }

    @Test
    void testStartNode_Failure() {
        when(nodeManagerService.startNode("node1")).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = nodeManagerController.startNode("node1");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse((Boolean) response.getBody().get("success"));
        assertEquals("Failed to start node node1", response.getBody().get("message"));
        
        verify(nodeManagerService, times(1)).startNode("node1");
    }

    @Test
    void testStartNode_Exception() {
        when(nodeManagerService.startNode("node1")).thenThrow(new RuntimeException("Process error"));

        ResponseEntity<Map<String, Object>> response = nodeManagerController.startNode("node1");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse((Boolean) response.getBody().get("success"));
        assertTrue(response.getBody().get("message").toString().contains("error"));
    }

    @Test
    void testStopNode_Success() {
        when(nodeManagerService.stopNode("node2")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = nodeManagerController.stopNode("node2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals("Node node2 stopped successfully", response.getBody().get("message"));
        
        verify(nodeManagerService, times(1)).stopNode("node2");
    }

    @Test
    void testStopNode_Failure() {
        when(nodeManagerService.stopNode("node2")).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = nodeManagerController.stopNode("node2");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse((Boolean) response.getBody().get("success"));
        assertEquals("Failed to stop node node2", response.getBody().get("message"));
        
        verify(nodeManagerService, times(1)).stopNode("node2");
    }

    @Test
    void testGetNodeStatus() {
        Map<String, Boolean> mockStatus = new HashMap<>();
        mockStatus.put("node1", true);
        mockStatus.put("node2", false);
        mockStatus.put("node3", true);
        mockStatus.put("node4", true);
        mockStatus.put("node5", false);

        when(nodeManagerService.getAllNodesStatus()).thenReturn(mockStatus);

        ResponseEntity<Map<String, Boolean>> response = nodeManagerController.getAllNodesStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(5, response.getBody().size());
        assertTrue(response.getBody().get("node1"));
        assertFalse(response.getBody().get("node2"));
        
        verify(nodeManagerService, times(1)).getAllNodesStatus();
    }

    @Test
    void testGetNodeLogs_Success() {
        List<String> mockLogs = Arrays.asList("Log line 1", "Log line 2", "Log line 3");
        
        when(nodeManagerService.getNodeLogs("node3")).thenReturn(mockLogs);
        when(nodeManagerService.isNodeRunning("node3")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = nodeManagerController.getNodeLogs("node3");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(mockLogs, response.getBody().get("logs"));
        assertTrue((Boolean) response.getBody().get("running"));
        
        verify(nodeManagerService, times(1)).getNodeLogs("node3");
    }

    @Test
    void testGetNodeLogs_NoLogs() {
        List<String> emptyLogs = Collections.emptyList();
        
        when(nodeManagerService.getNodeLogs("node3")).thenReturn(emptyLogs);
        when(nodeManagerService.isNodeRunning("node3")).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = nodeManagerController.getNodeLogs("node3");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(emptyLogs, response.getBody().get("logs"));
        assertFalse((Boolean) response.getBody().get("running"));
    }

    @Test
    void testGetNodeLogs_Exception() {
        when(nodeManagerService.getNodeLogs("node3")).thenThrow(new RuntimeException("File not found"));

        assertThrows(RuntimeException.class, () -> {
            nodeManagerController.getNodeLogs("node3");
        });
    }

    @Test
    void testStartAllNodes() {
        when(nodeManagerService.startNode(anyString())).thenReturn(true);

        // Start all 5 nodes
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<Map<String, Object>> response = nodeManagerController.startNode("node" + i);
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        verify(nodeManagerService, times(5)).startNode(anyString());
    }

    @Test
    void testStopAndStartCycle() {
        when(nodeManagerService.stopNode("node1")).thenReturn(true);
        when(nodeManagerService.startNode("node1")).thenReturn(true);

        ResponseEntity<Map<String, Object>> stopResponse = nodeManagerController.stopNode("node1");
        ResponseEntity<Map<String, Object>> startResponse = nodeManagerController.startNode("node1");

        assertEquals(HttpStatus.OK, stopResponse.getStatusCode());
        assertEquals(HttpStatus.OK, startResponse.getStatusCode());
        
        verify(nodeManagerService, times(1)).stopNode("node1");
        verify(nodeManagerService, times(1)).startNode("node1");
    }

    @Test
    void testInvalidNodeId() {
        when(nodeManagerService.startNode("node99")).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = nodeManagerController.startNode("node99");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
    }
}
