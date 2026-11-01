package com.example.raftimplementation.controller;

import com.example.raftimplementation.dashboard.DashboardView;
import com.example.raftimplementation.service.ClusterClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Server-rendered dashboard. Links and forms work as plain HTML: each action redirects back to the
 * page. htmx only makes this smoother, by asking for the part of the page that changes.
 */
@Controller
@RequiredArgsConstructor
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "false")
public class DashboardController {

    private static final int READ_ENTRIES_SHOWN = 5;

    public record Notice(String text, boolean error) { }
    public record ReadResult(String summary, List<DashboardView.EntryRow> entries) { }

    private final ClusterClient cluster;
    private final ObjectMapper objectMapper;

    @GetMapping("/")
    public String dashboard(@RequestParam(required = false) String node,
                            @RequestParam(required = false) String tab,
                            @RequestHeader(name = "HX-Request", required = false) String htmxRequest,
                            @RequestHeader(name = "HX-History-Restore-Request", required = false) String historyRestore,
                            Model model) {
        DashboardView view = DashboardView.from(cluster.getOverview(), node, tab);
        model.addAttribute("view", view);
        model.addAttribute("graphJson", objectMapper.writeValueAsString(view.graph()));
        return htmxRequest != null && historyRestore == null ? "fragments/update" : "dashboard";
    }

    @PostMapping("/actions/command")
    public String command(@RequestParam(required = false) String command,
                          @RequestParam(defaultValue = "send") String action,
                          @RequestParam(required = false) String node,
                          @RequestParam(required = false) String tab,
                          RedirectAttributes redirect) {
        if ("read".equals(action)) {
            Map<String, Object> result = cluster.read(null);
            if (Boolean.TRUE.equals(result.get("success"))) {
                redirect.addFlashAttribute("readResult", toReadResult(result));
            } else {
                notice(redirect, result);
            }
        } else {
            notice(redirect, cluster.submitCommand(command));
        }
        return backToDashboard(node, tab, redirect);
    }

    @PostMapping("/actions/snapshot")
    public String snapshot(@RequestParam(required = false) String node,
                           @RequestParam(required = false) String tab,
                           RedirectAttributes redirect) {
        notice(redirect, cluster.createSnapshot());
        return backToDashboard(node, tab, redirect);
    }

    @PostMapping("/actions/add-node")
    public String addNode(@RequestParam(required = false) String node,
                          @RequestParam(required = false) String tab,
                          RedirectAttributes redirect) {
        notice(redirect, cluster.addNode());
        return backToDashboard(node, tab, redirect);
    }

    @PostMapping("/actions/nodes/{nodeId}/{action}")
    public String nodeAction(@PathVariable String nodeId,
                             @PathVariable String action,
                             @RequestParam(required = false) String tab,
                             RedirectAttributes redirect) {
        Map<String, Object> result = switch (action) {
            case "start" -> cluster.startNode(nodeId);
            case "stop" -> cluster.stopNode(nodeId);
            case "suspend" -> cluster.suspendNode(nodeId);
            case "resume" -> cluster.resumeNode(nodeId);
            case "remove" -> cluster.removeNode(nodeId);
            default -> Map.of("success", false, "message", "Unknown action: " + action);
        };
        notice(redirect, result);
        return backToDashboard(nodeId, tab, redirect);
    }

    private static void notice(RedirectAttributes redirect, Map<String, Object> result) {
        redirect.addFlashAttribute("notice",
            new Notice(String.valueOf(result.get("message")), !Boolean.TRUE.equals(result.get("success"))));
    }

    private static String backToDashboard(String node, String tab, RedirectAttributes redirect) {
        if (node != null) {
            redirect.addAttribute("node", node);
        }
        if (tab != null) {
            redirect.addAttribute("tab", tab);
        }
        return "redirect:/";
    }

    @SuppressWarnings("unchecked")
    private static ReadResult toReadResult(Map<String, Object> result) {
        List<String> stateMachine = (List<String>) result.get("stateMachine");
        List<DashboardView.EntryRow> entries = new java.util.ArrayList<>();
        for (int i = stateMachine.size() - 1; i >= Math.max(0, stateMachine.size() - READ_ENTRIES_SHOWN); i--) {
            entries.add(new DashboardView.EntryRow(i + 1, stateMachine.get(i)));
        }
        String commands = stateMachine.size() == 1 ? " command" : " commands";
        return new ReadResult("Linearizable read from " + result.get("leader") + ": " + stateMachine.size() + commands, entries);
    }
}
