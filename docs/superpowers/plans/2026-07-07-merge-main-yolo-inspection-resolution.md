# Merge Main YOLO Inspection Resolution

## Merge Context

- Current branch: `codex/deepseek-assistant`
- Merge source: `origin/main`
- Incoming commit: `cf3371b 接入本地YOLO卫生识别模型：拖拽识别照片并生成巡检Excel`
- Safety branch: `backup/pre-merge-main-20260707-c50b8f2`
- Merge mode used: `git merge --no-commit --no-ff origin/main`

## Problems Found

1. `backend/src/main/resources/application.yml` has a content conflict.
   - Current branch has `app.seed.demo-enabled` and `app.migration.auto-run`.
   - `origin/main` adds static resource no-cache, multipart upload limits, `app.inspection.detect-url`, `app.inspection.timeout`, and CORS origin `"null"`.
   - Resolution: keep all current keys and add the incoming inspection/upload/CORS settings.

2. `backend/src/main/resources/static/index.html` has a content conflict inside the inspection module.
   - Current branch has the richer inspection photo evidence workflow: `INSP_DRAFT.photos`, local `aiSuggestions`, photo grid, suggestion acceptance/ignore, and save flow.
   - `origin/main` adds drag-and-drop photo recognition, `INSP_AI_RESULTS`, local YOLO backend calls, and Excel export.
   - Resolution: preserve the current photo evidence and suggestion workflow, then layer the incoming drag/drop recognition and Excel export on top.

3. Runtime integration risk exists after conflict resolution.
   - Incoming backend adds `/api/inspections/detect` and `/api/inspections/export`.
   - These endpoints depend on a local detector configured by `app.inspection.detect-url`.
   - Resolution: verify Spring Boot starts without the detector running, and ensure UI shows a clear failure if the detector is unavailable.

4. `web/` changes are included in `origin/main`.
   - Current project mainline remains `backend/src/main/resources/static/index.html`.
   - Resolution: accept incoming `web/` changes without using them as the mainline, then verify backend static page still works.

## Step-by-Step Resolution Plan

1. Resolve `application.yml` by combining both sides:
   - keep `spring.datasource`, Flyway, `app.seed`, `app.migration`, auth, assistant settings.
   - add `spring.web.resources.cache.cachecontrol.no-cache`.
   - add `spring.servlet.multipart.max-file-size` and `max-request-size`.
   - add `app.inspection.detect-url` and `app.inspection.timeout`.
   - add CORS `"null"` origin.

2. Resolve `index.html` by integrating inspection UI:
   - keep current `inspPhotoGridHtml()` and `inspSuggestionsHtml()` output.
   - add `id="inspDropZone"` and drag/drop handlers to the existing photo card.
   - add Excel export button when `INSP_AI_RESULTS.length > 0`.
   - keep current status text that counts saved photos.
   - keep current `inspPhotoRecognize(input)` behavior, but route selected files through a shared recognizer that also preserves current local suggestion behavior.
   - add incoming `INSP_AI_RESULTS`, API base, global drop guard, `inspDropPhotos`, detector call, image shrink helpers, and `inspExportExcel`.
   - reset `INSP_AI_RESULTS` after `inspSave()`.

3. Verify syntax:
   - run `node --check` on the extracted static script.
   - run `git diff --check`.

4. Verify backend:
   - run `mvn -q test`.
   - run `mvn -q -DskipTests package` after stopping the running jar if necessary.

5. Verify API:
   - health endpoint returns UP.
   - auth login still works.
   - assistant metadata fields still exist.
   - inspection export endpoint returns a file for a minimal valid payload.

6. Verify browser:
   - boss dashboard still shows all stores.
   - assistant still shows data source.
   - inspection new form renders.
   - drag/drop recognition card exists.
   - screenshot status and Escape behavior from P0 still work.

## Do Not Do

- Do not overwrite current inspection evidence/suggestion code with `origin/main`.
- Do not remove assistant metadata commits.
- Do not remove import month conflict guards.
- Do not commit `output/`.
- Do not write database passwords or detector credentials into files.
