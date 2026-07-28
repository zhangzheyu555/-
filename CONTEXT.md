# Domain Glossary

## Warehouse Requisition

A store's demand for goods from its assigned supply warehouse. One requisition can be fulfilled
through multiple deliveries and remains active until all requested quantities are fulfilled or the
request is rejected for a business reason.

## Delivery

One physical shipment against a warehouse requisition. A partial requisition can therefore have
more than one delivery.

## Backorder

The unshipped quantity of an accepted requisition line. A backorder represents normal inventory
shortage handling and is not a rejection.

## Rejection

The termination of an unreasonable, duplicate, or incorrectly submitted requisition. Inventory
shortage alone is not a valid rejection reason.

## Persistent Project Environment Memory

### 2026-07-24 — Canonical 38-Store Database

- User instruction: all future project startup, development, browser verification, manual QA, and
  end-to-end testing must use the 38-store MySQL database by default.
- Canonical local database identity: database `ai_profit_os_real_qa`, Docker container
  `ai-profit-os-qa-38-mysql`, Docker volume `ai-profit-os-qa-38-mysql-data`, and loopback port
  `13308`.
- The persisted volume is authoritative; the container's stale `MYSQL_DATABASE` environment value
  is not. Verify the connected schema with `database()` or `SHOW DATABASES`.
- Do not silently substitute an empty development database, `ai-profit-os-dev` volume,
  `ai-profit-os-dev-next` volume, or another QA database. If the canonical database cannot start or
  fails validation, stop and report the problem before using any alternative.
- Before project verification, confirm the database identity and that the business store set is 38
  `营业中` stores. Preserve the 5 `已闭店` historical store rows; they are not part of the active
  38-store count. Credentials must continue to come from local environment or container
  configuration and must not be written into project memory.
- Preserve this database and volume. Never run `down -v`, clear, reinitialize, repair Flyway
  history, or otherwise replace its data unless the user explicitly authorizes that exact action.
- Preflight on 2026-07-24 found two successful pre-merge migrations recorded under their old
  versions: warehouse item scope at V97 and store archive management at V98. Current source places
  those same migrations at V103 and V104 because remote V97-V102 were merged later. Do not run
  Flyway `repair`; current-source startup requires a backup and an explicitly authorized,
  verified history-version alignment before applying the real V97-V102 migrations.
- The isolated empty database created under Compose project `ai-profit-os-dev-20260724` is
  temporary and is not the canonical project database.

## Verification Memory

### 2026-07-24 — Warehouse Requisition Shortage Flow: PASSED

- Environment: real QA MySQL database `ai_profit_os_real_qa`; verified through the browser against
  the running frontend and backend.
- Sufficient stock: requisition `REQ1784869682961-84af7e` requested 2, shipped 2, and reached
  `RECEIVED`.
- Partial stock: requisition `REQ1784869683093-26675d` requested 5, shipped 3 first, retained 2 as
  backorder, then shipped the remaining 2 after replenishment and reached `RECEIVED`.
- Zero stock: requisition `REQ1784869683213-5da135` remained waiting with requested 2, shipped 0,
  and shortage 2; after purchase `PO1784870368333-f4ad96` replenished 2, the warehouse continued
  shipment and the requisition reached `RECEIVED` with shipped 2.
- Rejection: requisition `REQ1784869957252-cb782c` confirmed that a rejection reason is required and
  that rejection is recorded with a non-shortage business reason.
- UI displayed requested, pending, available, shipped, and shortage quantities correctly.
- Automated checks passed: 20 `WarehouseServiceTest` tests, focused Playwright checks on Chromium
  and Android, and the Vue production build.
- No database schema change or new Flyway migration was required for this verification.

### 2026-07-24 — Warehouse Item Requisition Scope: PASSED

- Environment: real QA MySQL database `ai_profit_os_real_qa`; the migration is committed as Flyway
  V103 after the remote branch occupied V97-V102; verified through the visible browser and direct
  API checks.
- Existing 413 items have no policy row and remain backward-compatible implicit `ALL`; no historical
  item or requisition row was rewritten.
