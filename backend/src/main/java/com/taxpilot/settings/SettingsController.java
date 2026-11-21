package com.taxpilot.settings;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/settings/organisation")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    /** Readable by every signed-in user: the letterhead appears on reports and the policy drives the UI. */
    @GetMapping
    public SettingsService.SettingsDto get() {
        return service.get();
    }

    @GetMapping("/letterhead")
    public SettingsService.Letterhead letterhead() {
        return service.letterhead();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SETTINGS_WRITE')")
    public SettingsService.SettingsDto update(@Valid @RequestBody SettingsService.SettingsRequest request) {
        return service.update(request);
    }
}
