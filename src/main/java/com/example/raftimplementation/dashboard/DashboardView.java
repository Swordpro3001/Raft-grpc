package com.example.raftimplementation.dashboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Everything the dashboard template shows, derived from the cluster overview.
 * All decisions live here so that the template only has to display values.
 */
public record DashboardView(
    Health health,
    String summary,
    List<Stat> stats,
    boolean hasLeader,
    List<NodeRow> nodes,
    Inspector inspector,
    String tab,
    List<ReplicationRow> replication,
    List<Row> snapshots,
    LogMatrix logMatrix,
    Graph graph
) {
    public static final String TAB_ACTIVITY = "activity";
    public static final String TAB_ENTRIES = "entries";

    private static final String NONE = "–";
    private static final int MAX_ENTRIES_SHOWN = 200;
    private static final int LOG_COLUMNS = 30;
    private static final int LOG_LABEL_EVERY = 5;

    public enum Role {
        LEADER("Leader", "bg-good"),
        FOLLOWER("Follower", "border-2 border-muted"),
        CANDIDATE("Candidate", "bg-warn"),
        SUSPENDED("Suspended", "bg-warn"),
        STARTING("Starting", "bg-warn"),
        OFFLINE("Offline", "bg-bad"),
        NOT_A_MEMBER("Not a member", "border-2 border-muted");

        private final String label;
        private final String dot;

        Role(String label, String dot) {
            this.label = label;
            this.dot = dot;
        }

        public String getLabel() {
            return label;
        }

        public String getDot() {
            return dot;
        }
    }

    public record Health(String label, String dot) { }
    public record Stat(String label, String value, String unit) { }
    public record Row(String label, String value) { }
    public record NodeRow(String id, Role role, String term, String committed, String log, boolean selected) { }
    public record ReplicationRow(String peer, String label, String dot) { }
    public record EventRow(String title, String time, String text) { }
    public record EntryRow(int number, String text) { }
    public record Inspector(String id, Role role, boolean online, boolean managed, boolean suspended, boolean member,
                            List<Row> details, List<EventRow> events, List<EntryRow> entries,
                            int entryCount, boolean entriesTruncated) { }

    public record LogColumn(int number, boolean labeled) { }
    public record LogCell(String text, String style, boolean differs, String tooltip) { }
    public record LogRow(String id, Role role, List<LogCell> cells) { }
    public record LogMatrix(List<LogColumn> columns, List<LogRow> rows) {
        public boolean isEmpty() {
            return columns.isEmpty();
        }
    }

    public record GraphNode(String id, String label, String role, boolean selected) { }
    public record GraphEdge(String id, String source, String target, String label, String state) { }
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) { }

    public String selectedId() {
        return inspector != null ? inspector.id() : null;
    }

    public static DashboardView from(Map<String, Object> overview, String requestedNode, String requestedTab) {
        List<Map<String, Object>> nodes = listOf(overview.get("nodes"));
        String leaderId = (String) overview.get("leader");
        Map<String, Object> leader = find(nodes, leaderId);

        String selectedId = find(nodes, requestedNode) != null
            ? requestedNode
            : leaderId != null ? leaderId : nodes.isEmpty() ? null : (String) nodes.get(0).get("nodeId");
        String tab = TAB_ENTRIES.equals(requestedTab) ? TAB_ENTRIES : TAB_ACTIVITY;

        List<Map<String, Object>> online = nodes.stream().filter(node -> !"OFFLINE".equals(node.get("state"))).toList();
        List<String> members = membersOf(leader, online, nodes);
        long available = online.stream()
            .filter(node -> members.contains(node.get("nodeId")) && !Boolean.TRUE.equals(node.get("suspended")))
            .count();

        return new DashboardView(
            health(leader != null, available == members.size()),
            summary(leader, online.isEmpty(), available, members.size()),
            stats(leader, mapOf(overview.get("performance"))),
            leader != null,
            nodes.stream().map(node -> nodeRow(node, selectedId)).toList(),
            inspector(find(nodes, selectedId)),
            tab,
            replication(mapOf(overview.get("replication")), leader != null),
            snapshots(mapOf(overview.get("snapshots"))),
            logMatrix(nodes, leader),
            graph(nodes, leaderId, mapOf(overview.get("replication")), selectedId)
        );
    }

    // ==================== Summary ====================

    private static Health health(boolean hasLeader, boolean allAvailable) {
        if (!hasLeader) {
            return new Health("No leader", "bg-bad");
        }
        return allAvailable ? new Health("Healthy", "bg-good") : new Health("Degraded", "bg-warn");
    }

    private static String summary(Map<String, Object> leader, boolean noneOnline, long available, int members) {
        if (leader != null) {
            return String.format("Leader %s, term %s, %d of %d members available",
                leader.get("nodeId"), leader.get("currentTerm"), available, members);
        }
        return noneOnline ? "Waiting for nodes to start" : "Electing a leader";
    }

    private static List<String> membersOf(Map<String, Object> leader, List<Map<String, Object>> online,
                                          List<Map<String, Object>> nodes) {
        Map<String, Object> source = leader != null ? leader : online.isEmpty() ? null : online.get(0);
        if (source != null && source.get("members") != null) {
            return stringsOf(source.get("members"));
        }
        return nodes.stream().map(node -> (String) node.get("nodeId")).toList();
    }

    private static List<Stat> stats(Map<String, Object> leader, Map<String, Object> performance) {
        if (leader == null || performance == null) {
            return List.of(new Stat("Committed entries", NONE, ""), new Stat("Throughput", NONE, ""),
                new Stat("Commit latency", NONE, ""), new Stat("Leader uptime", NONE, ""));
        }
        double latency = number(performance.get("avgCommitLatency"));
        long uptimeSeconds = (long) (number(performance.get("leaderUptime")) / 1000);
        return List.of(
            new Stat("Committed entries", String.valueOf(leader.get("commitIndex")), ""),
            new Stat("Throughput", String.format(java.util.Locale.ROOT, "%.2f", number(performance.get("throughput"))), "cmd/s"),
            latency > 0 ? new Stat("Commit latency", String.valueOf(Math.round(latency)), "ms") : new Stat("Commit latency", NONE, ""),
            uptime(uptimeSeconds)
        );
    }

    private static Stat uptime(long seconds) {
        if (seconds < 60) {
            return new Stat("Leader uptime", String.valueOf(seconds), "s");
        }
        if (seconds < 3600) {
            return new Stat("Leader uptime", String.valueOf(seconds / 60), "min");
        }
        return new Stat("Leader uptime", String.format(java.util.Locale.ROOT, "%.1f", seconds / 3600.0), "h");
    }

    // ==================== Nodes ====================

    static Role roleOf(Map<String, Object> node) {
        String state = (String) node.get("state");
        if ("OFFLINE".equals(state)) {
            return Boolean.TRUE.equals(node.get("managed")) ? Role.STARTING : Role.OFFLINE;
        }
        if (Boolean.FALSE.equals(node.get("member")) && !"SUSPENDED".equals(state)) {
            return Role.NOT_A_MEMBER;
        }
        return Role.valueOf(state);
    }

    private static NodeRow nodeRow(Map<String, Object> node, String selectedId) {
        String id = (String) node.get("nodeId");
        return new NodeRow(id, roleOf(node), text(node.get("currentTerm")), text(node.get("commitIndex")),
            text(node.get("logSize")), id.equals(selectedId));
    }

    private static Inspector inspector(Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        boolean online = !"OFFLINE".equals(node.get("state"));
        boolean suspended = Boolean.TRUE.equals(node.get("suspended"));
        boolean member = online && !Boolean.FALSE.equals(node.get("member"));
        boolean managed = Boolean.TRUE.equals(node.get("managed"));
        String id = (String) node.get("nodeId");
        if (!online) {
            return new Inspector(id, roleOf(node), false, managed, false, false, List.of(), List.of(), List.of(), 0, false);
        }

        List<Row> details = List.of(
            new Row("Leader", node.get("currentLeader") != null ? (String) node.get("currentLeader") : "Unknown"),
            new Row("Applied", text(node.get("lastApplied"))),
            new Row("Snapshot", Boolean.TRUE.equals(node.get("hasSnapshot"))
                ? "Up to entry " + (intOf(node.get("snapshotBaseIndex")) + 1) : "None"),
            new Row("Members", Boolean.TRUE.equals(node.get("jointConsensus"))
                ? "Changing" : String.join(", ", stringsOf(node.get("members")))));

        List<EventRow> events = new ArrayList<>();
        for (Map<String, Object> event : listOf(node.get("events"))) {
            String time = (String) event.get("formattedTimestamp");
            events.add(new EventRow(eventTitle((String) event.get("type")),
                time != null && time.length() >= 8 ? time.substring(0, 8) : "", (String) event.get("description")));
        }
        Collections.reverse(events);

        List<String> stateMachine = stringsOf(node.get("stateMachine"));
        List<EntryRow> entries = new ArrayList<>();
        int firstShown = Math.max(0, stateMachine.size() - MAX_ENTRIES_SHOWN);
        for (int i = stateMachine.size() - 1; i >= firstShown; i--) {
            entries.add(new EntryRow(i + 1, stateMachine.get(i)));
        }

        return new Inspector(id, roleOf(node), true, managed, suspended, member, details, events, entries,
            stateMachine.size(), firstShown > 0);
    }

    private static String eventTitle(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "STATE_CHANGE" -> "Role changed";
            case "ELECTION_START" -> "Election started";
            case "VOTE_GRANTED" -> "Vote granted";
            case "VOTE_DENIED" -> "Vote denied";
            case "ELECTION_WON" -> "Election won";
            case "ELECTION_LOST" -> "Election lost";
            case "HEARTBEAT_RECEIVED" -> "New leader";
            case "TERM_INCREASED" -> "Term increased";
            case "LOG_REPLICATED" -> "Entries replicated";
            case "COMMAND_RECEIVED" -> "Command received";
            case "MEMBERSHIP_CHANGE_START" -> "Membership change started";
            case "MEMBERSHIP_CHANGE_COMMITTED" -> "Membership change committed";
            case "SERVER_ADDED" -> "Server added";
            case "SERVER_REMOVED" -> "Server removed";
            case "SNAPSHOT_CREATED" -> "Snapshot created";
            case "SNAPSHOT_INSTALLED" -> "Snapshot installed";
            default -> type;
        };
    }

    // ==================== Replication and Snapshots ====================

    private static List<ReplicationRow> replication(Map<String, Object> replication, boolean hasLeader) {
        if (!hasLeader || replication == null) {
            return List.of();
        }
        List<ReplicationRow> rows = new ArrayList<>();
        Map<String, Object> peers = mapOf(replication.get("peers"));
        if (peers != null) {
            for (Map.Entry<String, Object> peer : peers.entrySet()) {
                Map<String, Object> status = mapOf(peer.getValue());
                boolean upToDate = Boolean.TRUE.equals(status.get("upToDate"));
                rows.add(new ReplicationRow(peer.getKey(),
                    upToDate ? "Up to date" : behind(intOf(status.get("lag"))),
                    upToDate ? "bg-good" : "bg-warn"));
            }
        }
        return rows;
    }

    private static String behind(int lag) {
        return lag + (lag == 1 ? " entry behind" : " entries behind");
    }

    private static List<Row> snapshots(Map<String, Object> snapshots) {
        if (snapshots == null) {
            return List.of();
        }
        Map<String, Object> last = mapOf(snapshots.get("lastSnapshot"));
        return List.of(
            new Row("Taken on leader", text(snapshots.get("totalSnapshots"))),
            new Row("Compacted entries", text(snapshots.get("compactedEntries"))),
            new Row("Entries in log", text(snapshots.get("currentLogSize"))),
            new Row("Latest", last != null
                ? "Entry " + (intOf(last.get("lastIncludedIndex")) + 1) + ", term " + last.get("lastIncludedTerm")
                : "None"));
    }

    // ==================== Log Matrix ====================

    /**
     * One row per machine and one column per log position, aligned by log index, which makes
     * it visible how far each log reaches and whether it agrees with the leader's.
     */
    private static LogMatrix logMatrix(List<Map<String, Object>> nodes, Map<String, Object> leader) {
        int lastIndex = -1;
        for (Map<String, Object> node : nodes) {
            Map<String, Object> log = mapOf(node.get("log"));
            if (log != null) {
                lastIndex = Math.max(lastIndex, intOf(log.get("lastIndex")));
            }
        }
        if (lastIndex < 0) {
            return new LogMatrix(List.of(), nodes.stream()
                .map(node -> new LogRow((String) node.get("nodeId"), roleOf(node), List.of())).toList());
        }

        int firstIndex = Math.max(0, lastIndex - LOG_COLUMNS + 1);
        List<LogColumn> columns = new ArrayList<>();
        for (int index = firstIndex; index <= lastIndex; index++) {
            int number = index + 1;
            columns.add(new LogColumn(number, index == firstIndex || index == lastIndex || number % LOG_LABEL_EVERY == 0));
        }

        Map<Integer, Integer> leaderTerms = leader != null ? termsByIndex(mapOf(leader.get("log"))) : Map.of();
        List<LogRow> rows = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            List<LogCell> cells = new ArrayList<>();
            Map<String, Object> log = mapOf(node.get("log"));
            Map<Integer, Map<String, Object>> entries = entriesByIndex(log);
            for (int index = firstIndex; index <= lastIndex; index++) {
                cells.add(logCell(index, log, entries.get(index), leaderTerms.get(index)));
            }
            rows.add(new LogRow((String) node.get("nodeId"), roleOf(node), cells));
        }
        return new LogMatrix(columns, rows);
    }

    private static LogCell logCell(int index, Map<String, Object> log, Map<String, Object> entry, Integer leaderTerm) {
        int number = index + 1;
        if (log == null) {
            return new LogCell("", "log-empty", false, "Entry " + number + ": unknown, this machine is not reachable");
        }
        if (index <= intOf(log.get("snapshotIndex"))) {
            return new LogCell("", "log-compacted", false, "Entry " + number + ": in snapshot");
        }
        if (entry == null) {
            return new LogCell("", "log-empty", false, "Entry " + number + ": not on this machine");
        }
        int term = intOf(entry.get("term"));
        boolean committed = index <= intOf(log.get("commitIndex"));
        boolean differs = leaderTerm != null && leaderTerm != term;
        String type = (String) entry.get("type");

        StringBuilder tooltip = new StringBuilder("Entry ").append(number).append(", term ").append(term)
            .append(committed ? ", committed" : ", not committed");
        if ("NOOP".equals(type)) {
            tooltip.append(", no-op of a new leader");
        } else {
            tooltip.append(": ").append(entry.get("command"));
        }
        if (differs) {
            tooltip.append(". Differs from the leader, which has term ").append(leaderTerm);
        }

        String style = (committed ? "log-committed" : "log-uncommitted")
            + ("CONFIGURATION".equals(type) ? " log-config" : "NOOP".equals(type) ? " log-noop" : "");
        return new LogCell(String.valueOf(term), style, differs, tooltip.toString());
    }

    private static Map<Integer, Map<String, Object>> entriesByIndex(Map<String, Object> log) {
        Map<Integer, Map<String, Object>> entries = new java.util.HashMap<>();
        if (log != null) {
            for (Map<String, Object> entry : listOf(log.get("entries"))) {
                entries.put(intOf(entry.get("index")), entry);
            }
        }
        return entries;
    }

    private static Map<Integer, Integer> termsByIndex(Map<String, Object> log) {
        Map<Integer, Integer> terms = new java.util.HashMap<>();
        entriesByIndex(log).forEach((index, entry) -> terms.put(index, intOf(entry.get("term"))));
        return terms;
    }

    // ==================== Graph ====================

    /**
     * The machines of the cluster and the replication links from the leader to its peers.
     */
    private static Graph graph(List<Map<String, Object>> nodes, String leaderId,
                               Map<String, Object> replication, String selectedId) {
        List<GraphNode> graphNodes = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            String id = (String) node.get("nodeId");
            Role role = roleOf(node);
            String label = node.get("currentTerm") == null
                ? id + "\n" + role.getLabel()
                : id + "\n" + role.getLabel() + ", term " + node.get("currentTerm")
                    + "\nlog " + node.get("logSize") + ", committed " + node.get("commitIndex");
            graphNodes.add(new GraphNode(id, label, role.name(), id.equals(selectedId)));
        }

        List<GraphEdge> edges = new ArrayList<>();
        Map<String, Object> peers = replication != null ? mapOf(replication.get("peers")) : null;
        if (leaderId != null && peers != null) {
            for (Map.Entry<String, Object> peer : peers.entrySet()) {
                Map<String, Object> target = find(nodes, peer.getKey());
                if (target == null) {
                    continue;
                }
                Map<String, Object> status = mapOf(peer.getValue());
                boolean reachable = !"OFFLINE".equals(target.get("state")) && !Boolean.TRUE.equals(target.get("suspended"));
                boolean upToDate = Boolean.TRUE.equals(status.get("upToDate"));
                edges.add(new GraphEdge(leaderId + "-" + peer.getKey(), leaderId, peer.getKey(),
                    !reachable ? "Unreachable" : upToDate ? "" : behind(intOf(status.get("lag"))),
                    !reachable ? "unreachable" : upToDate ? "synced" : "behind"));
            }
        }
        return new Graph(graphNodes, edges);
    }

    // ==================== JSON Helpers ====================

    private static Map<String, Object> find(List<Map<String, Object>> nodes, String nodeId) {
        return nodes.stream().filter(node -> node.get("nodeId").equals(nodeId)).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringsOf(Object value) {
        return value == null ? List.of() : (List<String>) value;
    }

    private static String text(Object value) {
        return value == null ? NONE : String.valueOf(value);
    }

    private static int intOf(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static double number(Object value) {
        return value == null ? 0 : ((Number) value).doubleValue();
    }
}
