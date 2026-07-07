# P0 Trustworthiness Fixes Implementation Plan

> REQUIRED SUB-SKILL: Use `executing-plans` with `test-driven-development`.

## Goal

Make the verified 8080 mainline more trustworthy without changing the Spring Boot static-app architecture:

- Assistant API returns structured trust metadata.
- Assistant UI shows the data basis, month, scope, and warnings.
- Store-manager dashboard and assistant scope remain limited to visible stores.
- Import preview keeps explicit month-conflict warning and blocking behavior.
- Screenshot recognition clearly says visual recognition is not configured.
- Escape closes the active import preview/panel in addition to existing modals.

## Current Facts

- Mainline UI: `backend/src/main/resources/static/index.html`
- Static data/helpers: `backend/src/main/resources/static/database.js`
- Backend assistant DTO: `backend/src/main/java/com/storeprofit/system/assistant/AssistantChatResponse.java`
- Backend assistant service: `backend/src/main/java/com/storeprofit/system/assistant/AssistantService.java`
- Current branch: `codex/deepseek-assistant`
- Do not commit `output/`.

## Task 1: Assistant Metadata, Test First

1. Add `backend/src/test/java/com/storeprofit/system/assistant/AssistantChatResponseTest.java`.
2. Test that `AssistantChatResponse` still exposes `answer`, `aiUsed`, `blocked`, `source`, and also exposes `dataSource`, `month`, `storeScope`, `warnings`.
3. Run `cd backend; mvn -q -Dtest=AssistantChatResponseTest test` and verify RED because the current record has only four fields.
4. Extend `AssistantChatResponse` with the four metadata fields and defensive `List.copyOf` handling.
5. Update every `new AssistantChatResponse(...)` call in `AssistantService.chat(...)` to use a private response factory.
6. The response factory derives:
   - `month`: first value from `targetMonths(user, message)`, or empty string if none.
   - `dataSource`: one of `FRONTEND_CONTEXT`, `BACKEND_FINANCE`, `AI_ENRICHED_FRONTEND_CONTEXT`, `AI_ENRICHED_BACKEND_FINANCE`, `SYSTEM_GUARDRAIL`, `LOCAL_RULES`.
   - `storeScope`: empty list for this P0, reserved for later explicit store IDs.
   - `warnings`: Chinese statements explaining whether the answer used visible frontend data, backend finance data, fallback rules, or guardrails.
7. Run `cd backend; mvn -q -Dtest=AssistantChatResponseTest test`.
8. Run `cd backend; mvn -q -DskipTests package`.
9. Commit: `Add assistant trust metadata`.

## Task 2: Assistant UI Source Copy

1. In `index.html`, replace `botAiHtml(resp)` and add `botAiSourceLabel(resp)` plus `botVisibleScopeText()`.
2. Keep existing compatibility fallback from `resp.source` when new metadata is absent.
3. Render one assistant card with:
   - top label: DeepSeek or backend/local answer.
   - sub label: data source, month if present, current visible scope.
   - warnings if present.
   - escaped answer HTML.
4. Run script syntax check by extracting the `<script>` block and running `node --check`.
5. Browser smoke the assistant on `http://127.0.0.1:8080/index.html`.
6. Commit: `Show assistant data source in UI`.

## Task 3: Preserve Dashboard and Import Scope Guards

1. Confirm `renderDash()` starts from `const scopedStores=visibleStores();`.
2. Confirm trend uses `scopedStores`, not `STORES`.
3. Confirm import helpers exist:
   - `importSelectedMonth()`
   - `importMonthMismatch(row)`
   - `importMonthMismatchNotice(row,i)`
4. Confirm `importCommitSelected()` blocks unconfirmed mismatched rows.
5. Run `cd backend; mvn -q -Dtest=SpreadsheetProfitParserTest test`.
6. If verification shows no code change, do not commit this task.

## Task 4: Screenshot Recognition Status

1. Update screenshot panel copy to say the current version previews images only and visual recognition is not configured.
2. Update `aiBtn` click handler so it always gives explicit unconfigured status, even when no image has been selected.
3. Preserve `aiFile` and paste preview behavior.
4. Run script syntax check with `node --check`.
5. Browser smoke the data-entry screenshot controls.
6. Commit: `Clarify screenshot recognition status`.

## Task 5: Escape Handling

1. Extend `closeActiveModalByEscape()` with an import-preview branch:
   - hide `#importPreview`
   - clear its HTML
   - clear `#aiStatus`
   - return `true`
2. Keep existing branches for salary, expense, store salary, and store modal.
3. The inspection form is rendered inline in the current page, not as an overlay modal, so no inspection-modal branch is added in this P0.
4. Run script syntax check with `node --check`.
5. Browser smoke Escape for import preview and existing modal flows.
6. Commit: `Harden modal escape behavior`.

## Task 6: Final Verification

1. Run `cd backend; mvn -q test`.
2. Run `cd backend; mvn -q -DskipTests package`.
3. Run script syntax check with `node --check`.
4. With 8080 running, login to `/api/auth/login` as boss and call `/api/assistant/chat`; response JSON must include `dataSource` and `month`.
5. Browser smoke:
   - boss dashboard shows all visible stores.
   - store-manager dashboard shows one store.
   - assistant card shows source/scope copy.
   - import month conflict remains blocked until confirmed.
   - screenshot status says recognition is not configured.
   - Escape closes active import preview or modal.
6. Run `git status -sb`; leave only unrelated/untracked `output/` if present.

## Self-Review

- No secrets are introduced.
- No `web/` files are touched.
- Existing assistant JSON fields remain compatible.
- Import parser behavior is verified before changing import UI.
- Untracked `output/` stays out of commits.
