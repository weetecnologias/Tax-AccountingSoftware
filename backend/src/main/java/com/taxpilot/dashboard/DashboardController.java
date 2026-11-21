package com.taxpilot.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.audit.AuditLogRepository;
import com.taxpilot.audit.AuditService;
import com.taxpilot.clients.ClientRepositories.ClientRepository;
import com.taxpilot.clients.ClientStatus;
import com.taxpilot.documents.Document;
import com.taxpilot.documents.DocumentRepository;
import com.taxpilot.security.CurrentUser;

/** Every figure is a live query; modules not yet built contribute nothing rather than placeholder numbers. */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ClientRepository clients;
    private final DocumentRepository documents;
    private final AuditLogRepository auditLogs;
    private final AuditService audit;

    public DashboardController(ClientRepository clients, DocumentRepository documents, AuditLogRepository auditLogs,
                               AuditService audit) {
        this.clients = clients;
        this.documents = documents;
        this.auditLogs = auditLogs;
        this.audit = audit;
    }

    public record DayCount(LocalDate day, long count) {
    }

    public record Summary(Instant generatedAt, long totalClients, long activeClients, long clientsAwaitingApproval,
                          long clientsWithoutAccountant, Map<String, Long> clientsByStatus, long totalDocuments,
                          long documentsAwaitingReview, long suspectedDuplicates, long documentsUploadedToday,
                          Map<String, Long> documentsByStatus, List<DayCount> uploadsLast14Days,
                          List<AuditService.AuditEntryDto> recentActivity) {
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('DASHBOARD_READ')")
    @Transactional(readOnly = true)
    public Summary summary() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ClientStatus s : ClientStatus.values()) byStatus.put(s.name(), 0L);
        for (Object[] row : clients.countByStatusGrouped()) byStatus.put(((ClientStatus) row[0]).name(), (Long) row[1]);
        long totalClients = byStatus.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Long> docsByStatus = new LinkedHashMap<>();
        for (Document.Status s : Document.Status.values()) docsByStatus.put(s.name(), 0L);
        for (Object[] row : documents.countByStatusGrouped())
            docsByStatus.put(((Document.Status) row[0]).name(), (Long) row[1]);
        long totalDocs = docsByStatus.values().stream().mapToLong(Long::longValue).sum();

        LocalDate today = LocalDate.now(IST);
        Instant startOfToday = today.atStartOfDay(IST).toInstant();
        Instant since = today.minusDays(13).atStartOfDay(IST).toInstant();
        Map<LocalDate, Long> perDay = new LinkedHashMap<>();
        for (int i = 13; i >= 0; i--) perDay.put(today.minusDays(i), 0L);
        for (Object[] row : documents.uploadsPerDay(since)) {
            LocalDate day = row[0] instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) row[0];
            perDay.put(day, ((Number) row[1]).longValue());
        }
        List<DayCount> uploads = new ArrayList<>();
        perDay.forEach((d, c) -> uploads.add(new DayCount(d, c)));

        CurrentUser me = CurrentUser.require();
        List<AuditService.AuditEntryDto> recent = me.has("AUDIT_READ")
                ? auditLogs.findTop10ByOrderByOccurredAtDesc().stream().map(audit::toDto).toList()
                : List.of();

        return new Summary(Instant.now(), totalClients, byStatus.get("ACTIVE"), byStatus.get("READY_FOR_APPROVAL"),
                clients.countOpenWithoutAccountant(), byStatus, totalDocs,
                documents.countByStatusIn(Document.Status.AWAITING_REVIEW), docsByStatus.get("DUPLICATE_SUSPECTED"),
                documents.countByUploadedAtGreaterThanEqual(startOfToday), docsByStatus, uploads, recent);
    }
}
