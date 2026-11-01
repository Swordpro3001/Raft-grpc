package com.example.raftimplementation.dashboard;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DashboardViewTest {

    private static Map<String, Object> entry(int index, int term, String type, String command) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("index", index);
        entry.put("term", term);
        entry.put("type", type);
        entry.put("command", command);
        return entry;
    }

    private static Map<String, Object> log(int lastIndex, int commitIndex, int snapshotIndex, List<Map<String, Object>> entries) {
        return Map.of("lastIndex", lastIndex, "commitIndex", commitIndex, "lastApplied", commitIndex,
            "snapshotIndex", snapshotIndex, "entries", entries);
    }

    private static Map<String, Object> node(String id, String state, int term, Map<String, Object> log) {
        Map<String, Object> node = new HashMap<>();
        node.put("nodeId", id);
        node.put("state", state);
        node.put("currentTerm", term);
        node.put("commitIndex", 1);
        node.put("logSize", 2);
        node.put("member", true);
        node.put("members", List.of("node1", "node2", "node3"));
        node.put("events", List.of());
        node.put("stateMachine", List.of("a"));
        node.put("log", log);
        return node;
    }

    private static Map<String, Object> offline(String id, boolean managed) {
        Map<String, Object> node = new HashMap<>();
        node.put("nodeId", id);
        node.put("state", "OFFLINE");
        node.put("managed", managed);
        node.put("events", List.of());
        return node;
    }

    private static Map<String, Object> overview(List<Map<String, Object>> nodes, String leader) {
        Map<String, Object> overview = new HashMap<>();
        overview.put("nodes", nodes);
        overview.put("leader", leader);
        return overview;
    }

    @Test
    void healthDependsOnLeaderAndAvailability() {
        Map<String, Object> leader = node("node1", "LEADER", 3, null);
        Map<String, Object> follower = node("node2", "FOLLOWER", 3, null);
        Map<String, Object> follower3 = node("node3", "FOLLOWER", 3, null);

        assertEquals("Healthy", DashboardView.from(overview(List.of(leader, follower, follower3), "node1"), null, null).health().label());
        assertEquals("Degraded", DashboardView.from(overview(List.of(leader, follower, offline("node3", false)), "node1"), null, null).health().label());
        assertEquals("No leader", DashboardView.from(overview(List.of(follower, follower3), null), null, null).health().label());
    }

    @Test
    void selectsRequestedNodeThenLeaderThenFirstNode() {
        var nodes = List.of(node("node1", "FOLLOWER", 1, null), node("node2", "LEADER", 1, null));

        assertEquals("node1", DashboardView.from(overview(nodes, "node2"), "node1", null).selectedId());
        assertEquals("node2", DashboardView.from(overview(nodes, "node2"), "unknown", null).selectedId());
        assertEquals("node1", DashboardView.from(overview(nodes, null), null, null).selectedId());
    }

    @Test
    void offlineNodeThatIsManagedIsStarting() {
        assertEquals(DashboardView.Role.STARTING, DashboardView.roleOf(offline("node1", true)));
        assertEquals(DashboardView.Role.OFFLINE, DashboardView.roleOf(offline("node1", false)));
    }

    @Test
    void logMatrixAlignsMachinesByIndexAndMarksEntryStates() {
        var leader = node("node1", "LEADER", 2, log(2, 1, -1, List.of(
            entry(0, 1, "NOOP", null), entry(1, 2, "COMMAND", "a"), entry(2, 2, "COMMAND", "b"))));
        var lagging = node("node2", "FOLLOWER", 2, log(0, 0, -1, List.of(entry(0, 1, "NOOP", null))));
        var conflicting = node("node3", "FOLLOWER", 2, log(1, 0, -1, List.of(entry(0, 1, "NOOP", null), entry(1, 1, "COMMAND", "x"))));

        DashboardView.LogMatrix matrix = DashboardView.from(overview(List.of(leader, lagging, conflicting), "node1"), null, null).logMatrix();

        assertEquals(3, matrix.columns().size());
        assertEquals(1, matrix.columns().get(0).number());

        List<DashboardView.LogCell> leaderCells = matrix.rows().get(0).cells();
        assertTrue(leaderCells.get(0).style().contains("log-noop"));
        assertTrue(leaderCells.get(1).style().contains("log-committed"));
        assertTrue(leaderCells.get(2).style().contains("log-uncommitted"));
        assertEquals("2", leaderCells.get(2).text());

        assertEquals("log-empty", matrix.rows().get(1).cells().get(1).style());

        DashboardView.LogCell conflict = matrix.rows().get(2).cells().get(1);
        assertTrue(conflict.differs());
        assertTrue(conflict.tooltip().contains("term 2"));
        assertFalse(matrix.rows().get(2).cells().get(0).differs());
    }

    @Test
    void logMatrixShowsLatestPositionsAndSnapshots() {
        var leader = node("node1", "LEADER", 1, log(99, 99, 94, List.of(entry(95, 1, "COMMAND", "a"))));

        DashboardView.LogMatrix matrix = DashboardView.from(overview(List.of(leader, offline("node2", false)), "node1"), null, null).logMatrix();

        assertEquals(30, matrix.columns().size());
        assertEquals(71, matrix.columns().get(0).number());
        assertEquals(100, matrix.columns().get(29).number());
        assertEquals("log-compacted", matrix.rows().get(0).cells().get(0).style());
        assertEquals("log-empty", matrix.rows().get(1).cells().get(0).style());
    }

    @Test
    void graphShowsStatesAndReplicationLinks() {
        var leader = node("node1", "LEADER", 4, null);
        var follower = node("node2", "FOLLOWER", 4, null);
        Map<String, Object> overview = overview(List.of(leader, follower, offline("node3", false)), "node1");
        overview.put("replication", Map.of("peers", Map.of(
            "node2", Map.of("upToDate", false, "lag", 3),
            "node3", Map.of("upToDate", false, "lag", 9))));

        DashboardView.Graph graph = DashboardView.from(overview, "node2", null).graph();

        assertEquals(3, graph.nodes().size());
        assertEquals("LEADER", graph.nodes().get(0).role());
        assertTrue(graph.nodes().get(0).label().contains("Leader, term 4"));
        assertTrue(graph.nodes().get(0).label().contains("log 2, committed 1"));
        assertTrue(graph.nodes().get(1).selected());

        var edges = graph.edges().stream().collect(Collectors.toMap(DashboardView.GraphEdge::target, edge -> edge));
        assertEquals("3 entries behind", edges.get("node2").label());
        assertEquals("behind", edges.get("node2").state());
        assertEquals("unreachable", edges.get("node3").state());
    }

    @Test
    void noLeaderMeansNoReplicationLinks() {
        var view = DashboardView.from(overview(List.of(node("node1", "CANDIDATE", 2, null)), null), null, null);

        assertTrue(view.graph().edges().isEmpty());
        assertTrue(view.replication().isEmpty());
        assertEquals("–", view.stats().get(0).value());
    }
}
