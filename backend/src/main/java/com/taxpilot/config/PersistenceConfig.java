package com.taxpilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Module repositories are grouped as nested interfaces (e.g. ClientRepositories.ClientRepository). */
@Configuration
@EnableJpaRepositories(basePackages = "com.taxpilot", considerNestedRepositories = true)
public class PersistenceConfig {
}
