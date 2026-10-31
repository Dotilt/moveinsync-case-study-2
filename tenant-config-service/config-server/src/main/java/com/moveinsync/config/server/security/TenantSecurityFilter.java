package com.moveinsync.config.server.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class TenantSecurityFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String tenantHeader = httpRequest.getHeader("X-Tenant-Id");
        String roleHeader = httpRequest.getHeader("X-User-Role");
        String userHeader = httpRequest.getHeader("X-User-Id");

        UserRole role = UserRole.EMPLOYEE;
        if (roleHeader != null) {
            try {
                role = UserRole.valueOf(roleHeader.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        // System or Admin role can act globally
        if (tenantHeader == null || tenantHeader.trim().isEmpty()) {
            tenantHeader = role == UserRole.ADMIN ? "SYSTEM" : null;
        }

        SecurityContext.setContext(new SecurityContext.RequestContext(tenantHeader, role, userHeader));

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContext.clear();
        }
    }
}
