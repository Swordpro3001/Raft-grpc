package com.example.raftimplementation.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The dashboard process's view of the cluster: talks to the nodes over REST and manages their
 * processes through the supervisor. Used by both the dashboard pages and the JSON API.
 * Operations return the node's answer, with at least "success" and "message".
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "false")
@Slf4j
public class ClusterClient {

    /** Set in "result" when no node could handle the request. */
    public static final String NO_LEADER = "NO_LEADER";
    /** Set in "result" when the request itself was wrong. */
    public static final String INVALID = "INVALID";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST = new ParameterizedTypeReference<>() { };
    private static final int EVENTS_PER_NODE = 30;
    private static final int LOG_ENTRIES_PER_NODE = 30;

    private final RestTemplate restTemplate;
    private final NodeManagerService nodeManager;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Status, events and log of all nodes, collected in parallel.
     * Nodes that cannot be reached are simply reported as offline.
     */
    public Map<String, Object> getOverview() {
        List<CompletableFuture<Map<String, Object>>> futures = nodeManager.getKnownNodes().stream()
            .map(nodeId -> CompletableFuture.supplyAsync(() -> fetchNode(nodeId), executor))
            .toList();
        List<Map<String, Object>> nodes = futures.stream().map(CompletableFuture::join).toList();

        Map<String, Object> overview = new HashMap<>();
        overview.put("nodes", nodes);
        nodes.stream()
            .filter(node -> "LEADER".equals(node.get("state")))
            .max(Comparator.comparingInt(node -> (Integer) node.get("currentTerm")))
            .ifPresent(leader -> {
                String nodeId = (String) leader.get("nodeId");
                overview.put("leader", nodeId);
                overview.put("performance", getObject(nodeId, "/api/metrics/performance"));
                overview.put("snapshots", getObject(nodeId, "/api/metrics/snapshots"));
                overview.put("replication", getObject(nodeId, "/api/metrics/replication"));
            });
        return overview;
    }

    private Map<String, Object> fetchNode(String nodeId) {
        Map<String, Object> node = new HashMap<>();
        Map<String, Object> status = getObject(nodeId, "/api/status");
        if (status == null) {
            node.put("nodeId", nodeId);
            node.put("state", "OFFLINE");
            node.put("events", List.of());
        } else {
            node.putAll(status);
            node.put("events", getLatestEvents(nodeId));
            node.put("log", getObject(nodeId, "/api/log?limit=" + LOG_ENTRIES_PER_NODE));
        }
        node.put("managed", nodeManager.isRunning(nodeId));
        return node;
    }

    private List<Map<String, Object>> getLatestEvents(String nodeId) {
        try {
            List<Map<String, Object>> events = restTemplate.exchange(
                nodeManager.baseUrl(nodeId) + "/api/events", HttpMethod.GET, null, JSON_LIST).getBody();
            return events == null ? List.of() : events.subList(Math.max(0, events.size() - EVENTS_PER_NODE), events.size());
        } catch (Exception e) {
            log.debug("Could not read events of {}: {}", nodeId, e.getMessage());
            return List.of();
        }
    }

    // ==================== Commands and Reads ====================

    public Map<String, Object> submitCommand(String command) {
        if (command == null || command.isBlank()) {
            return failure(INVALID, "Command is required");
        }
        return onLeader(leader -> post(leader, "/api/command", Map.of("command", command)));
    }

    /**
     * Linearizable read: the leader confirms with a quorum that it is still the leader.
     */
    public Map<String, Object> read(Integer index) {
        return onLeader(leader -> {
            Map<String, Object> result = getObject(leader, "/api/read" + (index != null ? "?index=" + index : ""));
            return result != null ? result : noLeader();
        });
    }

    public Map<String, Object> createSnapshot() {
        return onLeader(leader -> {
            Map<String, Object> result = new HashMap<>(post(leader, "/api/snapshot/create", null));
            result.putIfAbsent("message", "Snapshot created on " + leader);
            return result;
        });
    }

