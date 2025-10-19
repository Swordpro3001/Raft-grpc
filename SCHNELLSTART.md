
---

# Raft Consensus Implementation - Schnellstart

## Was wurde implementiert?

* **Raft Consensus Algorithmus** (Leader Election, Log Replication, State Machine)
* **gRPC Kommunikation** zwischen den Nodes
* **3 lokale Nodes** (Ports 8081-8083, gRPC 9091-9093)
* **Web Dashboard** zur Visualisierung (`index.html`)
* **REST API** für Command-Eingabe

## Wie starte ich das System?

### Option 1: Automatisches Starten (Empfohlen)

**Windows:**

```bash
start-cluster.bat
```

Das Skript:

1. Baut das Projekt
2. Startet alle 3 Nodes in separaten Fenstern
3. Öffnet das Dashboard

### Option 2: Manuell starten

Öffne **3 separate PowerShell/CMD-Fenster** und führe in jedem aus:

**Fenster 1 - Node 1:**

```bash
.\gradlew.bat bootRun --args="--spring.profiles.active=node1"
```

**Fenster 2 - Node 2:**

```bash
.\gradlew.bat bootRun --args="--spring.profiles.active=node2"
```

**Fenster 3 - Node 3:**

```bash
.\gradlew.bat bootRun --args="--spring.profiles.active=node3"
```

## Dashboard öffnen

Öffne die Datei `index.html` in deinem Browser (einfach doppelklicken).

Das Dashboard zeigt:

* **Node States** (LEADER, FOLLOWER, CANDIDATE)
* **Current Term** und **Leader**
* **Commit Index** und **Log Size**
* **State Machine** - alle angewendeten Commands

## Commands testen

### Im Dashboard:

1. Warte bis ein Leader gewählt wurde (~5-10 Sekunden)
2. Gib einen Command ein, z.B.:

   * `SET username=john`
   * `ADD item123`
   * `UPDATE counter=42`
3. Klicke "Command senden"
4. Beobachte wie der Command auf alle Nodes repliziert wird!

### Via REST API (curl/Postman):

```bash
# Status abrufen
curl http://localhost:8081/api/status
curl http://localhost:8082/api/status
curl http://localhost:8083/api/status

# Command senden (automatisch an Leader)
curl -X POST http://localhost:8081/api/command \
  -H "Content-Type: application/json" \
  -d '{"command": "SET x=100"}'
```

## Raft Features testen

### 1. Leader Election

* Stoppe den Leader-Node (Strg+C im Terminal)
* Beobachte im Dashboard wie ein neuer Leader gewählt wird
* Die verbleibenden 2 Nodes erreichen Majority (2/3)

### 2. Log Replication

* Sende mehrere Commands über das Dashboard
* Alle Nodes zeigen die gleichen Commands in der State Machine
* `commitIndex` steigt bei allen Nodes

### 3. Follower Fault Tolerance

* Stoppe einen Follower-Node
* Commands funktionieren weiterhin (Leader + 1 Follower = Majority)
* Starte den Node neu - er synchronisiert automatisch

### 4. Split Brain Prevention

* Stoppe 2 von 3 Nodes
* Der verbleibende Node kann KEIN Leader werden (keine Majority)
* Commands werden abgelehnt
* Starte einen Node wieder - Leader Election erfolgt

## Logs anschauen

In den Terminal-Fenstern siehst du:

```
INFO ... RaftNode : Election timeout! Starting election...
INFO ... RaftNode : Node node1 starting election for term 5
INFO ... RaftNode : Received vote from node2 (2/2)
INFO ... RaftNode : Node node1 became LEADER for term 5
INFO ... RaftNode : Leader received command: SET x=1
```

## Projektstruktur

```
src/main/
├── java/.../
│   ├── config/
│   │   └── RaftConfig.java          # Node-Konfiguration
│   ├── controller/
│   │   └── RaftController.java      # REST API
│   ├── grpc/
│   │   └── RaftGrpcService.java     # gRPC Service
│   ├── model/
│   │   ├── LogEntry.java            # Log Entry
│   │   └── NodeState.java           # LEADER/FOLLOWER/CANDIDATE
│   └── service/
│       └── RaftNode.java            # Raft-Logik
├── proto/
│   └── raft.proto                   # gRPC Definition
└── resources/
    ├── application-node1.properties  # Node 1 Config
    ├── application-node2.properties  # Node 2 Config
    └── application-node3.properties  # Node 3 Config
```

## Konfiguration

Jede Node hat eigene Config (`application-node*.properties`):

```properties
# Node Identity
raft.node-id=node1
server.port=8081              # HTTP REST API
spring.grpc.server.port=9091  # gRPC Port

# Cluster Peers
raft.peers[0].node-id=node1
raft.peers[0].host=localhost
raft.peers[0].grpc-port=9091
# ... weitere Peers
```

### Timeouts anpassen (in `RaftNode.java`):

```java
ELECTION_TIMEOUT_MIN = 3000   // Min Timeout für Election (ms)
ELECTION_TIMEOUT_MAX = 5000   // Max Timeout (randomisiert)
HEARTBEAT_INTERVAL = 1000     // Leader-Heartbeat Interval
```

## Troubleshooting

### "UNAVAILABLE: io exception" Fehler

* Warte 5-10 Sekunden nach dem Start
* gRPC-Verbindungen brauchen Zeit zum Aufbau
* Prüfe ob alle 3 Nodes gestartet sind

### Kein Leader wird gewählt

* Mindestens 2 von 3 Nodes müssen laufen (Majority)
* Warte 5-10 Sekunden
* Prüfe die Logs auf Fehler

### Ports bereits belegt

* Prüfe mit `netstat -ano | findstr "8081"`
* Stoppe alte Prozesse oder ändere Ports in Config-Dateien

### Dashboard zeigt "OFFLINE"

* Node ist nicht erreichbar
* Prüfe ob Node läuft: `curl http://localhost:8081/api/status`

## Wie funktioniert Raft?

### States

* **FOLLOWER**: Normaler State, hört auf Leader-Heartbeats
* **CANDIDATE**: Startet Election wenn kein Heartbeat kommt
* **LEADER**: Akzeptiert Client-Commands, repliziert an Followers

### Leader Election

1. Nach Timeout wird Follower zum Candidate
2. Candidate erhöht Term und requested Votes
3. Bei Majority wird er Leader
4. Sendet Heartbeats an alle Followers

### Log Replication

1. Leader nimmt Command entgegen
2. Fügt Entry zu lokalem Log hinzu
3. Repliziert Entry über AppendEntries an Followers
4. Bei Majority-Bestätigung wird Entry committed
5. Alle Nodes wenden committed Entries auf State Machine an

## Nächste Schritte

Mögliche Erweiterungen:

* [ ] **Persistierung**: Log auf Disk speichern
* [ ] **Snapshots**: Log Compaction implementieren
* [ ] **Dynamic Membership**: Nodes zur Laufzeit hinzufügen/entfernen
* [ ] **Read-Only Queries**: Direkt von Followers lesen
* [ ] **Metrics**: Prometheus/Grafana Integration
