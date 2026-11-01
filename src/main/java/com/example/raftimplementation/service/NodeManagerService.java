package com.example.raftimplementation.service;

import com.example.raftimplementation.RaftimplementationApplication;
import com.example.raftimplementation.config.RaftConfig;
import com.example.raftimplementation.model.ServerInfo;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local cluster supervisor: runs the nodes as child JVMs of the dashboard process, writes their
 * output to logs/, and stops them together with it. Children reuse this process's Java runtime
 * and classpath, so a cluster starts in seconds without a build tool in between.
 */
@Service
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "false")
@Slf4j
public class NodeManagerService {

    private static final Pattern NODE_ID = Pattern.compile("node([1-9][0-9]{0,2})");
    private static final Path LOG_DIRECTORY = Path.of("logs");
    private static final Path DATA_DIRECTORY = Path.of("data");
    private static final long READY_TIMEOUT_MS = 60_000;
    // Enough for a small demo node; favors startup time and footprint over peak performance
    private static final List<String> NODE_JVM_FLAGS = List.of(
        "-Xms64m", "-Xmx256m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xshare:auto");

    private final Map<String, Process> processes = new ConcurrentSkipListMap<>(nodeOrder());
    private final TreeSet<String> knownNodes = new TreeSet<>(nodeOrder());
    private final RaftConfig config;
    private final RestTemplate restTemplate;
    private final boolean autostart;
    private final int serverPort;
    private volatile boolean stopping = false;

