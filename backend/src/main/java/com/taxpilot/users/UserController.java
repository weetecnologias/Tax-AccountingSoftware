package com.taxpilot.users;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.common.PageResponse;
import com.taxpilot.common.Paging;
import com.taxpilot.users.UserDtos.CreateRoleRequest;
import com.taxpilot.users.UserDtos.CreateUserRequest;
import com.taxpilot.users.UserDtos.PermissionDto;
import com.taxpilot.users.UserDtos.ResetPasswordRequest;
import com.taxpilot.users.UserDtos.RoleDto;
import com.taxpilot.users.UserDtos.UpdateRoleRequest;
import com.taxpilot.users.UserDtos.UpdateUserRequest;
import com.taxpilot.users.UserDtos.UserDto;
import com.taxpilot.users.UserDtos.UserRef;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('USER_READ')")
    public PageResponse<UserDto> list(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) Boolean active,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) String sort) {
        return service.list(q, active, Paging.of(page, size, sort, Set.of("fullName", "email", "createdAt",
                "lastLoginAt"), "fullName"));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USER_WRITE')")
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAuthority('USER_READ')")
    public UserDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('USER_WRITE')")
    public UserDto update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/users/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('USER_WRITE')")
    public void resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(id, request.temporaryPassword());
    }

    /** Lightweight list for assignment pickers; any client editor may need it. */
    @GetMapping("/users/assignable")
    @PreAuthorize("hasAnyAuthority('CLIENT_WRITE', 'USER_READ')")
    public List<UserRef> assignable(@RequestParam(required = false) String permission) {
        return service.activeUsersWith(permission);
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('ROLE_READ')")
    public List<RoleDto> roles() {
        return service.roles();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROLE_WRITE')")
    public RoleDto createRole(@Valid @RequestBody CreateRoleRequest request) {
        return service.createRole(request);
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('ROLE_WRITE')")
    public RoleDto updateRole(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
        return service.updateRole(id, request);
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('ROLE_READ')")
    public List<PermissionDto> permissions() {
        return service.permissions();
    }
}
