package com.example.raftimplementation.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The dashboard's actions are plain form posts without authentication, and any website could
 * submit such a form to localhost. Browsers mark those requests, so they can simply be refused.
 */
@Component
@ConditionalOnProperty(name = "raft.node.enabled", havingValue = "false")
public class CrossSiteRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String site = request.getHeader("Sec-Fetch-Site");
        boolean foreign = "cross-site".equals(site) || "same-site".equals(site);
        if (HttpMethod.POST.matches(request.getMethod()) && foreign) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-site requests are not allowed");
            return;
        }
        chain.doFilter(request, response);
    }
}