- Browser verification passed for explicit all-store confirmation (38 active stores), region-only,
  store-only, region/store union, not-started campaigns, and ended campaigns.
- Store `rg1` could see the active all-store, `JINGZHOU`, and `rg1` fixtures, but could not see the
  future or ended fixtures.
- After item `CODEX-QA-STORE-0724` changed from store `rg1` to `rg2`, direct requisition submission
  was rejected with HTTP 403 and `ITEM_NOT_AVAILABLE_FOR_STORE`.
- Historical requisition `REQ1784873744322-83acd9` remained visible and pending after the scope
  change, confirming that scope updates affect only new requisitions.
- Operation logs contain policy before/after JSON for both initial configuration and the later scope
  change.
- Focused backend tests, the Vue production build, and the requisition-scope Chromium test passed.
- V103 is a minimal additive migration with two dedicated tables; it does not backfill or alter
  existing business rows.

### 2026-07-24 — Store Archive Management and Inactive Restrictions: PASSED

- Environment: real QA MySQL database `ai_profit_os_real_qa`; the migration is committed as Flyway
  V104 after the remote branch occupied V97-V102; verified through the visible browser and direct
  API checks.
- Browser verification passed for normal creation, duplicate code/name, required and phone
  validation, dynamically loaded dropdown options, immediately effective editing, stop
  confirmation, historical requisition visibility, and re-enabling.
- An inactive store was blocked from creating requisitions in the UI and was also blocked by direct
  API calls for requisitions, daily loss, expenses, inventory checks, and inspections with
  `STORE_INACTIVE_NEW_BUSINESS_FORBIDDEN`; store-manager purchase creation remained permission
  denied.
- Re-enabling the store restored new requisition creation immediately.
- New, edit, stop, re-enable, and denied-operation logs were verified with before/after data where
  applicable.
- Focused backend tests, four store-management Chromium tests, the backend package build, and the
  Vue production build passed.
- V104 is a minimal additive migration adding responsible employee, cost-account attribution, and
  optimistic-lock version fields. Existing stores remain readable and historical business data is
  unchanged.
- All synthetic users, store, employee, requisitions, tokens, and logs created for this verification
  were removed afterward; the original 43 stores remain.

### 2026-07-24 — Knowledge Base Visibility and Store Multi-select: PASSED

- Upload now explicitly supports either transactional upload-and-publish or draft-only save; both
  full-tenant publish entry points require confirmation and UI messages follow the returned status.
- `GET /api/knowledge-base/documents/available` returns only current-tenant `PUBLISHED` summaries
  selected by tenant, role, and store scope in SQL. Drafts, archived records, foreign-tenant records,
  and out-of-scope records are excluded before reaching the frontend.
- BOSS, FINANCE, SUPERVISOR, WAREHOUSE, STORE_MANAGER, and EMPLOYEE sessions expose the knowledge
  menu when their effective permission contains `knowledge_base.search`; personal `DENY` remains
  authoritative. Focus/visibility refreshes update session permissions and the document catalog.
- Only BOSS and SUPERVISOR can see or call management functions. Supervisor store selection and
  backend validation use the persisted configured STORE scope rather than the supervisor-wide
  compatibility shortcut used by other operational modules. Available lists, search, and download
  now use that same knowledge-base-specific scope.
- Supervisor account creation and atomic access-profile updates persist actual store IDs to the
  existing account and data-scope tables. Legacy supervisor `ALL` rows are rejected by the
  knowledge-base boundary, while other operational modules retain their existing compatibility
  behavior.
- The compatibility authorization endpoint synchronizes the same supervisor store list, and atomic
  access-profile updates reject mismatched account versus STORE scopes before either source is
  written.
- Store targeting uses searchable checkboxes backed by `StoreInfo.id`, supports filtered select-all
  and clear, and requests the existing `/api/stores` endpoint with its knowledge-base scope option.
