# Troubleshooting

Each node writes its log to `logs/nodeN.log`. Start there.

## `Unsupported class file major version`

Gradle cannot run on your newest JDK. The project pins Gradle's daemon to JDK 21 in `gradle/gradle-daemon-jvm.properties`, so this should only happen with a very old checkout. If it still appears, install JDK 21 and run `gradlew --stop`.

## A node does not start: port in use

Node `nodeN` needs ports `8080+N` and `9090+N`, and the dashboard needs `8080`. To find out what is using a port:

```powershell
netstat -ano | findstr LISTENING | findstr ":8081"   # Windows
```

```bash
lsof -i :8081                                        # macOS, Linux
```

## No leader is elected

A cluster needs a majority of its members to be running: 3 out of 5, or 2 out of 3.

- The dashboard shows which nodes are offline or suspended.
- The Activity tab of a node shows its election attempts.
- `logs/nodeN.log` shows why a node did not start.

If you changed the cluster size, the existing data still contains the old configuration. Run `raft reset`.

## A command fails

| Result | Meaning |
|---|---|
| `NOT_LEADER` | You contacted a follower directly. Use the dashboard API on port 8080, or `/api/cluster/command` on a node. |
| `TIMEOUT` | The leader could not reach a majority in time. The command may still take effect once it can. |
| `LEADERSHIP_LOST` | The leader changed while the command was in progress. The command may or may not take effect. |
| `SUSPENDED` | The node is suspended. Resume it. |

## `Snapshot of nodeN is unreadable`

Snapshots now use protobuf. A snapshot written by an older version of this project cannot be read, and a node refuses to start without it because the log entries it covers are gone. Run `raft reset` to delete the data of all nodes, or delete only `data/nodeN` so that the node resyncs from the leader.

## A removed node comes back after a restart

The dashboard starts every node that has a directory in `data/`. A removed node only runs as "Not a member", which does not affect the cluster. Delete its `data/nodeN` directory to get rid of it.

## Reaching the nodes from another machine

All ports bind to `127.0.0.1` because the APIs have no authentication. To change this, set `raft.bind-address`, but only on a network you trust.
