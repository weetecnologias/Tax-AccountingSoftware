package com.taxpilot.users;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    public record UserDto(UUID id, String email, String fullName, boolean active, boolean mustChangePassword,
                          boolean locked, Instant lastLoginAt, Set<String> roles, Instant createdAt, long version) {
        public static UserDto of(User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.isActive(), u.isMustChangePassword(),
                    u.isLocked(Instant.now()), u.getLastLoginAt(), u.roleCodes(), u.getCreatedAt(), u.getVersion());
        }
    }

    /** Minimal user reference for pickers and record pages. */
    public record UserRef(UUID id, String fullName, String email) {
        public static UserRef of(User u) {
            return u == null ? null : new UserRef(u.getId(), u.getFullName(), u.getEmail());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank String temporaryPassword,
            @NotEmpty Set<String> roles) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 200) String fullName,
            boolean active,
            @NotEmpty Set<String> roles,
            long version) {
    }

    public record ResetPasswordRequest(@NotBlank String temporaryPassword) {
    }

    public record PermissionDto(String code, String module, String description) {
        public static PermissionDto of(Permission p) {
            return new PermissionDto(p.getCode(), p.getModule(), p.getDescription());
        }
    }

    public record RoleDto(UUID id, String code, String name, String description, boolean systemRole,
                          List<String> permissions, long version) {
        public static RoleDto of(Role r) {
            return new RoleDto(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.isSystemRole(),
                    r.getPermissions().stream().map(Permission::getCode).sorted(Comparator.naturalOrder()).toList(),
                    r.getVersion());
        }
    }

    public record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,59}$", message = "Use upper-case letters, digits and _")
            String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 300) String description,
            @NotEmpty Set<String> permissions) {
    }

    public record UpdateRoleRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 300) String description,
            @NotEmpty Set<String> permissions,
            long version) {
    }
}
