package com.taxpilot.users;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.audit.AuditService;
import com.taxpilot.auth.UserSessionRepository;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.PageResponse;
import com.taxpilot.security.CurrentUser;
import com.taxpilot.users.UserDtos.CreateRoleRequest;
import com.taxpilot.users.UserDtos.CreateUserRequest;
import com.taxpilot.users.UserDtos.RoleDto;
import com.taxpilot.users.UserDtos.UpdateRoleRequest;
import com.taxpilot.users.UserDtos.UpdateUserRequest;
import com.taxpilot.users.UserDtos.UserDto;

@Service
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final UserSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserService(UserRepository users, RoleRepository roles, PermissionRepository permissions,
                       UserSessionRepository sessions, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.permissions = permissions;
        this.sessions = sessions;
        this.encoder = encoder;
        this.audit = audit;
    }

    // ---- users ----

    @Transactional(readOnly = true)
    public PageResponse<UserDto> list(String q, Boolean active, Pageable pageable) {
        String term = q == null || q.isBlank() ? null : q.trim();
        return PageResponse.of(users.search(term, active, pageable), UserDto::of);
    }

    @Transactional(readOnly = true)
    public UserDto get(UUID id) {
        return UserDto.of(find(id));
    }

    @Transactional(readOnly = true)
    public User find(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional
    public UserDto create(CreateUserRequest req) {
        String email = req.email().trim().toLowerCase();
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "EMAIL_TAKEN",
                    "A user with this email already exists",
                    List.of(new ApiException.FieldError("email", "Email already in use")));
        }
        PasswordPolicy.check("temporaryPassword", req.temporaryPassword());
        User user = new User(email, req.fullName().trim(), encoder.encode(req.temporaryPassword()));
        user.replaceRoles(resolveRoles(req.roles()));
        users.save(user);
        audit.record("USER_CREATED", "USER", user.getId(), null,
                Map.of("email", email, "roles", user.roleCodes()));
        return UserDto.of(user);
    }

    @Transactional
    public UserDto update(UUID id, UpdateUserRequest req) {
        User user = find(id);
        checkVersion(user.getVersion(), req.version());
        CurrentUser me = CurrentUser.require();
        Set<Role> newRoles = resolveRoles(req.roles());
        boolean self = me.id().equals(id);
        if (self && !req.active()) {
            throw ApiException.badRequest("SELF_DEACTIVATION", "You cannot deactivate your own account");
        }
        if (self && user.roleCodes().contains(Role.ADMIN)
                && newRoles.stream().noneMatch(r -> r.getCode().equals(Role.ADMIN))) {
            throw ApiException.badRequest("SELF_ADMIN_REMOVAL", "You cannot remove your own administrator role");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!user.getFullName().equals(req.fullName().trim())) changes.put("fullName", req.fullName().trim());
        if (user.isActive() != req.active()) changes.put("active", req.active());
        Set<String> newCodes = new HashSet<>();
        newRoles.forEach(r -> newCodes.add(r.getCode()));
        if (!user.roleCodes().equals(newCodes)) changes.put("roles", newCodes);

        user.setFullName(req.fullName().trim());
        user.setActive(req.active());
        user.replaceRoles(newRoles);
        if (!req.active()) {
            sessions.revokeAllForUser(id, Instant.now());
        }
        users.save(user);
        if (!changes.isEmpty()) {
            audit.record(req.active() ? "USER_UPDATED" : "USER_DEACTIVATED", "USER", id, null, changes);
        }
        return UserDto.of(user);
    }

    @Transactional
    public void resetPassword(UUID id, String temporaryPassword) {
        User user = find(id);
        PasswordPolicy.check("temporaryPassword", temporaryPassword);
        user.changePassword(encoder.encode(temporaryPassword), true);
        sessions.revokeAllForUser(id, Instant.now());
        audit.record("USER_PASSWORD_RESET", "USER", id, null, null);
    }

    /** Users holding a given permission, for assignment pickers (e.g. reviewers need CLIENT_APPROVE). */
    @Transactional(readOnly = true)
    public List<UserDtos.UserRef> activeUsersWith(String permission) {
        return users.findAll().stream()
                .filter(u -> u.isActive() && (permission == null || u.permissionCodes().contains(permission)))
                .sorted((a, b) -> a.getFullName().compareToIgnoreCase(b.getFullName()))
                .map(UserDtos.UserRef::of)
                .toList();
    }

    // ---- roles ----

    @Transactional(readOnly = true)
    public List<RoleDto> roles() {
        return roles.findAll().stream()
                .sorted((a, b) -> Boolean.compare(b.isSystemRole(), a.isSystemRole()) != 0
                        ? Boolean.compare(b.isSystemRole(), a.isSystemRole())
                        : a.getCode().compareTo(b.getCode()))
                .map(RoleDto::of).toList();
    }

    @Transactional(readOnly = true)
    public List<UserDtos.PermissionDto> permissions() {
        return permissions.findAll().stream()
                .sorted((a, b) -> (a.getModule() + a.getCode()).compareTo(b.getModule() + b.getCode()))
                .map(UserDtos.PermissionDto::of).toList();
    }

    @Transactional
    public RoleDto createRole(CreateRoleRequest req) {
        if (roles.existsByCode(req.code())) {
            throw ApiException.conflict("ROLE_EXISTS", "A role with this code already exists");
        }
        Role role = new Role(req.code(), req.name().trim(), req.description());
        role.replacePermissions(resolvePermissions(req.permissions()));
        roles.save(role);
        audit.record("ROLE_CREATED", "ROLE", role.getId(), null,
                Map.of("code", role.getCode(), "permissions", req.permissions()));
        return RoleDto.of(role);
    }

    @Transactional
    public RoleDto updateRole(UUID id, UpdateRoleRequest req) {
        Role role = roles.findById(id).orElseThrow(() -> ApiException.notFound("Role"));
        checkVersion(role.getVersion(), req.version());
        Set<Permission> perms = resolvePermissions(req.permissions());
        if (Role.ADMIN.equals(role.getCode()) && perms.size() != permissions.count()) {
            throw ApiException.badRequest("ADMIN_ROLE_LOCKED", "The ADMIN role must keep every permission");
        }
        role.setName(req.name().trim());
        role.setDescription(req.description());
        role.replacePermissions(perms);
        roles.save(role);
        audit.record("ROLE_UPDATED", "ROLE", id, null, Map.of("code", role.getCode(), "permissions",
                req.permissions()));
        return RoleDto.of(role);
    }

    private Set<Role> resolveRoles(Set<String> codes) {
        Set<Role> result = new HashSet<>();
        for (String code : codes) {
            result.add(roles.findByCode(code).orElseThrow(() -> ApiException.field("roles", "Unknown role " + code)));
        }
        return result;
    }

    private Set<Permission> resolvePermissions(Set<String> codes) {
        List<Permission> found = permissions.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            throw ApiException.field("permissions", "One or more permissions are unknown");
        }
        return new HashSet<>(found);
    }

    static void checkVersion(long current, long supplied) {
        if (current != supplied) {
            throw ApiException.conflict("STALE_VERSION", "This record was changed by someone else. Reload and try again.");
        }
    }
}
