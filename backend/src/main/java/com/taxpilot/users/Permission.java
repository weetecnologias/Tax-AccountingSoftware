package com.taxpilot.users;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String module;

    @Column(nullable = false)
    private String description;

    protected Permission() {
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getModule() { return module; }
    public String getDescription() { return description; }
}
