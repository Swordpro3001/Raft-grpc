package com.example.raftimplementation.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Dashboard Controller - only active when running in client mode.
 * Serves the monitoring dashboard UI.
 */
@Controller
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "false")
public class DashboardController {
    
    @GetMapping("/")
    public String dashboard() {
        return "forward:/index.html";
    }
}
