package com.taxpilot.common;

import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Builds a bounded, whitelisted Pageable from raw query parameters. */
public final class Paging {

    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    public static Pageable of(int page, int size, String sort, Set<String> allowedFields, String defaultField) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        String field = defaultField;
        Sort.Direction direction = Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            if (allowedFields.contains(parts[0])) {
                field = parts[0];
            }
            if (parts.length > 1 && parts[1].equalsIgnoreCase("asc")) {
                direction = Sort.Direction.ASC;
            }
        }
        return PageRequest.of(safePage, safeSize, Sort.by(direction, field));
    }
}
