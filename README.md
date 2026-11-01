# Raft Consensus with Spring Boot and gRPC

[![CI](https://github.com/Swordpro3001/Raft-grpc/actions/workflows/ci.yml/badge.svg)](https://github.com/Swordpro3001/Raft-grpc/actions/workflows/ci.yml)
[![Code Quality](https://github.com/Swordpro3001/Raft-grpc/actions/workflows/quality.yml/badge.svg)](https://github.com/Swordpro3001/Raft-grpc/actions/workflows/quality.yml)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

An implementation of the [Raft consensus algorithm](https://raft.github.io/raft.pdf) with a web dashboard, built for learning and experimenting on one machine.

## Quick Start

You need Java 21 or newer. Nothing else: the Gradle wrapper is included.

```bash
raft.cmd          # Windows
./raft.sh         # macOS, Linux
```

This builds the project if needed and starts the dashboard and five nodes:

```
Dashboard   http://localhost:8080
Starting    node1, node2, node3, node4, node5 (logs in logs\)
Ready       leader node2 elected after 10.5 s. Press Ctrl+C to stop.
```

Press Ctrl+C or close the terminal to stop everything. The nodes never outlive the dashboard process.

| Command | What it does |
|---|---|
| `raft start [nodes]` | Start a cluster, 5 nodes by default. This is the default command. |
| `raft reset` | Delete all node data and logs |
| `raft test` | Run the tests |

Node data stays in `data/` between runs, and each node writes its log to `logs/nodeN.log`. To change the cluster size of existing data, run `raft reset` first.

### Running nodes yourself

To run a node on its own, for example in the debugger, use its profile. In this case nothing supervises the process.

```bash
gradlew bootRun --args="--spring.profiles.active=node1"
```

Running the application without a profile, for example from your IDE, starts the dashboard and the cluster, as `raft` does.

## How It Works

```
Browser ---> Dashboard process (8080) ---REST---> node1 ... nodeN (8081 ...)
             supervises the node JVMs                 |
                                                      +--- gRPC (9091 ...) between nodes
                                                      +--- H2 database in data/nodeN
```

Node `nodeN` listens on HTTP port `8080+N` and gRPC port `9090+N`. All ports bind to `127.0.0.1` because the APIs have no authentication.

### The dashboard

The dashboard is rendered on the server with Thymeleaf. Links and forms are plain HTML, and [htmx](https://htmx.org) with idiomorph updates the live part of the page in place, every 1.5 seconds and after each action. The libraries come from WebJars, so there is no Node build and no CDN.

- **Topology** is a [Cytoscape.js](https://js.cytoscape.org) graph of all machines with their role, term and log position, and the replication links from the leader. Select a machine to inspect it. The server provides the graph data; `cluster-graph.js` only draws it.
- **Logs** shows the log of every machine, aligned by position. Each cell is an entry and shows its term. The legend explains which entries are committed, not committed, no-ops, membership changes, in a snapshot, or different from the leader's. Hover over an entry for details.

`css/app.css` is a utility-first stylesheet that uses Tailwind's class names and scale. It is hand-written, so a class that is new to the templates has to be added there too.

### Implemented from the Raft paper

| Feature | Notes |
|---|---|
| Leader election | Randomized timeouts, durable term and vote |
| Pre-vote | A server first checks that it could win, so a returning server cannot depose a healthy leader |
| Log replication | Asynchronous, one RPC in flight per peer, automatic batching, fast backtracking on conflicts |
| Commit rule | Only entries of the current term commit by counting replicas; each new leader commits a no-op |
| Persistence | Term, vote and log are durable before a node answers an RPC |
| Snapshots | Automatic log compaction, and InstallSnapshot for followers that fell behind |
| Membership changes | Joint consensus; the configuration is part of the log, and a leader can remove itself |
| Client commands | The API answers after the command is committed and applied |
| Linearizable reads | ReadIndex: the leader confirms it still has a quorum before it reads |

### Known limitations

- **Duplicate commands.** There are no client sessions. If a command times out and is sent again, it can be applied twice (paper, section 8).
- **New servers join as voters right away,** without the non-voting catch-up phase from section 6. A new server therefore counts towards the quorum while it is still catching up.
- **Snapshots go over the wire in one message** of at most 64 MB, not in chunks.
- **The state machine is a list of commands.** It demonstrates ordering and does not interpret them, and snapshots grow with it.

## API

The dashboard process routes these to the right node:

| Endpoint | Purpose |
|---|---|
| `POST /api/command` | Submit a command, for example `{"command": "SET key=value"}` |
| `GET /api/read` | Linearizable read of the state machine |
| `GET /api/cluster/overview` | Status, events and log of all nodes |
| `POST /api/cluster/add-node` | Start a new node and add it to the cluster |
| `POST /api/cluster/remove-node/{nodeId}` | Remove a node from the cluster |
| `POST /api/nodes/{nodeId}/start`, `stop` | Start or stop a node process |
| `POST /api/nodes/{nodeId}/suspend`, `resume` | Pause Raft on a node while its process keeps running |
| `POST /api/snapshot/create` | Create a snapshot on the leader |

Each node has its own API, on port `8080+N`:

| Endpoint | Purpose |
|---|---|
| `GET /api/status`, `GET /api/events` | State and recent events of this node |
| `GET /api/log?limit=100` | Latest log entries, with commit and snapshot positions |
| `POST /api/command` | Submit a command (leader only) |
| `POST /api/cluster/command` | Submit a command, forwarded to the leader if necessary |
| `GET /api/read?strict=false` | Faster read that may be stale. By default reads are linearizable. |
| `POST /api/membership/add`, `POST /api/membership/remove/{nodeId}` | Membership changes (leader only) |
| `GET /api/membership/members` | The current configuration |
| `POST /api/suspend`, `POST /api/resume` | Pause or resume Raft |
| `POST /api/snapshot/create`, `GET /api/snapshot/info` | Snapshots |
| `GET /api/metrics/performance`, `health`, `replication`, `snapshots`, `events` | Metrics |

A command returns one of these results: `COMMITTED`, `NOT_LEADER`, `SUSPENDED`, `LEADERSHIP_LOST` or `TIMEOUT`. After the last two, the command may or may not take effect.

## Configuration

Set these as `--name=value` arguments or in `application.properties`:

| Property | Default | Purpose |
|---|---|---|
| `raft.cluster-size` | 5 | Size of the initial cluster, unless `raft.peers` lists it explicitly |
| `raft.election-timeout-min-ms` | 1500 | Lower bound of the randomized election timeout |
| `raft.election-timeout-max-ms` | 3000 | Upper bound of the randomized election timeout |
| `raft.heartbeat-interval-ms` | 500 | Heartbeat interval of the leader |
| `raft.rpc-timeout-ms` | 1000 | Deadline for RPCs between nodes |
| `raft.command-timeout-ms` | 5000 | How long a client waits for a commit |
| `raft.snapshot-threshold` | 100 | Log size that triggers a snapshot |
| `raft.local.autostart` | true | Whether the dashboard starts the nodes |

## Project Structure

```
raft.cmd, raft.sh              Start, reset and test commands
src/main/proto/raft.proto      gRPC protocol
src/main/java/.../
    service/RaftNode           The Raft algorithm
    service/NodeManagerService Local cluster supervisor
    service/ClusterClient      The dashboard process's access to the nodes
    dashboard/DashboardView    Everything the dashboard shows, ready for the templates
    grpc/                      gRPC server and protobuf conversion
    controller/                REST APIs of the nodes and the dashboard
    persistence/               Durable state, log and snapshots
src/main/resources/templates/  Dashboard pages (Thymeleaf)
src/main/resources/static/     Stylesheet and graph script
src/test/java/.../
    service/RaftClusterIntegrationTest   Real nodes over real gRPC
    service/RaftSafetyRegressionTest     Protocol rules, one test per rule
```

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) if something does not work.

## CI and Releases

| Workflow | Runs on | What it does |
|---|---|---|
| `ci.yml` | Pull requests, pushes to main, on demand | Tests and a smoke test of the packaged jar. Pushes to main and releases also run them on Windows and macOS. |
| `quality.yml` | Pull requests, pushes to main, weekly | CodeQL analysis, and the dependency graph for Dependabot alerts |
| `release.yml` | Version tags | Publishes a GitHub release |

`.github/scripts/smoke-test.sh` runs on your machine too: it starts the jar, and checks failover, snapshots and recovery after a crash. It uses the same ports as the cluster, so stop any running cluster first.

### Publishing a release

Tag a commit on main and push the tag:

```bash
git tag -a v1.2.0 -m "Version 1.2.0"
git push origin v1.2.0
```

This runs all checks on all platforms, then publishes `raft-1.2.0.jar` with its SHA-256 checksum and generated release notes. A suffix like `v1.2.0-rc.1` makes it a prerelease. Tags on commits outside main, and tags of existing releases, are rejected.

## References

- [Raft paper](https://raft.github.io/raft.pdf)
- [Raft website](https://raft.github.io/)
- [Raft visualization](http://thesecretlivesofdata.com/raft/)

## License

MIT License
