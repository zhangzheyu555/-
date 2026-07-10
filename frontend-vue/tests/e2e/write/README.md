# Write E2E Tests

Default Vue3 E2E tests are read-only and must not write real business data to MySQL.

Future write-flow specs, such as warehouse requisitions, receipt confirmation, finance approval, and boss closure, must live in this folder and only run when explicitly enabled:

```powershell
$env:E2E_WRITE = '1'
npm run test:e2e:write
```

All write data must use an `E2E-YYYYMMDD-*` prefix and either clean itself up or report the created records.