- Focused backend tests, 22 Chromium account/knowledge-base Playwright checks, Vue typecheck/build,
  backend package, and diff checks passed. The full backend run executed 982 tests with one unrelated known
  H2 fixture error in `FinanceDataScopeRepositoryTest` because its local `profit_entry` fixture lacks
  delivery-platform columns already queried by production code.
- No knowledge-base database schema change and no new Flyway migration were added.

### 2026-07-25 — Canonical Migration History Alignment and Latest Runtime: PASSED

- The user explicitly authorized aligning the canonical 38-store database migration history and
  deploying the latest local version.
- A fresh full backup was created before any history change:
  `/private/tmp/ai_profit_os_real_qa_pre_alignment_20260725_091843.sql.gz`, SHA-256
  `ffe76285e19170eca52787cf6e521d48629bfbb193e95344caa491650d1a275a`. Gzip integrity and the
  completed dump footer were verified.
- The full history audit found both the known V97/V98 renumbering and an older combined daily-loss
  migration. The alignment preserved installed ranks and execution metadata while mapping the old
  combined V93/V94 records, the V96 checksum, and old V97/V98 to current V93/V94/V96/V103/V104.
- The exact procedure was first rehearsed against a restored copy of the fresh backup. With
  one-time QA out-of-order execution, Flyway then ran the idempotent V91/V92 split migrations and
  the real V97-V102 migrations. A second strict startup with out-of-order disabled validated 105
  migrations, reported current version V104, and reported no pending migration.
- The same audited transaction and migration run then completed on canonical
  `ai_profit_os_real_qa`. Final history is 105 rows, maximum installed rank 105, zero failures, and
  strict startup validates 105 migrations at V104 with no pending migration.
- Business data remained 38 `营业中` stores plus 5 `已闭店` stores, 45 accounts, and 49 published
  knowledge-base documents. The V97-V102 permission and encoding repairs reached their expected
  state.
- Runtime `http://127.0.0.1:8188` now serves commit `2ac6177` through backend image
  `ai-profit-os-dev-backend:head-2ac6177` and frontend image
  `ai-profit-os-dev-frontend:head-2ac6177`. Both use `unless-stopped`; the prior compatible
  backend/frontend containers are stopped and retained for historical reference.
- API verification passed for BOSS permissions, 49 available published documents, and 43 selectable
  stores. Visible browser verification passed for the new available-document section, separate
  upload-and-publish/draft actions, and selecting two stores with the checkbox selector.
- No `flyway repair` was used. No migration source file was added or modified during the alignment
  and deployment.

### 2026-07-27 — Canonical Employee Archive Recovery: PASSED

- The canonical `ai_profit_os_real_qa` database had zero `employee`, `salary_policy`, and
  `employee_salary_profile` rows even though Flyway V69 was recorded as successful. The tenant and
  employee data did not exist when that conditional seed migration originally ran, so it inserted
  nothing and Flyway correctly did not rerun it later.
- The project database snapshot `database/store_profit_mysql8.sql` was restored into an isolated
  MySQL container and audited before import. It contained 194 historical formal employee archives
  plus 3 explicitly marked local demo employees. The 3 demo employees and their demo salary rows
  were excluded.
- A verified backup of the target tables was created before import:
  `/private/tmp/ai_profit_os_real_qa_pre_employee_import_20260727.sql.gz`, SHA-256
  `63beca45cd98d88650424b129d7ce83b3f7c4f87f955418ad83952582f2ba5f1`.
- One transaction imported 194 formal employee archives covering the 13 RG stores, created the
  existing V69 standard 2026 salary policy, and created 164 policy-linked salary profiles for
  non-part-time employees. Final employee status is 181 `在职` plus 13 `离职`; there are zero
  orphan stores, profiles, policies, or imported demo employees.
- Historical salary records and current-month attendance were intentionally not copied. The salary
  employee-page API now exposes all 181 active employees as `PENDING_GENERATION`; `rg1` exposes 9.
  Salary generation correctly remains blocked until current-month attendance is confirmed.
- Operation log target `employee-history-import-20260727` records the source, counts, and explicit
  demo-data exclusion. The temporary verification token and isolated audit container were removed.
