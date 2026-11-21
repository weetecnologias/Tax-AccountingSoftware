# TAXPILOT AI

**Your Intelligent Accounting & Tax Department** — an AI-assisted accounting, GST, income-tax and compliance
platform for Indian accounting operations.

Current status: **Phase 1 (working foundation)** — login, users/roles, dashboard, client onboarding,
document storage and audit log. See [PROGRESS.md](PROGRESS.md) and [documentation/BACKLOG.md](documentation/BACKLOG.md).

| Part | Stack | Folder |
|---|---|---|
| API | Java 21 bytecode, Spring Boot 4.1, PostgreSQL 17, Flyway | `backend/` |
| Web app | React 19, TypeScript, Vite, Tailwind CSS, React Query, React Hook Form, Zod, Recharts | `frontend/` |
| Local infra | Docker Compose (PostgreSQL; optional MinIO and ClamAV) | `docker-compose.yml` |

## Run locally

Prerequisites: JDK 21+ (25 works), Maven 3.9+, Node 22+, Docker Desktop.

1. Create your local secrets file once (it is git-ignored):
   copy `.env.example` to `.env` and fill in the keys as described inside it.
2. Start PostgreSQL:
   ```
   docker compose up -d postgres
   ```
3. Start the API (http://localhost:8080; migrations run automatically; the first admin is created from `.env`):
   ```
   cd backend
   mvn spring-boot:run
   ```
4. Start the web app in a second terminal (http://localhost:5173):
   ```
   cd frontend
   npm install
   npm run dev
   ```
5. Sign in with `TAXPILOT_ADMIN_EMAIL` / `TAXPILOT_ADMIN_PASSWORD` from `.env`. You will be asked to change the
   password at first sign-in.

API documentation (dev): http://localhost:8080/swagger-ui.html · OpenAPI JSON: http://localhost:8080/api/v1/openapi

## Tests

```
cd backend;  mvn verify      # 22 unit + 20 integration tests (Docker must be running for Testcontainers)
cd frontend; npm test        # 8 unit tests
```

## Documentation
- [Architecture](documentation/architecture/ARCHITECTURE.md) · [Database / ERD](documentation/database/ERD.md)
- [Product requirements](documentation/prd/PRD.md) · [Phase 1 SRS](documentation/srs/SRS-Phase1.md)
- [API contracts](documentation/api/API-Phase1.md) · [UI/UX](documentation/ui-ux/UI-UX.md)
- [Security](documentation/security/SECURITY.md) · [Testing](documentation/testing/TESTING.md)

## Branches

All work happens off `develop`. Nothing is pushed straight to `main`, which holds production releases only.
See [BRANCHING.md](BRANCHING.md).
