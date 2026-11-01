package com.example.raftimplementation.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stops a node started by the local cluster supervisor when the supervisor is gone,
 * however it ended, so that closing a terminal never leaves orphaned JVMs behind.
 */
@Component
@ConditionalOnProperty(name = "raft.parent-pid")
@Slf4j
public class ParentProcessWatchdog {

    @Value("${raft.parent-pid}")
    private long parentPid;

    @PostConstruct
    public void watchParent() {
        ProcessHandle.of(parentPid).ifPresentOrElse(
            parent -> parent.onExit().thenRun(this::exit),
            this::exit
        );
    }

    private void exit() {
        log.info("Supervisor process {} is gone, shutting down", parentPid);
        System.exit(0);
    }
}
