
package com.logiplatform.model;

/**
 * Read-only role definition used by the administration API.
 *
 * Roles are stored directly as VARCHAR values in users.role.
 * This class is therefore intentionally not annotated with @Entity.
 */
public final class Role {

    private final RoleName roleName;
    private final String displayName;
    private final String description;

    public Role(
            RoleName roleName,
            String displayName,
            String description) {

        this.roleName = roleName;
        this.displayName = displayName;
        this.description = description;
    }

    public RoleName getRoleName() {
        return roleName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static Role from(RoleName roleName) {

        return new Role(
                roleName,
                roleName.getDisplayName(),
                roleName.getDescription());
    }
}
