# Node Management - Fehlerbehebung und Debug

## Problem: "Failed to stop node (not running)"

### Ursache:
Die Fehlermeldung erscheint, wenn:
1. Die Node tatsächlich nicht läuft
2. Der Port-Check fehlschlägt
3. Die Process-ID nicht gefunden werden kann

### Diagnose:

#### 1. Prüfen Sie, ob Nodes tatsächlich laufen:
```powershell
# Alle Java-Prozesse anzeigen
Get-Process -Name java

# Ports prüfen
netstat -ano | findstr LISTENING | findstr ":808"
```

**Erwartete Ausgabe wenn Nodes laufen:**
```
TCP    0.0.0.0:8081    0.0.0.0:0    LISTENING    12345
TCP    0.0.0.0:8082    0.0.0.0:0    LISTENING    12346
```

#### 2. Test der Management API:
```powershell
# Status aller Nodes abrufen
curl http://localhost:8081/api/nodes/status

# Erwartete Ausgabe:
# {"node1":true,"node2":false,"node3":false,"node4":false,"node5":false}
```

### Verbesserte Funktionen (jetzt implementiert):

1. **Robustere Port-Prüfung:**
   - Filtert jetzt explizit nach "LISTENING" Status
   - Besseres Logging für Debug-Zwecke

2. **Bessere Fehlerbehandlung:**
   - Detaillierte Log-Ausgaben in der Konsole
   - Genauere Fehlermeldungen

3. **Debug-Logging:**
   - Zeigt PID und Port-Status an
   - Hilft bei der Fehlersuche

## Test-Szenarios:

### Szenario 1: Node über Dashboard starten und stoppen
```
1. Öffne index.html
2. Klicke "Start" bei node2
3. Warte 10 Sekunden
4. Node2 sollte als "RUNNING" angezeigt werden
5. Klicke "Stop" bei node2
6. Node2 sollte als "OFFLINE" angezeigt werden
```

### Szenario 2: Node manuell starten, dann über Dashboard stoppen
```powershell
# Terminal 1: Node2 manuell starten
.\gradlew.bat bootRun --args="--spring.profiles.active=node2"

# Terminal 2: Prüfen ob Port belegt
netstat -ano | findstr LISTENING | findstr ":8082"

# Dashboard: Klicke "Stop" bei node2
# -> Node2 sollte gestoppt werden!
```

### Szenario 3: Mehrere Nodes gleichzeitig verwalten
```
1. Start Node1 (Management Node)
2. Über Dashboard: Start Node2, Node3, Node4
3. Warte bis Leader gewählt wurde
4. Stoppe einen Follower -> Leader bleibt bestehen
5. Stoppe den Leader -> Neue Election
```

## Häufige Fehler und Lösungen:

### Fehler: "Failed to stop node (not running)"
**Lösung 1:** Node läuft tatsächlich nicht
```powershell
# Prüfen:
netstat -ano | findstr LISTENING | findstr ":8082"
# Wenn keine Ausgabe -> Node läuft nicht
```

**Lösung 2:** NodeManager erkennt Node nicht
```powershell
# Status-API direkt aufrufen:
curl http://localhost:8081/api/nodes/status

# Logs prüfen in der Node1-Konsole
# Suche nach: "Port 8082 check: IN USE" oder "FREE"
```

**Lösung 3:** Firewall blockiert netstat
```powershell
# Als Administrator ausführen oder Firewall-Regel hinzufügen
```

### Fehler: Node startet nicht
```powershell
# Prüfe ob Port schon belegt:
netstat -ano | findstr LISTENING | findstr ":8082"

# Wenn belegt, finde PID (letzte Spalte) und beende:
taskkill /F /PID <pid>
```

### Fehler: Dashboard zeigt falsche Status
```powershell
# Browser-Cache leeren (Strg + F5)
# Oder warte 2 Sekunden auf Auto-Refresh
```

## Manuelles Testen der Stop-Funktion:

### Test 1: Stop via API
```powershell
# Node2 manuell starten
start cmd /k "gradlew.bat bootRun --args=--spring.profiles.active=node2"

# Warten...
timeout /t 10

# Via API stoppen
curl -X POST http://localhost:8081/api/nodes/node2/stop

# Ergebnis prüfen
curl http://localhost:8081/api/nodes/status
```

### Test 2: Stop via Dashboard
1. Öffne `index.html`
2. Node sollte als "RUNNING" mit grünem Stop-Button angezeigt werden
3. Klicke "⏹ Stop"
4. Success-Meldung sollte erscheinen
5. Node wird nach 1 Sekunde als "OFFLINE" angezeigt

## Wichtige Log-Meldungen:

### Erfolgreiche Erkennung:
```
DEBUG c.e.r.service.NodeManagerService : Port 8082 check: IN USE (line: TCP    0.0.0.0:8082 ...)
DEBUG c.e.r.service.NodeManagerService : Found PID 12346 for port 8082
INFO  c.e.r.service.NodeManagerService : Found process 12346 on port 8082. Killing...
INFO  c.e.r.service.NodeManagerService : Node node2 (PID: 12346) stopped successfully
```

### Node läuft nicht:
```
DEBUG c.e.r.service.NodeManagerService : Port 8082 check: FREE (line: null)
WARN  c.e.r.service.NodeManagerService : Node node2 is not running
```

## Advanced: Debugging

### Logs in Echtzeit anschauen:
Die Node1-Konsole zeigt alle Debug-Informationen an. Achten Sie auf:
- Port-Checks
- PID-Ermittlung
- Stop/Start-Operationen

### Manuelles Testen der Hilfsfunktionen:
```powershell
# Test isPortInUse
netstat -ano | findstr LISTENING | findstr ":8082"

# Test getProcessIdByPort
$output = netstat -ano | findstr LISTENING | findstr ":8082"
# PID ist die letzte Spalte
$pid = ($output -split '\s+')[-1]
Write-Host "PID: $pid"

# Test killProcessByPid
taskkill /F /PID $pid
```

## Empfohlener Test-Workflow:

1. **Starte Node1:**
   ```powershell
   .\gradlew.bat bootRun --args="--spring.profiles.active=node1"
   ```

2. **Öffne Dashboard:**
   - Öffne `index.html` im Browser
   - Alle Nodes sollten als "OFFLINE" angezeigt werden

3. **Starte Node2 über Dashboard:**
   - Klicke "▶ Start" bei node2
   - Warte 10-15 Sekunden
   - Node2 wird als "CANDIDATE" oder "FOLLOWER" angezeigt

4. **Stoppe Node2 über Dashboard:**
   - Klicke "⏹ Stop" bei node2
   - Success-Meldung erscheint
   - Node2 wird nach 1-2 Sekunden als "OFFLINE" angezeigt

5. **Wiederhole für Node3, Node4:**
   - Teste Start/Stop für jede Node
   - Beobachte Leader-Election

## Support:

Bei weiteren Problemen:
1. Prüfen Sie die Node1-Konsole auf Fehler
2. Führen Sie die Diagnose-Befehle aus
3. Prüfen Sie, ob Firewall/Antivirus blockiert
4. Starten Sie alle Nodes neu mit: `taskkill /F /IM java.exe`
