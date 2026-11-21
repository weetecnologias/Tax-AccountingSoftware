package com.taxpilot.settings;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.audit.AuditService;
import com.taxpilot.common.ApiException;
import com.taxpilot.security.CurrentUser;

/**
 * Review of the statutory GST rate table. Slabs ship as PENDING_REVIEW; a user holding TAX_RULES_APPROVE confirms each
 * against the official source, and the reviewer and time are recorded and audited.
 */
@Service
public class GstRateReviewService {

    public record SlabDto(UUID id, String rate, LocalDate effectiveFrom, LocalDate effectiveTo, String description,
                          String officialSource, String reviewStatus, String reviewedBy, Instant reviewedAt) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public GstRateReviewService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SlabDto> list() {
        return jdbc.query("""
                select s.id, s.rate, s.effective_from, s.effective_to, s.description, s.official_source, s.review_status,
                       u.full_name, s.reviewed_at
                from gst_rate_slabs s left join users u on u.id = s.reviewed_by
                order by s.rate, s.effective_from
                """, (rs, i) -> new SlabDto(rs.getObject("id", UUID.class),
                rs.getBigDecimal("rate").stripTrailingZeros().toPlainString(),
                rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class),
                rs.getString("description"), rs.getString("official_source"), rs.getString("review_status"),
                rs.getString("full_name"),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant()));
    }

    @Transactional
    public SlabDto approve(UUID id) {
        CurrentUser me = CurrentUser.require();
        List<String> status = jdbc.queryForList("select review_status from gst_rate_slabs where id = ? for update", String.class, id);
        if (status.isEmpty()) throw ApiException.notFound("GST rate slab");
        if (!"PENDING_REVIEW".equals(status.get(0))) {
            throw ApiException.conflict("ALREADY_REVIEWED", "This rate has already been reviewed (" + status.get(0) + ")");
        }
        Instant now = Instant.now();
        jdbc.update("update gst_rate_slabs set review_status = 'APPROVED', reviewed_by = ?, reviewed_at = ? where id = ?",
                me.id(), Timestamp.from(now), id);
        SlabDto dto = list().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        audit.record("GST_RATE_APPROVED", "GST_RATE_SLAB", id, null,
                Map.of("rate", dto.rate(), "effectiveFrom", dto.effectiveFrom().toString(), "source", dto.officialSource()));
        return dto;
    }

    @RestController
    @RequestMapping("/api/v1/settings/gst-rates")
    public static class Controller {
        private final GstRateReviewService service;

        public Controller(GstRateReviewService service) {
            this.service = service;
        }

        @GetMapping
        public List<SlabDto> list() {
            return service.list();
        }

        @PostMapping("/{id}/approve")
        @PreAuthorize("hasAuthority('TAX_RULES_APPROVE')")
        public SlabDto approve(@PathVariable UUID id) {
            return service.approve(id);
        }
    }
}
