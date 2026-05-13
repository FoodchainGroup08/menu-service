package com.microservices.menu.security;

/**
 * Headers forwarded by the API gateway after JWT validation.
 */
public final class GatewayRoleHelper {

    private GatewayRoleHelper() {}

    public static boolean isHeadOfficeAdminRole(String userRole) {
        if (userRole == null || userRole.isBlank()) {
            return false;
        }
        String r = userRole.trim();
        return "HEAD_OFFICE_ADMIN".equalsIgnoreCase(r)
                || "OFFICE_ADMIN".equalsIgnoreCase(r)
                || "Admin".equalsIgnoreCase(r);
    }
}
