package com.example.raftimplementation.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web configuration for Raft nodes.
 * Disables static resources when running as a Raft node (only REST API available).
 */
@Configuration
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "true", matchIfMissing = true)
public class NodeWebConfig implements WebMvcConfigurer {
    
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {

    }
}