    public NodeManagerService(RaftConfig config, RestTemplate restTemplate,
                              @Value("${raft.local.autostart:true}") boolean autostart,
                              @Value("${server.port:8080}") int serverPort) {
        this.config = config;
        this.restTemplate = restTemplate;
        this.autostart = autostart;
        this.serverPort = serverPort;
        for (int number = 1; number <= config.getClusterSize(); number++) {
            knownNodes.add("node" + number);
        }
        knownNodes.addAll(nodesWithData());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startCluster() {
        System.out.printf("%nDashboard   http://localhost:%d%n", serverPort);
        if (!autostart) {
            return;
        }
        long startMillis = System.currentTimeMillis();
        List<String> nodes = getKnownNodes();
        System.out.printf("Starting    %s (logs in %s%s)%n", String.join(", ", nodes), LOG_DIRECTORY, File.separator);
        for (String nodeId : nodes) {
            try {
                startNode(nodeId);
            } catch (IllegalStateException | IllegalArgumentException e) {
                System.out.printf("Warning     %s%n", e.getMessage());
            }
        }
        Thread.ofVirtual().name("cluster-ready").start(() -> announceWhenReady(startMillis));
    }

    private void announceWhenReady(long startMillis) {
        while (!stopping && System.currentTimeMillis() - startMillis < READY_TIMEOUT_MS) {
            String leader = findLeader();
            if (leader != null) {
                double seconds = (System.currentTimeMillis() - startMillis) / 1000.0;
                System.out.printf(Locale.ROOT, "Ready       leader %s elected after %.1f s. Press Ctrl+C to stop.%n%n", leader, seconds);
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (!stopping) {
            System.out.printf("Warning     no leader after %d s, check %s%s%n",
                READY_TIMEOUT_MS / 1000, LOG_DIRECTORY, File.separator);
        }
    }

    private String findLeader() {
        for (String nodeId : getKnownNodes()) {
            try {
                Map<?, ?> status = restTemplate.getForObject(baseUrl(nodeId) + "/api/status", Map.class);
                if (status != null && "LEADER".equals(status.get("state"))) {
                    return nodeId;
                }
            } catch (Exception e) {
                log.debug("{} is not ready yet", nodeId);
            }
        }
        return null;
    }

    /**
     * Starts a known node, or registers and starts a new one.
     *
     * @throws IllegalArgumentException if the node ID is invalid
     * @throws IllegalStateException if the node is running or its ports are taken
     */
    public synchronized void startNode(String nodeId) {
        ServerInfo address = addressOf(nodeId);
        if (isRunning(nodeId)) {
            throw new IllegalStateException(nodeId + " is already running");
        }
        if (isPortInUse(address.getHttpPort()) || isPortInUse(address.getGrpcPort())) {
            throw new IllegalStateException("Port " + address.getHttpPort() + " or " + address.getGrpcPort()
                + " is in use, cannot start " + nodeId);
        }

        // A node beyond the initial cluster has to be added by the leader before it may take part
        boolean joining = numberOf(nodeId) > config.getClusterSize();
        try {
            Files.createDirectories(LOG_DIRECTORY);
            Process process = new ProcessBuilder(nodeCommand(address, joining))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(LOG_DIRECTORY.resolve(nodeId + ".log").toFile()))
                .start();
            processes.put(nodeId, process);
            knownNodes.add(nodeId);
            log.info("Started {} with PID {}", nodeId, process.pid());
        } catch (IOException e) {
            throw new IllegalStateException("Could not start " + nodeId + ": " + e.getMessage(), e);
        }
    }

    private List<String> nodeCommand(ServerInfo address, boolean joining) {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command()
            .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.addAll(NODE_JVM_FLAGS);

        // A single jar on the classpath means we run from the Boot jar, otherwise from Gradle or an IDE
        String classpath = System.getProperty("java.class.path");
        if (classpath.endsWith(".jar") && !classpath.contains(File.pathSeparator)) {
            command.addAll(List.of("-jar", classpath));
        } else {
            command.addAll(List.of("-cp", classpath, RaftimplementationApplication.class.getName()));
        }

        command.add("--spring.profiles.active=node");
        command.add("--raft.node-id=" + address.getNodeId());
        command.add("--server.port=" + address.getHttpPort());
        command.add("--raft.grpc-port=" + address.getGrpcPort());
        command.add("--raft.cluster-size=" + config.getClusterSize());
        command.add("--raft.join=" + joining);
        command.add("--raft.parent-pid=" + ProcessHandle.current().pid());
        return command;
    }

    /**
     * Stops a node process. Raft has to tolerate this at any time.
     */
    public synchronized void stopNode(String nodeId) {
        Process process = processes.remove(nodeId);
        if (process == null || !process.isAlive()) {
            throw new IllegalStateException(nodeId + " is not running, or was not started from here");
        }
        terminate(List.of(process));
    }

    public boolean isRunning(String nodeId) {
        Process process = processes.get(nodeId);
        return process != null && process.isAlive();
    }

    public List<String> getKnownNodes() {
        synchronized (this) {
            return new ArrayList<>(knownNodes);
        }
    }

    /**
     * The next unused node ID, for adding a server to the cluster.
     */
    public synchronized String nextNodeId() {
        return "node" + (numberOf(knownNodes.last()) + 1);
    }

    public ServerInfo addressOf(String nodeId) {
        return RaftConfig.conventionalAddress(numberOf(nodeId));
    }

    public String baseUrl(String nodeId) {
        ServerInfo address = addressOf(nodeId);
        return "http://" + address.getHost() + ":" + address.getHttpPort();
    }

    @PreDestroy
    public synchronized void stopAll() {
        stopping = true;
        if (!processes.isEmpty()) {
            System.out.printf("Stopping    %s%n", String.join(", ", processes.keySet()));
        }
        terminate(new ArrayList<>(processes.values()));
        processes.clear();
    }

    private void terminate(List<Process> targets) {
        targets.forEach(Process::destroy);
        for (Process process : targets) {
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private boolean isPortInUse(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Nodes that were added in an earlier run are still part of the configuration in the logs.
     */
    private List<String> nodesWithData() {
        if (!Files.isDirectory(DATA_DIRECTORY)) {
            return List.of();
        }
        try (Stream<Path> directories = Files.list(DATA_DIRECTORY)) {
            return directories
                .map(directory -> directory.getFileName().toString())
                .filter(name -> NODE_ID.matcher(name).matches())
                .toList();
        } catch (IOException e) {
            log.warn("Could not read {}: {}", DATA_DIRECTORY, e.getMessage());
            return List.of();
        }
    }

    private static int numberOf(String nodeId) {
        Matcher matcher = NODE_ID.matcher(nodeId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid node ID: " + nodeId);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Comparator<String> nodeOrder() {
        return Comparator.comparingInt(NodeManagerService::numberOf);
    }
}
