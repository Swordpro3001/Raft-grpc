package com.example.raftimplementation.controller;

import com.example.raftimplementation.model.RaftEvent;
import com.example.raftimplementation.service.RaftNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RaftController {
    
    private final RaftNode raftNode;
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(raftNode.getStatus());
    }
    
    @PostMapping("/command")
    public ResponseEntity<Map<String, Object>> submitCommand(@RequestBody Map<String, String> request) {
        String command = request.get("command");
        
        if (command == null || command.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "Command is required"));
        }
        
        boolean success = raftNode.submitCommand(command);
        
        if (success) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Command submitted successfully"
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Not a leader. Current leader: " + raftNode.getCurrentLeader()
            ));
        }
    }
    
    @GetMapping("/events")
    public ResponseEntity<List<RaftEvent>> getEvents() {
        return ResponseEntity.ok(raftNode.getEvents());
    }
}
