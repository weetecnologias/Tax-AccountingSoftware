# TAXPILOT AI — Testing Strategy

| Layer | Tooling | Phase 1 coverage |
|---|---|---|
| Backend unit | JUnit 5, AssertJ | PAN/TAN/GSTIN validators, AES-GCM crypto, client status state machine, file-type sniffing |
| Backend integration | Spring Boot Test + Testcontainers PostgreSQL | Auth (login, lockout, refresh rotation, logout), permissions, client CRUD + maker-checker activation, documents upload/duplicate/review, audit immutability, dashboard |
| Frontend unit | Vitest + Testing Library | Zod schemas (PAN/GSTIN), API error mapping, key components |
| End-to-end | Manual scripted run in a browser against local stack (Phase 1); Playwright suite planned for Phase 2 | Login → create client → onboard → activate (second user) → upload → review → audit |

Run:
```
cd backend;  mvn verify        # needs Docker running for Testcontainers
cd frontend; npm test
```

Acceptance gates for every phase: build green, all tests green, no unbalanced journals (Phase 2+),
no consequential action without approval, every calculation reproducible.
