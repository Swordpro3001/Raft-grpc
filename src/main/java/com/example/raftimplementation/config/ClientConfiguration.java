package com.example.raftimplementation.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration for client mode.
 * Provides basic beans needed for the dashboard client.
 */
@Configuration
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "false")
public class ClientConfiguration {
    
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
