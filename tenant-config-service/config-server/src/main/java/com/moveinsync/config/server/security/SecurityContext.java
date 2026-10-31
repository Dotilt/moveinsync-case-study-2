package com.moveinsync.config.server.security;

public class SecurityContext {
    private static final ThreadLocal<RequestContext> CURRENT_CONTEXT = new ThreadLocal<>();

    public static void setContext(RequestContext context) {
        CURRENT_CONTEXT.set(context);
    }

    public static RequestContext getContext() {
        RequestContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            // Default anonymous / system admin if unauthenticated header absent
            return new RequestContext("SYSTEM", UserRole.ADMIN, "system");
        }
        return ctx;
    }

    public static void clear() {
        CURRENT_CONTEXT.remove();
    }

    public static class RequestContext {
        private final String tenantId;
        private final UserRole role;
        private final String userId;

        public RequestContext(String tenantId, UserRole role, String userId) {
            this.tenantId = tenantId;
            this.role = role != null ? role : UserRole.EMPLOYEE;
            this.userId = userId != null ? userId : "anonymous";
        }

        public String getTenantId() {
            return tenantId;
        }

        public UserRole getRole() {
            return role;
        }

        public String getUserId() {
            return userId;
        }

        public boolean hasRole(UserRole required) {
            if (this.role == UserRole.ADMIN) return true;
            if (this.role == UserRole.OPERATOR && required == UserRole.EMPLOYEE) return true;
            return this.role == required;
        }
    }
}
