# AI Profit OS Mobile

UniApp Vue 3 + TypeScript + Pinia mobile client. It adds H5, WeChat Mini Program and native App candidates without replacing `frontend-vue`.

The current UI follows the AI Profit OS orange mobile design: role-based workbench metrics, task-driven navigation, 16rpx operational cards, five-tab navigation, and a server-backed business-message view. Visual references may come from the desktop `miniapp` prototype, but authentication, permissions, API contracts, data scopes, idempotency, and uploads must stay on this production mobile implementation.

## Boundaries

- The app stores one bearer token key only. Session user, permissions, store scope and all business data are fetched from the Spring Boot APIs.
- MySQL remains the only business source of truth. Do not add inventory, finance, inspection or examination persistence to local/uni storage.
- `src/platform` is the only location for `H5`, `MP-WEIXIN` and `APP-PLUS` conditional compilation.
- Mobile menu visibility and direct-page guards combine the role allowlist for phase one with backend `permissions` and `dataScopes`. Backend authorization remains authoritative.
- No app id, signing material, push credential, upload secret or production URL belongs in this repository.
- Protected training media is never persisted as business data: App streams with an Authorization header; H5 and WeChat use a short-lived video-scoped playback ticket. `manifest.json` enables the required App `VideoPlayer` module.

## 本地与预发布配置

Copy `.env.example` to an untracked environment file only for local development:

- H5 may use same-origin `/api`; `VITE_DEV_PROXY_TARGET` is an optional local development proxy target.
- The release candidate script never uses the development proxy. It receives one independent, HTTPS-only API base URL per target from the current user's environment or approved CI secret configuration: `MOBILE_STAGING_H5_API_BASE_URL`, `MOBILE_STAGING_MP_WEIXIN_API_BASE_URL`, `MOBILE_STAGING_ANDROID_API_BASE_URL`, and `MOBILE_STAGING_IOS_API_BASE_URL`.
- Do not place an API key, app signing material, or a real staging URL in this repository. The candidate manifest records only each API host and a SHA-256 hash of the normalized URL.

## Commands

Release candidates use Node 20 LTS (`.nvmrc`) and the committed lockfile. The build gate rejects Node 24 and any dirty or untracked source tree, then builds from a temporary `git archive` rather than the working tree.

```powershell
npm ci
npm run type-check
npm run build:h5
npm run build:mp-weixin
npm run build:app:android
npm run build:app:ios
```

After the source tree has been committed and is clean, create the four-target resource candidate with:

```powershell
.\scripts\build-mobile-candidates.ps1
```

The script emits a timestamped directory below `output/mobile-release-evidence/`; it never overwrites previous evidence. Android and iOS outputs remain unsigned resource candidates, so their manifest is deliberately marked **not RC** until the platform signing and real-device gates are completed.

The Android/iOS commands generate unsigned UniApp resource candidates only. APK/IPA creation, signing, store upload and production release require HBuilderX plus the relevant platform accounts and certificates and are intentionally outside phase one.
