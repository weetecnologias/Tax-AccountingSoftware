package com.taxpilot.common;

import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.clients.EntityType;
import com.taxpilot.common.validation.IndianStates;
import com.taxpilot.documents.DocumentType;

/** Static reference lists for forms. Available to any authenticated user. */
@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {

    public record Option(String value, String label) {
    }

    public record EntityTypeOption(String value, String label, List<Character> panHolderTypes) {
    }

    @GetMapping("/states")
    public List<Option> states() {
        return IndianStates.CODES.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> new Option(e.getKey(), e.getValue())).toList();
    }

    @GetMapping("/entity-types")
    public List<EntityTypeOption> entityTypes() {
        return Arrays.stream(EntityType.values())
                .map(t -> new EntityTypeOption(t.name(), t.label(), t.panHolderTypes().stream().sorted().toList()))
                .toList();
    }

    @GetMapping("/document-types")
    public List<Option> documentTypes() {
        return Arrays.stream(DocumentType.values()).map(t -> new Option(t.name(), t.label())).toList();
    }
}
