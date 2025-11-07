package com.example.raftimplementation.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class NodeManagerService {
    
    private final Map<String, Process> runningNodes = new ConcurrentHashMap<>();
    private final Map<String, List<String>> nodeLogs = new ConcurrentHashMap<>();
    private final String projectPath;
    
    private static final Map<String, Integer> NODE_PORTS = Map.of(
        "node1", 8081,
        "node2", 8082,
        "node3", 8083,
        "node4", 8084,
        "node5", 8085
    );
    
    public NodeManagerService() {
        this.projectPath = System.getProperty("user.dir");
        log.info("NodeManager initialized with project path: {}", projectPath);
    }
    
    public synchronized boolean startNode(String nodeId) {
        if (runningNodes.containsKey(nodeId)) {
            log.warn("Node {} is already running", nodeId);
            return false;
        }
        
        try {
            log.info("Starting node: {}", nodeId);
            
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            
            ProcessBuilder processBuilder;
            if (isWindows) {
                processBuilder = new ProcessBuilder(
                    "cmd.exe", "/c",
                    "gradlew.bat", "bootRun",
                    "--args=--spring.profiles.active=" + nodeId
                );
            } else {
                processBuilder = new ProcessBuilder(
                    "./gradlew", "bootRun",
                    "--args=--spring.profiles.active=" + nodeId
                );
            }
            
            processBuilder.directory(new File(projectPath));
            processBuilder.redirectErrorStream(true);
            
            Process process = processBuilder.start();
            runningNodes.put(nodeId, process);
            nodeLogs.put(nodeId, new ArrayList<>());
            
            startLogCapture(nodeId, process);
            
            log.info("Node {} started successfully", nodeId);
            return true;
            
        } catch (IOException e) {
            log.error("Failed to start node {}: {}", nodeId, e.getMessage(), e);
            return false;
        }
    }
    
    public synchronized boolean stopNode(String nodeId) {
        Integer port = NODE_PORTS.get(nodeId);
        if (port == null) {
            log.error("Unknown node ID: {}", nodeId);
            return false;
        }

        if (!isPortInUse(port)) {
            log.warn("Node {} is not running (port {} is free)", nodeId, port);
            Process process = runningNodes.remove(nodeId);
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return false;
        }
        
        String pid = getProcessIdByPort(port);
        if (pid == null) {
            log.error("Could not determine process ID for node {} on port {}", nodeId, port);
            return false;
        }
        
        log.info("Stopping node {} (PID: {}) on port {}", nodeId, pid, port);
        
        boolean killed = killProcessByPid(pid);
        
        if (killed) {
            runningNodes.remove(nodeId);
            
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            if (!isPortInUse(port)) {
                log.info("Node {} stopped successfully", nodeId);
                return true;
            } else {
                log.warn("Node {} may still be running (port still in use)", nodeId);
                return false;
            }
        } else {
            log.error("Failed to kill process {} for node {}", pid, nodeId);
            return false;
        }
    }
    
    public boolean isNodeRunning(String nodeId) {
        Integer port = NODE_PORTS.get(nodeId);
        if (port == null) {
            log.warn("Unknown node ID: {}", nodeId);
            return false;
        }
        
        boolean portInUse = isPortInUse(port);
        
        if (!portInUse) {
            Process process = runningNodes.get(nodeId);
            if (process != null) {
                log.debug("Cleaning up stale process reference for {}", nodeId);
                runningNodes.remove(nodeId);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
        
        return portInUse;
    }
    
    private boolean isPortInUse(int port) {
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb;
            
            if (isWindows) {
                pb = new ProcessBuilder("cmd.exe", "/c", 
                    "netstat -ano | findstr :" + port);
            } else {
                pb = new ProcessBuilder("sh", "-c", "lsof -i :" + port);
            }
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            boolean found = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().length() > 0) {
                    log.info("Port {} check - Found line: {}", port, line);
                    found = true;
                    break; 
                }
            }
            
            int exitCode = process.waitFor();
            
            log.info("Port {} check result: {} (exit code: {})", port, found ? "IN USE" : "FREE", exitCode);
            return found;
        } catch (Exception e) {
            log.error("Error checking if port {} is in use", port, e);
            return false;
        }
    }
    
    private String getProcessIdByPort(int port) {
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb;
            
            if (isWindows) {
                pb = new ProcessBuilder("cmd.exe", "/c", 
                    "netstat -ano | findstr :" + port);
            } else {
                pb = new ProcessBuilder("sh", "-c", "lsof -t -i:" + port);
            }
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("PID search for port {} - line: {}", port, line);
                
                if (isWindows) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("TCP") && trimmed.contains(":" + port)) {
                        String[] parts = trimmed.split("\\s+");
                        if (parts.length >= 5) {
                            String pid = parts[parts.length - 1];
                            if (pid.matches("\\d+")) {
                                log.info("Found PID {} for port {}", pid, port);
                                return pid;
                            }
                        }
                    }
                } else {
                    String pid = line.trim();
                    if (!pid.isEmpty() && pid.matches("\\d+")) {
                        log.info("Found PID {} for port {}", pid, port);
                        return pid;
                    }
                }
            }
            
            process.waitFor();
            log.warn("No PID found for port {}", port);
        } catch (Exception e) {
            log.error("Error getting process ID for port {}", port, e);
        }
        return null;
    }
    
    private boolean killProcessByPid(String pid) {
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb;
            
            if (isWindows) {
                pb = new ProcessBuilder("taskkill", "/F", "/PID", pid);
            } else {
                pb = new ProcessBuilder("kill", "-9", pid);
            }
            
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.error("Error killing process {}", pid, e);
            return false;
        }
    }
    
    public Map<String, Boolean> getAllNodesStatus() {
        Map<String, Boolean> status = new ConcurrentHashMap<>();
        for (int i = 1; i <= 5; i++) {
            String nodeId = "node" + i;
            status.put(nodeId, isNodeRunning(nodeId));
        }
        return status;
    }
    
    public List<String> getNodeLogs(String nodeId) {
        return nodeLogs.getOrDefault(nodeId, new ArrayList<>());
    }
    
    private void startLogCapture(String nodeId, Process process) {
        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                List<String> logs = nodeLogs.get(nodeId);
                
                while ((line = reader.readLine()) != null && process.isAlive()) {
                    if (logs.size() > 100) {
                        logs.remove(0);
                    }
                    logs.add(line);
                    log.debug("[{}] {}", nodeId, line);
                }
                
            } catch (IOException e) {
                log.error("Error reading logs from node {}", nodeId, e);
            }
        });
        
        logThread.setDaemon(true);
        logThread.setName("LogCapture-" + nodeId);
        logThread.start();
    }
    
    public void stopAllNodes() {
        log.info("Stopping all nodes...");
        new ArrayList<>(runningNodes.keySet()).forEach(this::stopNode);
    }
}
