package com.taxpilot.auth;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.audit.AuditService;
import com.taxpilot.config.TaxpilotProperties;
import com.taxpilot.users.PasswordPolicy;
import com.taxpilot.users.Role;
import com.taxpilot.users.RoleRepository;
import com.taxpilot.users.User;
import com.taxpilot.users.UserRepository;

/** Creates the first administrator from environment variables, only when the users table is empty. */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final TaxpilotProperties props;

    public BootstrapAdmin(UserRepository users, RoleRepository roles, PasswordEncoder encoder, AuditService audit,
                          TaxpilotProperties props) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.audit = audit;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        TaxpilotProperties.Bootstrap b = props.bootstrap();
        if (b == null || b.adminEmail() == null || b.adminEmail().isBlank() || b.adminPassword() == null) {
            log.warn("No users exist and no bootstrap admin is configured (TAXPILOT_ADMIN_EMAIL / TAXPILOT_ADMIN_PASSWORD)");
            return;
        }
        PasswordPolicy.check("adminPassword", b.adminPassword());
        User admin = new User(b.adminEmail().trim().toLowerCase(),
                b.adminName() == null || b.adminName().isBlank() ? "Administrator" : b.adminName(),
                encoder.encode(b.adminPassword()));
        Role adminRole = roles.findByCode(Role.ADMIN).orElseThrow();
        admin.replaceRoles(Set.of(adminRole));
        users.save(admin);
        audit.recordAs(null, "system", "USER_BOOTSTRAPPED", "USER", admin.getId(), null, null);
        log.info("Bootstrap administrator created; password change is required at first login");
    }
}
