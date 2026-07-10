# Backend P0 Migration Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first P0 backend capability: a read-only migration status endpoint that reports which legacy KV business keys still need structured MySQL migration.

**Architecture:** Keep the slice small and non-destructive. Add a `migration` service that reads `kv_storage`, returns a typed response, and lets only `BOSS` or `ADMIN` users access it through `/api/migration/status`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring JDBC, JUnit 5, AssertJ, Mockito, H2 in-memory database for service tests.

## Global Constraints

- MySQL remains the target source of truth for real business data.
- `kv_storage` is compatibility and migration buffer only.
- No browser storage changes in this slice.
- No secret, API key, database口令, or environment value is written to docs or code.
- Production code changes must be preceded by failing tests.

---

### Task 1: Add Migration Status API

**Files:**
- Create: `backend/src/test/java/com/storeprofit/system/migration/MigrationStatusServiceTest.java`
- Create: `backend/src/test/java/com/storeprofit/system/migration/MigrationControllerTest.java`
- Create: `backend/src/main/java/com/storeprofit/system/migration/MigrationStatusService.java`
- Create: `backend/src/main/java/com/storeprofit/system/migration/MigrationController.java`
- Create: `backend/src/main/java/com/storeprofit/system/migration/MigrationStatusResponse.java`
- Create: `backend/src/main/java/com/storeprofit/system/migration/LegacyKvKeyStatusResponse.java`
- Modify: `docs/superpowers/specs/2026-07-08-backend-completion-design-v1.md`

**Interfaces:**
- Consumes: `AuthService.requireUser(String authorization)` and `AuthUser.role()`.
- Produces: `GET /api/migration/status` returning `ApiResponse<MigrationStatusResponse>`.
- Produces: `MigrationStatusService.status(AuthUser user)`.

- [x] **Step 1: Write failing service test**

```java
MigrationStatusResponse response = service.status(boss());
assertThat(response.migrationRequired()).isTrue();
assertThat(response.presentBusinessKeyCount()).isEqualTo(2);
```

- [x] **Step 2: Write failing controller test**

```java
ApiResponse<MigrationStatusResponse> result = controller.status("Bearer token");
assertThat(result.success()).isTrue();
verify(migrationStatusService).status(user);
```

- [x] **Step 3: Run targeted tests to verify RED**

Run: `mvn -q -Dtest=MigrationStatusServiceTest,MigrationControllerTest test`

Expected: compilation failure because migration status classes do not exist yet.

- [x] **Step 4: Implement minimal migration status classes**

Add typed records and a service that checks these legacy business keys:

```text
stores -> store_branch
entries -> profit_entry
salary -> salary_record
expenses -> expense_claim
inspections -> inspection_record
logs -> operation_log
```

Only `BOSS` and `ADMIN` may call the status service.

- [x] **Step 5: Run targeted tests to verify GREEN**

Run: `mvn -q -Dtest=MigrationStatusServiceTest,MigrationControllerTest test`

Expected: tests pass.

- [x] **Step 6: Update design document progress**

Update `2026-07-08-backend-completion-design-v1.md` to record that `GET /api/migration/status` exists as the first P0 read-only migration endpoint.

- [x] **Step 7: Run backend verification**

Run: `mvn -q test`

Expected: full backend test suite passes.
