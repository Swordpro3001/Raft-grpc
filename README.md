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
* ✅ 3+ locally running nodes (Ports 8081–8083+)
* ✅ Web dashboard for visualizing node states
* ✅ REST API for command input
* ✅ State machine for applied commands
* ✅ Dynamic cluster membership management

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

* Java 17+
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
4. Click **"Send Command"**
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

## Adding a New Node to the Cluster

### Step 1: Create Application Properties

Create a new configuration file `src/main/resources/application-nodeX.properties` (e.g., `application-node6.properties`):

```properties
# Node 6 Configuration
raft.node-id=node6
raft.grpc-port=50056
raft.http-port=8086

# Server Port
server.port=8086

# Peers (all other nodes in the cluster)
raft.peers[0].node-id=node1
raft.peers[0].host=localhost
raft.peers[0].grpc-port=50051

raft.peers[1].node-id=node2
raft.peers[1].host=localhost
raft.peers[1].grpc-port=50052

raft.peers[2].node-id=node3
raft.peers[2].host=localhost
raft.peers[2].grpc-port=50053

raft.peers[3].node-id=node4
raft.peers[3].host=localhost
raft.peers[3].grpc-port=50054

raft.peers[4].node-id=node5
raft.peers[4].host=localhost
raft.peers[4].grpc-port=50055
```

**Important:** Make sure the ports are unique (gRPC and HTTP ports must not conflict with existing nodes).

### Step 2: Add Node to Cluster via Dashboard

1. Open Dashboard: http://localhost:8081
2. Go to "Cluster Management"
3. Fill in the fields:
   - **Node ID:** node6
   - **Host:** localhost
   - **gRPC Port:** 50056
   - **HTTP Port:** 8086
4. Click "Add Node"

This adds the node to the Raft configuration but does NOT start it automatically.

### Step 3: Start the Node Manually

Open a new terminal/PowerShell and run:

```bash
.\gradlew.bat bootRun --args=--spring.profiles.active=node6
```

**Or** add the node to the `start-cluster.bat`:

```bat
rem === Node 6 ===
powershell -WindowStyle Hidden -Command "Start-Process 'cmd.exe' -ArgumentList '/c gradlew.bat bootRun --args=--spring.profiles.active=node6' -WindowStyle Hidden"
```

### Step 4: Verify

1. Check the dashboard to see if node6 appears
2. Wait for synchronization (log will be replicated)
3. The node should appear as FOLLOWER

### Important Notes

* **Ports must be unique** (gRPC and HTTP ports)
* **Node ID must be unique**
* The new node **automatically receives the entire log** from the leader
* After adding, the cluster goes through **Joint Consensus** (C_old,new → C_new)
* This process takes approximately 2 heartbeat cycles (~2 seconds)

### Troubleshooting Node Addition

**"Failed to contact leader"**
- Wait for leader election (can take up to 5 seconds)
- Try again

**"Node appears but stays offline"**
- Check that the properties file is configured correctly
- Verify that the ports are available
- Start the node manually using the command from Step 3

**"LogSize explodes"**
- This issue has been fixed in this version
- Previously was a bug in configuration entry processing

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
* Look for "AppendEntries" messages in logs
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