# AI Profit OS - Qoder Source Handoff

This archive is a sanitized snapshot of the current working tree for code repair in Qoder.

## Included

- `backend/`: Spring Boot source, Flyway migrations, and tests.
- `frontend-vue/`: Vue 3 source, Playwright tests, and build configuration.
- `inspection-service/`: Python inspection service source and templates.
- `docs/`: product, architecture, deployment, and migration documentation.
- `.github/` and `scripts/`: CI and operational scripts.
- Root project guidance: `AGENTS.md`, `README.md`, and `CURRENT_VERSION.md`.

## Intentionally Excluded

- Git history and repository metadata (`.git`).
- Real business backups and legacy data files.
- Legacy HTML runtimes: root `index.html`, `database.js`, `cloudbase.full.js`, `runtime-static/`, and `web/`.
- `backend/src/main/resources/static/`, which contains legacy static resources and historical backup material.
- Dependencies and build output: `node_modules/`, `target/`, `dist/`, `.vite/`.
- Logs, browser screenshots, test output, local work folders, and environment files.
- YOLO model weights; install or provide them separately when testing image recognition.

## Current Architecture

- `frontend-vue` is the only intended production frontend.
- `backend` is the production API and business-logic service.
- MySQL is the system of record; business data must not be stored in browser storage.
- Read `AGENTS.md` before changing code.

## Build Commands

Backend:

```powershell
cd backend
mvn -q clean package
```

Frontend:

```powershell
cd frontend-vue
npm ci
npm run build
```

The backend requires a non-production MySQL database and credentials supplied through environment variables for runtime and Flyway validation.

## Important Release Blockers

- Validate all Flyway migrations against an empty non-production MySQL database.
- Start and test the latest built backend jar before accepting runtime results.
- Run the role, tenant, and store-scope authorization regression suite.
- Do not publish this project until secret/data scans and the final release audit pass.
