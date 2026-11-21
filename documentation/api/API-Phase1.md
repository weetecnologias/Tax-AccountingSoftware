# TAXPILOT AI — API Contracts, Phase 1

Base URL `/api/v1`. JSON. Bearer access token on every call except those marked *public*.
The live OpenAPI document is served by the backend at `/api/v1/openapi` (Swagger UI at `/swagger-ui` in dev).

## Conventions
- **Paging:** `?page=0&size=20&sort=createdAt,desc` → `{ items, page, size, totalItems, totalPages }`.
- **Errors:** `application/problem+json` → `{ type, title, status, detail, instance, requestId, errors: [{field, message}] }`.
- **Concurrency:** mutable resources return `version`; send it back on `PUT`; a stale version gives `409`.

## Auth
| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | /auth/login | public | `{email,password}` → `{accessToken, expiresIn, user}` + refresh cookie |
| POST | /auth/refresh | public (cookie) | rotates cookie, returns new access token |
| POST | /auth/logout | authenticated | revokes session, clears cookie |
| GET | /auth/me | authenticated | current user, roles, permissions |
| POST | /auth/change-password | authenticated | `{currentPassword,newPassword}` |

## Users and roles
| Method | Path | Permission |
|---|---|---|
| GET | /users | USER_READ |
| POST | /users | USER_WRITE |
| GET | /users/{id} | USER_READ |
| PUT | /users/{id} | USER_WRITE |
| POST | /users/{id}/reset-password | USER_WRITE |
| GET | /roles | ROLE_READ |
| POST | /roles | ROLE_WRITE |
| PUT | /roles/{id} | ROLE_WRITE |
| GET | /permissions | ROLE_READ |

## Clients
| Method | Path | Permission |
|---|---|---|
| GET | /clients?q=&status=&entityType= | CLIENT_READ |
| POST | /clients | CLIENT_WRITE |
| GET | /clients/{id} | CLIENT_READ (full PAN needs CLIENT_PII_READ) |
| PUT | /clients/{id} | CLIENT_WRITE |
| POST | /clients/{id}/status | CLIENT_WRITE (activation needs CLIENT_APPROVE) — `{targetStatus, comment, closureReason?}` |
| GET | /clients/{id}/status-history | CLIENT_READ |
| GET/POST | /clients/{id}/contacts · PUT/DELETE /clients/{id}/contacts/{contactId} | CLIENT_READ / CLIENT_WRITE |
| GET/POST | /clients/{id}/addresses · PUT/DELETE …/{addressId} | CLIENT_READ / CLIENT_WRITE |
| GET/POST | /clients/{id}/gst-registrations · PUT …/{regId} | CLIENT_READ / CLIENT_WRITE |
| GET/POST | /clients/{id}/bank-accounts · PUT …/{accountId} | CLIENT_READ / CLIENT_WRITE |
| GET | /clients/{id}/documents | DOCUMENT_READ |
| GET | /clients/{id}/audit | AUDIT_READ |
| GET | /clients/export | CLIENT_READ — CSV, audited |

## Documents
| Method | Path | Permission |
|---|---|---|
| POST | /documents/upload (multipart: file, clientId, documentType, financialYear?) | DOCUMENT_UPLOAD |
| GET | /documents?clientId=&status=&documentType= | DOCUMENT_READ |
| GET | /documents/{id} | DOCUMENT_READ |
| GET | /documents/{id}/download | DOCUMENT_READ (audited) |
| POST | /documents/{id}/approve · /reject `{comment}` | DOCUMENT_REVIEW |
| POST | /documents/{id}/archive | DOCUMENT_REVIEW |

## Audit, dashboard, reference
| Method | Path | Permission |
|---|---|---|
| GET | /audit-logs?actorId=&action=&entityType=&clientId=&from=&to= | AUDIT_READ |
| GET | /dashboard/summary | DASHBOARD_READ |
| GET | /reference/states · /reference/entity-types · /reference/document-types | authenticated |
| GET | /actuator/health | public |

`POST /documents/{id}/extract` (spec Part 21) is deferred to Phase 6 with the Document Intelligence Agent.
