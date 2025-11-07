package com.example.raftimplementation.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web configuration for client mode.
 * Enables static resources (HTML, CSS, JS) only when running as a client.
 */
@Configuration
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "false")
public class ClientWebConfig implements WebMvcConfigurer {
    
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(0); 
    }
}
