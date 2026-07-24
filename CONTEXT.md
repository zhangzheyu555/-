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
