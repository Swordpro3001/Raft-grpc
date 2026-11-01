package com.example.raftimplementation.controller;

import com.example.raftimplementation.config.CrossSiteRequestFilter;
import com.example.raftimplementation.service.ClusterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Renders the real templates.
 */
@WebMvcTest(controllers = DashboardController.class, properties = "raft.node.enabled=false")
@Import(CrossSiteRequestFilter.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ClusterClient cluster;

    @BeforeEach
    void setUp() {
        Map<String, Object> node = new HashMap<>();
        node.put("nodeId", "node1");
        node.put("state", "LEADER");
        node.put("currentTerm", 2);
        node.put("commitIndex", 1);
        node.put("logSize", 1);
        node.put("member", true);
        node.put("members", List.of("node1"));
        node.put("events", List.of());
        node.put("stateMachine", List.of("<script>alert(1)</script>"));
        node.put("log", Map.of("lastIndex", 0, "commitIndex", 0, "lastApplied", 0, "snapshotIndex", -1,
            "entries", List.of(Map.of("index", 0, "term", 2, "type", "COMMAND", "command", "<img src=x>"))));
        when(cluster.getOverview()).thenReturn(Map.of("nodes", List.of(node), "leader", "node1"));
    }

    @Test
    void plainRequestGetsTheWholePage() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<!DOCTYPE html>")))
            .andExpect(content().string(containsString("id=\"cluster-graph\"")))
            .andExpect(content().string(containsString("Leader node1, term 2")));
    }

    @Test
    void htmxRequestOnlyGetsTheLivePartAndOutOfBandUpdates() throws Exception {
        mvc.perform(get("/").header("HX-Request", "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("<!DOCTYPE html>"))))
            .andExpect(content().string(not(containsString("id=\"cluster-graph\""))))
            .andExpect(content().string(containsString("id=\"state\"")))
            .andExpect(content().string(matchesRegex("(?s).*id=\"health\"[^>]*hx-swap-oob=\"true\".*")))
            .andExpect(content().string(matchesRegex("(?s).*id=\"graph-data\"[^>]*data-graph=.*")));
    }

    @Test
    void historyRestoreGetsTheWholePage() throws Exception {
        mvc.perform(get("/").header("HX-Request", "true").header("HX-History-Restore-Request", "true"))
            .andExpect(content().string(containsString("<!DOCTYPE html>")));
    }

    @Test
    void commandsAreEscaped() throws Exception {
        mvc.perform(get("/").param("tab", "entries"))
            .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
            .andExpect(content().string(not(containsString("<img src=x>"))))
            .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")));
    }

    @Test
    void sendingACommandRedirectsBackWithANotice() throws Exception {
        when(cluster.submitCommand("SET x=1")).thenReturn(Map.of("success", true, "message", "Command committed"));

        mvc.perform(post("/actions/command").param("command", "SET x=1").param("node", "node1").param("tab", "entries"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/?node=node1&tab=entries"))
            .andExpect(flash().attribute("notice", new DashboardController.Notice("Command committed", false)));
    }

    @Test
    void readShowsTheLatestCommands() throws Exception {
        when(cluster.read(null)).thenReturn(Map.of("success", true, "leader", "node1", "stateMachine", List.of("a", "b")));

        mvc.perform(post("/actions/command").param("action", "read"))
            .andExpect(flash().attributeExists("readResult"));
        verify(cluster, never()).submitCommand(any());
    }

    @Test
    void nodeActionsAreRouted() throws Exception {
        when(cluster.stopNode("node2")).thenReturn(Map.of("success", true, "message", "node2 stopped"));

        mvc.perform(post("/actions/nodes/node2/stop"))
            .andExpect(redirectedUrl("/?node=node2"));
        verify(cluster).stopNode("node2");
    }

    @Test
    void crossSitePostsAreRefused() throws Exception {
        mvc.perform(post("/actions/nodes/node1/stop").header("Sec-Fetch-Site", "cross-site"))
            .andExpect(status().isForbidden());
        verify(cluster, never()).stopNode(any());
    }

    @Test
    void sameOriginPostsAreAllowed() throws Exception {
        when(cluster.stopNode("node1")).thenReturn(Map.of("success", true, "message", "stopped"));

        mvc.perform(post("/actions/nodes/node1/stop").header("Sec-Fetch-Site", "same-origin"))
            .andExpect(status().is3xxRedirection());
    }
}
