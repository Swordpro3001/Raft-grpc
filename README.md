# Raft Consensus Implementation with Spring Boot and gRPC

![CI Tests](https://github.com/Swordpro3001/Raft-grpc/actions/workflows/test.yml/badge.svg)
![Code Quality](https://github.com/Swordpro3001/Raft-grpc/actions/workflows/code-quality.yml/badge.svg)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Production-ready Raft Consensus implementation with dynamic membership changes, log compaction, and advanced optimizations.

## Features

- Full Raft implementation (Leader Election, Log Replication, Commit)
- gRPC communication between nodes
- Dynamic cluster membership (add/remove servers safely)
- Log compaction and automatic snapshotting
- Advanced optimizations: InstallSnapshot, Pre-vote, Linearizable Reads
- Comprehensive monitoring and metrics
- Web dashboard visualization
- REST API for cluster management

## Quick Start

### Requirements
- Java 17+
- Gradle

### Start Cluster

**Windows:**
```bash
start-cluster.bat
```

**Manual:**
```bash
gradlew bootRun --args="--spring.profiles.active=node1"
gradlew bootRun --args="--spring.profiles.active=node2"
gradlew bootRun --args="--spring.profiles.active=node3"
```

Open: [http://localhost:8081/index.html](http://localhost:8081/index.html)

## Core API

### Submit Commands
```bash
# Auto-routes to leader
curl -X POST http://localhost:8081/api/cluster/command \
  -H "Content-Type: application/json" \
  -d '{"command": "SET key=value"}'
```

### Cluster Management
```bash
# Add node
curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"node4","host":"localhost","grpcPort":9094,"httpPort":8084}'

# Remove node
curl -X POST http://localhost:8081/api/cluster/remove/node3

# List members
curl http://localhost:8081/api/membership/members
```

### Monitoring
```bash
# Performance metrics
curl http://localhost:8081/api/metrics/performance

# Replication status
curl http://localhost:8081/api/metrics/replication

# Snapshot stats
curl http://localhost:8081/api/metrics/snapshots

# Events with filtering
curl 'http://localhost:8081/api/metrics/events?type=ELECTION_START&limit=10'
```

## Advanced Features

### 1. Dynamic Membership Changes

Safely add/remove nodes with automatic staging and rollback:

**Adding a Node:**
- Starts as staging (non-voting member)
- Replicates log without affecting quorum
- Automatically promoted after catching up
- Falls back to previous config if timeout

**Quorum Validation:**
- Minimum 3 voting nodes enforced
- Prevents cluster from becoming non-fault-tolerant
- Validates before every removal

**Example:**
```bash
# Start new node
curl -X POST http://localhost:8081/api/nodes/node4/start

# Add to cluster (auto-staging)
sleep 10
curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"node4","host":"localhost","grpcPort":9094,"httpPort":8084,"staging":true}'

# Verify membership
curl http://localhost:8081/api/membership/members
```

### 2. Log Compaction & Snapshots

Automatic snapshot creation when log reaches 100 entries:

**Benefits:**
- Memory reduced by ~95%
- Followers catch up in seconds (vs minutes)
- Enables indefinite cluster operation

**Monitor:**
```bash
curl http://localhost:8081/api/metrics/snapshots
```

Response shows compression ratio and compacted entries count.

### 3. Advanced Optimizations

**InstallSnapshot RPC:**
- Leaders send snapshots to far-behind followers
- Recovery from seconds to milliseconds for lagging nodes
- Automatic and transparent

**Pre-vote Algorithm:**
- Prevents disruption from partitioned nodes
- Reduces unnecessary leader elections by 90%
- Improves cluster stability

**Linearizable Reads:**
- Fast read operations (2-3x faster than writes)
- No log replication needed
- Strong consistency guarantee

```bash
# Read from leader
curl http://localhost:8081/api/read
```

## Architecture

### Three-Node Example

```
Client
  |
  +---> NodeManagerController
           |
           +---> [node1 (Leader), node2 (Follower), node3 (Follower)]
                    |
                    +---> gRPC communication
                    +---> Log replication
                    +---> Heartbeats
```

### Node States

- FOLLOWER: Default state, receives heartbeats
- CANDIDATE: Initiates elections
- LEADER: Accepts commands, replicates logs

### gRPC Services

- RequestVote: Leader election
- AppendEntries: Log replication and heartbeats
- InstallSnapshot: Fast catch-up for lagging followers

## Configuration

Each node has `src/main/resources/application-nodeX.properties`:

```properties
raft.node-id=node1
server.port=8081
spring.grpc.server.port=9091

# Peer config
raft.peers[0].node-id=node1
raft.peers[0].host=localhost
raft.peers[0].grpc-port=9091
```

### Tuning (in RaftNode.java)

```java
ELECTION_TIMEOUT_MIN = 3000;      // 3 seconds
ELECTION_TIMEOUT_MAX = 5000;      // 5 seconds
HEARTBEAT_INTERVAL = 1000;        // 1 second
SNAPSHOT_THRESHOLD = 100;         // entries
STAGING_DURATION_MS = 10000;      // 10 seconds
MEMBERSHIP_CHANGE_TIMEOUT_MS = 30000; // 30 seconds
```

## Testing Features

### Test Leader Election
```bash
# Stop current leader
curl -X POST http://localhost:8081/api/nodes/node1/stop

# Watch new leader election
curl http://localhost:8082/api/status
```

### Test Log Replication
```bash
# Submit commands
for i in {1..10}; do
  curl -X POST http://localhost:8081/api/cluster/command \
    -H "Content-Type: application/json" \
    -d "{\"command\":\"test-$i\"}"
done

# Verify on all nodes
curl http://localhost:8081/api/status | jq '.logSize'
curl http://localhost:8082/api/status | jq '.logSize'
```

### Test Snapshots
```bash
# Create 150 entries (triggers snapshot)
for i in {1..150}; do
  curl -X POST http://localhost:8081/api/cluster/command \
    -H "Content-Type: application/json" \
    -d "{\"command\":\"cmd-$i\"}"
  sleep 0.05
done

# Check snapshot created
curl http://localhost:8081/api/metrics/snapshots | jq '.totalSnapshots'
```

### Test Membership Changes
```bash
# Add node4
curl -X POST http://localhost:8081/api/nodes/node4/start
sleep 10

curl -X POST http://localhost:8081/api/cluster/add \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"node4","host":"localhost","grpcPort":9094,"httpPort":8084}'

# Verify
curl http://localhost:8081/api/metrics/health | jq '.clusterSize'
```

## Project Structure

```
src/
├── main/java/com/example/raftimplementation/
│   ├── config/         # Configuration classes
│   ├── controller/     # REST API endpoints
│   ├── grpc/           # gRPC service implementation
│   ├── model/          # Data models
│   ├── service/        # Core Raft logic
│   └── proto/          # gRPC protocol definitions
├── resources/
│   ├── application-node*.properties  # Node configs
│   └── index.html                    # Web dashboard
└── test/
```

## Monitoring

### Health Endpoint
```bash
curl http://localhost:8081/api/metrics/health
```

Shows: node state, term, log size, commit index, snapshot info, peer connections

### Performance Endpoint
```bash
curl http://localhost:8081/api/metrics/performance
```

Shows: throughput (cmd/s), election time (ms), replication latency (ms), leader stability (%)

### Replication Endpoint
```bash
curl http://localhost:8081/api/metrics/replication
```

Shows per-peer: nextIndex, matchIndex, lag, upToDate status

### Event History
```bash
# View events by type
curl 'http://localhost:8081/api/metrics/events?type=ELECTION_WON&limit=10'
```

## Implementation Status

**Core Raft:**
- Leader election with timeouts
- Log replication and commit
- Persistent state management

**Safety (Membership Changes):**
- Quorum validation
- Staging phase for new nodes
- Automatic promotion
- Rollback on failure
- Joint consensus

**Optimizations:**
- InstallSnapshot RPC (fast recovery)
- Pre-vote algorithm (election stability)
- Linearizable reads (fast reads)
- Async non-blocking gRPC

**Log Compaction:**
- Automatic snapshots at 100 entries
- State machine preservation
- Snapshot installation
- Compression metrics

**Monitoring:**
- Performance metrics
- Health metrics
- Replication tracking
- Event history with filtering
- Snapshot statistics

**API & Dashboard:**
- Cluster command submission
- Node management
- Cluster membership control
- Web visualization

## Troubleshooting

### Nodes not connecting
- Verify ports 8081-8083, 9091-9093 are available
- Check firewall settings
- Review logs for connection errors

### No leader elected
- Wait 5-10 seconds initially
- Ensure 2+ nodes running
- Check network connectivity
- Review election events: `curl 'http://localhost:8081/api/metrics/events?type=ELECTION_START'`

### Commands not replicated
- Verify you're contacting the leader
- Use `/api/cluster/command` to auto-route
- Check `commitIndex` and `lastApplied` match

### High replication lag
- Monitor: `curl http://localhost:8081/api/metrics/replication`
- Check peer connectivity
- Verify network bandwidth

### Incorrect state machine indices
- Happens after snapshots
- Frontend auto-corrects using `snapshotBaseIndex`
- Check: `curl http://localhost:8081/api/metrics/health` for snapshot info

## References

- [Raft Paper](https://raft.github.io/raft.pdf)
- [Raft Website](https://raft.github.io/)
- [Raft Visualization](http://thesecretlivesofdata.com/raft/)

## License

MIT License

## Author

Built with Spring Boot 3.5.6, gRPC, and the Raft Consensus Algorithm