    // ==================== Node Lifecycle ====================

    public Map<String, Object> startNode(String nodeId) {
        return lifecycle(nodeId + " is starting", () -> nodeManager.startNode(nodeId));
    }

    public Map<String, Object> stopNode(String nodeId) {
        return lifecycle(nodeId + " stopped", () -> nodeManager.stopNode(nodeId));
    }

    public Map<String, Object> suspendNode(String nodeId) {
        return validated(nodeId, () -> post(nodeId, "/api/suspend", null));
    }

    public Map<String, Object> resumeNode(String nodeId) {
        return validated(nodeId, () -> post(nodeId, "/api/resume", null));
    }

    private Map<String, Object> lifecycle(String successMessage, Runnable action) {
        try {
            action.run();
            return Map.of("success", true, "message", successMessage);
        } catch (IllegalArgumentException e) {
            return failure(INVALID, e.getMessage());
        } catch (IllegalStateException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    private Map<String, Object> validated(String nodeId, Supplier<Map<String, Object>> action) {
        try {
            nodeManager.addressOf(nodeId);
        } catch (IllegalArgumentException e) {
            return failure(INVALID, e.getMessage());
        }
        return action.get();
    }

    // ==================== Membership ====================

    /**
     * Starts a new node, then asks the leader to add it to the cluster. The node
     * waits as a non-member until the leader's configuration entry reaches it.
     */
    public Map<String, Object> addNode() {
        return onLeader(leader -> {
            String nodeId = nodeManager.nextNodeId();
            try {
                nodeManager.startNode(nodeId);
            } catch (IllegalStateException e) {
                return Map.of("success", false, "message", e.getMessage());
            }
            return post(leader, "/api/membership/add", nodeManager.addressOf(nodeId));
        });
    }

    /**
     * Asks the leader to remove a node from the cluster. Its process keeps running as a non-member.
     */
    public Map<String, Object> removeNode(String nodeId) {
        return validated(nodeId, () -> onLeader(leader -> post(leader, "/api/membership/remove/" + nodeId, null)));
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> onLeader(java.util.function.Function<String, Map<String, Object>> action) {
        String leader = findLeader();
        return leader != null ? action.apply(leader) : noLeader();
    }

    private String findLeader() {
        List<CompletableFuture<Map<String, Object>>> futures = nodeManager.getKnownNodes().stream()
            .map(nodeId -> CompletableFuture.supplyAsync(() -> getObject(nodeId, "/api/status"), executor))
            .toList();
        return futures.stream()
            .map(CompletableFuture::join)
            .filter(status -> status != null && "LEADER".equals(status.get("state")))
            .max(Comparator.comparingInt(status -> (Integer) status.get("currentTerm")))
            .map(status -> (String) status.get("nodeId"))
            .orElse(null);
    }

    private static Map<String, Object> noLeader() {
        return failure(NO_LEADER, "No leader available. An election may be in progress.");
    }

    private static Map<String, Object> failure(String result, String message) {
        return Map.of("success", false, "result", result, "message", message);
    }

    private Map<String, Object> getObject(String nodeId, String path) {
        try {
            return restTemplate.exchange(nodeManager.baseUrl(nodeId) + path, HttpMethod.GET, null, JSON_OBJECT).getBody();
        } catch (Exception e) {
            log.debug("GET {} on {} failed: {}", path, nodeId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String nodeId, String path, Object body) {
        try {
            Map<String, Object> response = restTemplate.postForObject(nodeManager.baseUrl(nodeId) + path, body, Map.class);
            return response != null ? response : Map.of("success", false, "message", "Empty response");
        } catch (Exception e) {
            log.warn("POST {} on {} failed: {}", path, nodeId, e.getMessage());
            return Map.of("success", false, "message", nodeId + " is not reachable");
        }
    }
}
