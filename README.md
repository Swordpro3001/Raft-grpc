# Raft Consensus Implementation with Spring Boot and gRPC

![CI Tests](https://github.com/Swordpro3001/Raft-grpc/actions/workflows/test.yml/badge.svg)
![Code Quality](https://github.com/Swordpro3001/Raft-grpc/actions/workflows/code-quality.yml/badge.svg)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A complete implementation of the Raft Consensus Algorithm using Spring Boot and gRPC for local execution.

## Features

* ✅ Full Raft implementation (Leader Election, Log Replication, Commit)
* ✅ gRPC communication between nodes
* ✅ 3 locally running nodes (Ports 8081–8083)
* ✅ Web dashboard for visualizing node states
* ✅ REST API for command input
* ✅ State machine for applied commands

## Architecture

```
┌─────────────┐     gRPC      ┌─────────────┐     gRPC      ┌─────────────┐
│   Node 1    │◄──────────────►│   Node 2    │◄──────────────►│   Node 3    │
│  (Leader)   │                │ (Follower)  │                │ (Follower)  │
│  Port 8081  │                │  Port 8082  │                │  Port 8083  │
│  gRPC 9091  │                │  gRPC 9092  │                │  gRPC 9093  │
└─────────────┘                └─────────────┘                └─────────────┘
       │                              │                              │
       │                              │                              │
       └──────────────────────────────┴──────────────────────────────┘
                                      │
                                      ▼
                              ┌──────────────┐
                              │ Web Dashboard│
                              │ (index.html) │
                              └──────────────┘
```

## Raft Components

### Node States

* **FOLLOWER**: Default state, responds to leader heartbeats
* **CANDIDATE**: Initiates leader election
* **LEADER**: Accepts client requests and replicates logs

### gRPC Services

1. **RequestVote** – for leader elections
2. **AppendEntries** – for heartbeats and log replication

### Persistent State

* `currentTerm`: Current election term
* `votedFor`: Candidate voted for in current term
* `log[]`: Log entries with client commands

## Quick Start

### Requirements

* Java 21
* Gradle (or use the included Gradle Wrapper)

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

The `index.html` in should open automaticly in the browser or open:

* [http://localhost:8081/index.html](http://localhost:8081/index.html)

## API Endpoints

### Get Status

```bash
GET http://localhost:8081/api/status
GET http://localhost:8082/api/status
GET http://localhost:8083/api/status
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

### Send Command

```bash
POST http://localhost:8081/api/command
Content-Type: application/json

{
  "command": "SET key=value"
}
```

Success response:

```json
{
  "success": true,
  "message": "Command submitted successfully"
}
```

Response when not leader:

```json
{
  "success": false,
  "message": "Not a leader. Current leader: node2"
}
```

## Using the Dashboard

1. Open `index.html` in your browser
2. Wait until a leader is elected (automatically after a few seconds)
3. Enter a command, e.g.:

   * `SET username=john`
   * `ADD item123`
   * `UPDATE counter=42`
4. Click **“Send Command”**
5. Watch as the command is replicated to all nodes

## Testing Raft Features

### 1. Test Leader Election

```bash
# Stop the current leader (e.g., Node 1)
# Observe how a new leader is elected
```

### 2. Test Log Replication

```bash
# Send several commands via the dashboard
# Watch how all nodes receive identical log entries
```

### 3. Simulate Network Partition

```bash
# Stop 2 out of 3 nodes
# The remaining node cannot become leader (no majority)
# Restart one node - a new leader election will occur
```

## Project Structure

```
src/
├── main/
│   ├── java/com/example/raftimplementation/
│   │   ├── config/
│   │   │   └── RaftConfig.java          # Node and peer configuration
│   │   ├── controller/
│   │   │   └── RaftController.java      # REST API controller
│   │   ├── grpc/
│   │   │   └── RaftGrpcService.java     # gRPC service implementation
│   │   ├── model/
│   │   │   ├── LogEntry.java            # Log entry model
│   │   │   └── NodeState.java           # Node state enum
│   │   └── service/
│   │       └── RaftNode.java            # Core Raft logic
│   ├── proto/
│   │   └── raft.proto                   # gRPC protocol buffer definition
│   └── resources/
│       ├── application-node1.properties  # Config for Node 1
│       ├── application-node2.properties  # Config for Node 2
│       └── application-node3.properties  # Config for Node 3
├── test/
└── index.html                           # Web dashboard
```

## Configuration

Each node has its own configuration file (`application-node*.properties`):

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

## Timeouts and Tuning

In `RaftNode.java`, you can adjust these values:

```java
private static final int ELECTION_TIMEOUT_MIN = 3000;  // 3 seconds
private static final int ELECTION_TIMEOUT_MAX = 5000;  // 5 seconds
private static final int HEARTBEAT_INTERVAL = 1000;    // 1 second
```

## Debugging

Adjust logging level in `application-node*.properties`:

```properties
# Detailed logging
logging.level.com.example.raftimplementation=DEBUG

# gRPC logging
logging.level.io.grpc=DEBUG
```

## Troubleshooting

### Problem: Nodes do not connect

* Check that all ports (8081–8083, 9091–9093) are free
* Check firewall settings
* Inspect logs for each node

### Problem: No leader is elected

* Wait at least 5–10 seconds
* Ensure at least 2 out of 3 nodes are running
* Check logs for errors

### Problem: Commands are not replicated

* Ensure the node is actually the leader
* Look for “AppendEntries” messages in logs
* Check the `commitIndex` of all nodes

## Advanced Features (Optional)

Possible extensions:

* [ ] Log persistence on disk
* [ ] Snapshot and log compaction
* [ ] Dynamic membership changes
* [ ] Performance metrics
* [ ] Prometheus/Grafana integration

## License

MIT License

## Author

Built with Spring Boot 3.5.6, gRPC, and the Raft Consensus Algorithm


[Schnellstart](SCHNELLSTART.md)

[Troubleshooting](TROUBLESHOOTING.md)