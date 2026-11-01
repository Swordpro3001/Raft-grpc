package com.example.raftimplementation.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class AppConfig {

    /**
     * A node that is down must fail fast instead of blocking the caller.
     * The read timeout has to outlast the commit timeout of a forwarded command.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .connectTimeout(Duration.ofMillis(500))
            .readTimeout(Duration.ofSeconds(8))
            .build();
    }
}
