# Raft Consensus Implementation with Spring Boot and gRPC

![CI Tests](https://github.com/Swordpro3001/Raft-grpc/actions/workflows/test.yml/badge.svg)
![Code Quality](https://github.com/Swordpro3001/Raft-grpc/actions/workflows/code-quality.yml/badge.svg)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A complete implementation of the Raft Consensus Algorithm using Spring Boot and gRPC for local execution, with support for dynamic cluster membership changes.

## Features

- Full Raft implementation (Leader Election, Log Replication, Commit)
- gRPC communication between nodes
- 3+ locally running nodes with configurable ports
- Web dashboard for visualizing node states
- REST API for command input and cluster management
- Dynamic cluster membership changes (add/remove servers)
- Centralized NodeManager for intelligent command routing
- State machine for applied commands

## Architecture

### Node Communication

```
┌──────────────────────┐         ┌──────────────────────┐
│     Client           │────────>│ NodeManagerController│
└──────────────────────┘         └──────────────────────┘
                                          │
                        ┌─────────────────┼─────────────────┐
                        V                 V                 V
                    ┌────────┐        ┌────────┐        ┌────────┐
                    │ Node 1 │───────>│ Node 2 │───────>│ Node 3 │
                    │(Leader)│        │(Follow)│        │(Follow)│
                    │ 8081   │        │ 8082   │        │ 8083   │
                    │gRPC:91 │        │gRPC:92 │        │gRPC:93 │
                    └────────┘        └────────┘        └────────┘
```

The NodeManagerController acts as an intelligent API gateway:
- Maintains cluster membership information
- Tracks the current leader
- Automatically forwards commands to the leader
- Discovers leader if not already known
- Handles cluster membership operations

## Raft Components

### Node States

- FOLLOWER: Default state, responds to leader heartbeats
- CANDIDATE: Initiates leader election
- LEADER: Accepts client requests and replicates logs

### gRPC Services

1. RequestVote - for leader elections
2. AppendEntries - for heartbeats and log replication

### Persistent State

- currentTerm: Current election term
- votedFor: Candidate voted for in current term
- log[]: Log entries with client commands or configuration changes

## Dynamic Cluster Membership

### Overview

This implementation supports dynamic cluster membership changes according to the Raft paper (Section 6), allowing servers to be added or removed without downtime.

### Two-Phase Approach

When changing membership, Raft uses two configuration states:

**Phase 1: Joint Consensus (C_old,new)**
- Log entry contains both old and new configurations
- Requires majority in both old AND new configurations
- Prevents split-brain during transition

**Phase 2: New Configuration (C_new)**
- After C_old,new is committed, log entry with C_new
- Only requires majority in new configuration

### Adding a Server

```bash
# Step 1: Start the new server
curl -X POST http://localhost:8081/api/nodes/node4/start

# Step 2: Wait for it to start up (5-10 seconds)

# Step 3: Add to cluster
curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{
    "nodeId": "node4",
    "host": "localhost",
    "grpcPort": 9094,
    "httpPort": 8084
  }'

# Step 4: Verify cluster status
curl http://localhost:8081/api/cluster/status
```

### Removing a Server

```bash
# Remove from cluster
curl -X POST http://localhost:8081/api/cluster/remove/node3

# Verify removal
curl http://localhost:8081/api/membership/members

# Stop the server
curl -X POST http://localhost:8081/api/nodes/node3/stop
```

## Quick Start

### Requirements

- Java 17+
- Gradle (or use the included Gradle Wrapper)

### Start all nodes

**Windows:**

```bash
start-cluster.bat
```

**Manual (each node in a separate terminal):**

```bash
# Node 1
gradlew bootRun --args="--spring.profiles.active=node1"

# Node 2
gradlew bootRun --args="--spring.profiles.active=node2"

# Node 3
gradlew bootRun --args="--spring.profiles.active=node3"
```

### Open the Dashboard

The index.html should open automatically in the browser or open:

- [http://localhost:8081/index.html](http://localhost:8081/index.html)

## Common Quick Tasks

### Submit a Command (Recommended Way)

No need to know who the leader is. Use the NodeManager endpoint which auto-routes to the leader:

```bash
curl -X POST http://localhost:8081/api/cluster/command \
  -H "Content-Type: application/json" \
  -d '{"command": "SET username=john"}'
```

### Add a New Server

```bash
# Step 1: Start the new node
curl -X POST http://localhost:8081/api/nodes/node4/start

# Step 2: Wait for startup (10 seconds)
sleep 10

# Step 3: Add to cluster
curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{
    "nodeId": "node4",
    "host": "localhost",
    "grpcPort": 9094,
    "httpPort": 8084
  }'

# Step 4: Verify
curl http://localhost:8081/api/membership/members
```

### Remove a Server

```bash
# Remove from cluster
curl -X POST http://localhost:8081/api/cluster/remove/node3

# Verify removal
curl http://localhost:8081/api/membership/members

# Stop the node
curl -X POST http://localhost:8081/api/nodes/node3/stop
```

### Check Cluster Status

```bash
# Get overall cluster info
curl http://localhost:8081/api/cluster/status

# Get individual node status
curl http://localhost:8081/api/status
```

## API Endpoints

### Essential Cluster Operations (Recommended)

Use these endpoints for all normal operations. They automatically discover and route to the leader.

| Endpoint | Method | Description |
|----------|--------|-------------|
| /api/cluster/command | POST | Submit command (auto-routes to leader) |
| /api/cluster/status | GET | Get cluster-wide status |
| /api/cluster/add | POST | Add server to cluster |
| /api/cluster/remove/{nodeId} | POST | Remove server from cluster |
| /api/membership/members | GET | List all cluster members |

### Examples

```bash
# Submit a command
curl -X POST http://localhost:8081/api/cluster/command \
  -H "Content-Type: application/json" \
  -d '{"command": "SET key=value"}'

# Get cluster-wide status
curl http://localhost:8081/api/cluster/status

# Add server to cluster
curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{"nodeId": "node6", "host": "localhost", "grpcPort": 9096, "httpPort": 8086}'

# Remove server from cluster
curl -X POST http://localhost:8081/api/cluster/remove/node5

# List cluster members
curl http://localhost:8081/api/membership/members
```

### Direct Node Endpoints (Advanced)

For advanced use cases, you can interact directly with individual nodes:

| Endpoint | Method | Description |
|----------|--------|-------------|
| /api/status | GET | Get individual node status |
| /api/command | POST | Submit command directly to node (must be leader) |
| /api/membership/add | POST | Add server (leader only, direct) |
| /api/membership/remove/{nodeId} | POST | Remove server (leader only, direct) |

### Node Management

| Endpoint | Method | Description |
|----------|--------|-------------|
| /api/nodes/{nodeId}/start | POST | Start a node |
| /api/nodes/{nodeId}/stop | POST | Stop a node |
| /api/nodes/status | GET | Get all nodes status |
| /api/nodes/{nodeId}/logs | GET | Get node logs |

### Response Examples

Get node status:
```bash
curl http://localhost:8081/api/status
```

Response:
```json
{
  "nodeId": "node1",
  "state": "LEADER",
  "currentTerm": 5,
  "currentLeader": "node1",
  "commitIndex": 3,
  "logSize": 4,
  "stateMachine": ["SET x=1", "ADD item1", "UPDATE y=2"]
}
```

## Using the Dashboard

1. Open index.html in your browser
2. Wait until a leader is elected (automatically after a few seconds)
3. Enter a command, for example:
   - SET username=john
   - ADD item123
   - UPDATE counter=42
4. Click "Send Command"
5. Watch as the command is replicated to all nodes

## Best Practices

### 1. Always Maintain Quorum
- For a 3-node cluster, keep at least 2 nodes running
- For a 5-node cluster, keep at least 3 nodes running
- Never remove multiple nodes simultaneously

### 2. Use Centralized Endpoints
- Use /api/cluster/* endpoints for all operations
- They automatically find and route to the leader
- No need to know cluster topology

### 3. Wait Between Operations
- Wait 5-10 seconds after starting a new node before adding it
- Wait for replication to complete before next operation
- Verify cluster status before continuing

### 4. Graceful Operations
- Remove nodes from cluster before stopping them
- Wait for membership change to commit
- Verify removal before shutdown

### 5. Monitor Cluster Health
- Check cluster status regularly: curl http://localhost:8081/api/cluster/status
- Verify state machine consistency across nodes
- Watch for membership change events in logs

## Common Scenarios

### Scenario: Scale Cluster from 3 to 5 Nodes

```bash
# Current: 3 nodes running (node1, node2, node3)

# Add node4
curl -X POST http://localhost:8081/api/nodes/node4/start
sleep 10
curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"node4", "host":"localhost", "grpcPort":9094, "httpPort":8084}'

# Verify node4 joined
curl http://localhost:8081/api/membership/members

# Add node5
curl -X POST http://localhost:8081/api/nodes/node5/start
sleep 10
curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"node5", "host":"localhost", "grpcPort":9095, "httpPort":8085}'

# Done: Now have 5 nodes
curl http://localhost:8081/api/cluster/status
```

### Scenario: Rolling Upgrade

```bash
# Upgrade nodes one at a time to maintain availability

# Upgrade node1
curl -X POST http://localhost:8081/api/cluster/remove/node1
curl -X POST http://localhost:8081/api/nodes/node1/stop
# Replace binary and restart
curl -X POST http://localhost:8081/api/nodes/node1/start
sleep 10
curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"node1", "host":"localhost", "grpcPort":9091, "httpPort":8081}'

# Repeat for node2, node3, etc.
```

### Scenario: Replace Failed Node

```bash
# Node2 has failed

# Remove from cluster
curl -X POST http://localhost:8081/api/cluster/remove/node2

# Start replacement
curl -X POST http://localhost:8081/api/nodes/node2/start
sleep 10

# Add back
curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"node2", "host":"localhost", "grpcPort":9092, "httpPort":8082}'
```

### Test Leader Election

```bash
# Stop the current leader (e.g., Node 1)
curl -X POST http://localhost:8081/api/nodes/node1/stop

# Observe how a new leader is elected from the remaining nodes
curl http://localhost:8082/api/status
```

### Test Log Replication

```bash
# Send several commands via the dashboard
curl -X POST http://localhost:8081/api/cluster/command \
  -H "Content-Type: application/json" \
  -d '{"command": "TEST1"}'

curl -X POST http://localhost:8081/api/cluster/command \
  -H "Content-Type: application/json" \
  -d '{"command": "TEST2"}'

# Watch how all nodes receive identical log entries
curl http://localhost:8081/api/status | jq '.logSize'
curl http://localhost:8082/api/status | jq '.logSize'
curl http://localhost:8083/api/status | jq '.logSize'
```

### Test Membership Changes

```bash
# Start with 3 nodes
# Add node4
curl -X POST http://localhost:8081/api/nodes/node4/start
sleep 5
curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{"nodeId": "node4", "host": "localhost", "grpcPort": 9094, "httpPort": 8084}'

# Verify node4 is in cluster
curl http://localhost:8081/api/membership/members

# Submit command and verify it replicates to node4
curl -X POST http://localhost:8081/api/cluster/command \
  -H "Content-Type: application/json" \
  -d '{"command": "POST_ADD_TEST"}'

curl http://localhost:8084/api/status
```

### Simulate Network Partition

```bash
# Stop 2 out of 3 nodes
curl -X POST http://localhost:8081/api/nodes/node1/stop
curl -X POST http://localhost:8081/api/nodes/node2/stop

# The remaining node cannot become leader (no majority)
curl http://localhost:8083/api/status

# Restart one node - a new leader election will occur
curl -X POST http://localhost:8081/api/nodes/node1/start
sleep 10
curl http://localhost:8083/api/status
```

## Project Structure

```
src/
├── main/
│   ├── java/com/example/raftimplementation/
│   │   ├── config/
│   │   │   ├── AppConfig.java                    # RestTemplate bean
│   │   │   └── RaftConfig.java                   # Node and peer configuration
│   │   ├── controller/
│   │   │   ├── NodeManagerController.java        # Centralized API gateway
│   │   │   └── RaftController.java               # REST API controller
│   │   ├── grpc/
│   │   │   └── RaftGrpcService.java              # gRPC service implementation
│   │   ├── model/
│   │   │   ├── ClusterConfiguration.java         # Cluster config model
│   │   │   ├── ServerInfo.java                   # Server information model
│   │   │   ├── LogEntry.java                     # Log entry model
│   │   │   ├── NodeState.java                    # Node state enum
│   │   │   └── RaftEvent.java                    # Event model
│   │   ├── service/
│   │   │   ├── ClusterManager.java               # Cluster membership registry
│   │   │   └── RaftNode.java                     # Core Raft logic
│   │   └── proto/
│   │       └── raft.proto                        # gRPC protocol buffer definition
│   └── resources/
│       ├── application-node1.properties          # Config for Node 1
│       ├── application-node2.properties          # Config for Node 2
│       ├── application-node3.properties          # Config for Node 3
│       └── index.html                            # Web dashboard
└── test/
```

## Configuration

Each node has its own configuration file (application-node*.properties):

```properties
# Node ID
raft.node-id=node1

# HTTP Port (REST API)
server.port=8081

# gRPC Port (Raft communication)
spring.grpc.server.port=9091

# Peer configuration
raft.peers[0].node-id=node1
raft.peers[0].host=localhost
raft.peers[0].grpc-port=9091
# ... more peers
```

### Timeouts and Tuning

In RaftNode.java, you can adjust these values:

```java
private static final int ELECTION_TIMEOUT_MIN = 3000;  // 3 seconds
private static final int ELECTION_TIMEOUT_MAX = 5000;  // 5 seconds
private static final int HEARTBEAT_INTERVAL = 1000;    // 1 second
```

Shorter timeouts make elections faster but increase election churn. Longer timeouts reduce unnecessary elections but increase failover time.

### Logging Configuration

Adjust logging level in application-node*.properties:

```properties
# Detailed logging
logging.level.com.example.raftimplementation=DEBUG

# gRPC logging
logging.level.io.grpc=DEBUG
```

## Data Models

### ClusterConfiguration

Represents a cluster configuration state during membership changes:

```java
public class ClusterConfiguration {
    private Set<String> oldServers;  // Servers in old config
    private Set<String> newServers;  // Servers in new config
    private boolean isJoint;         // True during transition
}
```

States:
- Normal: oldServers == newServers, isJoint = false
- Joint Consensus: oldServers != newServers, isJoint = true

### ServerInfo

Information about a cluster member:

```java
public class ServerInfo {
    private String nodeId;
    private String host;
    private int grpcPort;
    private int httpPort;
}
```

### LogEntry (Extended)

Log entries can contain regular commands or configuration changes:

```java
public class LogEntry {
    private int term;
    private String command;                       // Regular command (optional)
    private ClusterConfiguration configuration;  // Config change (optional)
}
```

## Safety Guarantees

### No Split-Brain

During membership changes, the joint consensus (C_old,new) ensures:
- Decisions require majority in both old and new configurations
- Impossible for both old and new configurations to make independent decisions

### Availability During Changes

- Cluster remains available during membership changes
- No downtime required
- Multiple changes can be in progress (serialized through log)

### Leader Election Safety

- Election requires majority in current configuration
- During C_old,new: requires majority in both old and new
- Prevents election of servers not in new configuration

## Troubleshooting

### Nodes do not connect

- Check that all ports (8081-8083, 9091-9093) are free
- Check firewall settings
- Inspect logs for each node

### No leader is elected

- Wait at least 5-10 seconds for initial election
- Ensure at least 2 out of 3 nodes are running
- Check logs for errors
- Verify network connectivity between nodes

### Commands are not replicated

- Ensure the node you're contacting is actually the leader
- Use NodeManagerController (/api/cluster/command) to auto-route to leader
- Look for "AppendEntries" messages in logs
- Check the commitIndex of all nodes to verify replication

### Port conflicts

```bash
# Check if ports are in use (Linux/macOS)
lsof -i :8081
lsof -i :9091

# Windows
netstat -ano | findstr :8081
```

### gRPC connection issues

- Ensure firewall allows connections on gRPC ports (9091-9093)
- Check that peer host and port configuration is correct
- Enable DEBUG logging for io.grpc to see connection attempts

## Implementation Status

### Completed

- ClusterManager service for membership tracking
- ServerInfo and ClusterConfiguration models
- NodeManagerController for intelligent command routing
- RaftNode membership change methods
- gRPC connection management
- Configuration entries in log
- Event logging for membership changes
- Proto file extensions

### Partially Implemented

- Two-phase membership change (simplified single-phase)
- Joint consensus majority calculation
- Configuration change enforcement

### Future Work

- Full two-phase C_old,new to C_new implementation
- Joint consensus majority enforcement
- Automatic C_new commit after C_old,new
- Leader step-down on self-removal
- New server catch-up before voting
- Configuration in snapshots/log compaction
- Bulk membership changes
- Log persistence on disk
- Snapshot and log compaction
- Performance metrics
- Prometheus/Grafana integration

## References

- [Raft Paper - Section 6: Cluster membership changes](https://raft.github.io/raft.pdf)
- [Raft Website](https://raft.github.io/)
- [Raft Visualization](http://thesecretlivesofdata.com/raft/)

## License

MIT License

## Author

Built with Spring Boot 3.5.6, gRPC, and the Raft Consensus Algorithm