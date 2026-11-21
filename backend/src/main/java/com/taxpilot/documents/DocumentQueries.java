package com.taxpilot.documents;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only document facts other modules may ask for, without depending on the full DocumentService. */
@Service
@Transactional(readOnly = true)
public class DocumentQueries {

    private final DocumentRepository documents;

    public DocumentQueries(DocumentRepository documents) {
        this.documents = documents;
    }

    public long countForClient(UUID clientId) {
        return documents.countByClientId(clientId);
    }

    public long countAwaitingReview(UUID clientId) {
        return documents.countByClientIdAndStatusIn(clientId, Document.Status.AWAITING_REVIEW);
    }
}
