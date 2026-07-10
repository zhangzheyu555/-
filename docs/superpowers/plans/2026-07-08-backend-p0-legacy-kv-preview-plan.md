# Backend P0 Legacy KV Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-only legacy KV migration preview endpoint that shows which business keys can be mapped to structured MySQL tables before any migration run exists.

**Architecture:** Reuse `MigrationStatusService.status(AuthUser)` as the source of truth for legacy business key presence. Add preview records and expose `GET /api/migration/legacy-kv/preview` for owner roles only.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring JDBC, JUnit 5, AssertJ, Mockito, H2.

## Global Constraints

- Preview endpoints must not write, delete, or mutate database state.
- Preview output must not claim automatic migration has run.
- Owner-only access remains enforced by the service layer.
- Tests must fail before production code is written.

---

### Task 1: Add Legacy KV Preview API

**Files:**
- Modify: `backend/src/test/java/com/storeprofit/system/migration/MigrationStatusServiceTest.java`
- Modify: `backend/src/test/java/com/storeprofit/system/migration/MigrationControllerTest.java`
- Modify: `backend/src/main/java/com/storeprofit/system/migration/MigrationStatusService.java`
- Modify: `backend/src/main/java/com/storeprofit/system/migration/MigrationController.java`
- Create: `backend/src/main/java/com/storeprofit/system/migration/LegacyKvMigrationPreviewResponse.java`
- Create: `backend/src/main/java/com/storeprofit/system/migration/LegacyKvMigrationPreviewItemResponse.java`
- Modify: `docs/superpowers/specs/2026-07-08-backend-completion-design-v1.md`

**Interfaces:**
- Produces: `GET /api/migration/legacy-kv/preview`.
- Produces: `MigrationStatusService.legacyKvPreview(AuthUser user)`.
- Produces: `LegacyKvMigrationPreviewResponse` with `automaticRunAvailable=false`.

- [x] **Step 1: Write failing service preview test**

```java
LegacyKvMigrationPreviewResponse preview = service.legacyKvPreview(user("BOSS"));
assertThat(preview.automaticRunAvailable()).isFalse();
assertThat(preview.actionableKeyCount()).isEqualTo(1);
```

- [x] **Step 2: Write failing controller preview test**

```java
ApiResponse<LegacyKvMigrationPreviewResponse> result = controller.legacyKvPreview("Bearer token");
assertThat(result.data()).isSameAs(response);
```

- [x] **Step 3: Run targeted tests to verify RED**

Run: `mvn -q '-Dtest=MigrationStatusServiceTest,MigrationControllerTest' test`

Expected: compilation failure because preview classes or methods do not exist.

- [x] **Step 4: Implement minimal preview response and route**

Use `status(user)` to build preview items. Present keys get `plannedAction="MAP_TO_STRUCTURED_TABLE"`; absent keys get `plannedAction="NOOP"`.

- [x] **Step 5: Run targeted tests to verify GREEN**

Run: `mvn -q '-Dtest=MigrationStatusServiceTest,MigrationControllerTest' test`

Expected: tests pass.

- [x] **Step 6: Update design document progress**

Record that `GET /api/migration/legacy-kv/preview` exists as a read-only preview endpoint.

- [x] **Step 7: Run backend verification**

Run: `mvn -q test`

Expected: full backend test suite passes.
