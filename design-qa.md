**Findings**
- No P0/P1/P2 issues remain for the `/profit-table` old-HTML visual parity pass.

**Evidence**
- Source visual truth path: `output/ui-parity/old-profit-table.png`
- Implementation screenshot path: `output/ui-parity/vue-profit-table-after.png`
- Full-view comparison evidence: `output/ui-parity/profit-table-comparison.png`
- Additional implementation state: `output/ui-parity/vue-profit-table-all-after.png`
- Viewport: 1920x1080 desktop
- State: boss logged in, `/profit-table?mode=single&storeId=rx13`, 2026-07
- Focused region comparison evidence: the side-by-side full-page comparison is readable for the required surfaces: header, segmented mode switch, filter card, single-store statement card, table row hierarchy, and amount alignment. The all-store summary state was captured separately.

**Required Fidelity Surfaces**
- Fonts and typography: Vue3 now follows the old HTML business-report hierarchy: small top controls, compact report title, 13.5px statement rows, bold totals, and tabular numeric alignment.
- Spacing and layout rhythm: removed the heavy outer white page shell, reduced the filter section height, restored the light white filter card, and matched the old statement card width, padding, row rhythm, and rounded shadow treatment.
- Colors and visual tokens: segmented active state uses the old orange-to-brown emphasis; section headings use the old orange; totals and positive net profit use green; muted rows and empty values use the old subdued gray treatment.
- Image quality and asset fidelity: this screen has no product imagery beyond existing UI icons. No placeholder images or generated assets are used.
- Copy and content: Vue3 shows the old-page business anchors: `利润表`, `单店利润表`, `全部门店汇总`, `营业总收入`, `实收收入`, `成本`, `费用`, `净利润`. It does not show `财务工作台`, `暂未迁移`, or `第一阶段占位`.

**Patches Made**
- Rebuilt `ProfitTablePage.vue` as a lightweight legacy-style report page instead of a nested dashboard/form page.
- Removed the profit-risk action cards from `/profit-table`; this route now focuses on single-store profit statement and all-store summary.
- Added the old-style segmented mode switch, filter card, single-store statement table, and all-store summary table.
- Added Playwright regression coverage for the legacy report structure.

**Open Questions**
- Old and Vue3 screenshots may show different selected stores or amounts because Vue3 is API-backed and route-driven. The visual structure is aligned; values remain live data.

**Implementation Checklist**
- Keep `/profit-table` using API-backed profit data.
- Apply the same old-HTML parity standard next to `/store-detail`, `/data-entry`, and `/expenses`.

**Follow-up Polish**
- P3: If the user wants closer pixel matching, tune the report card max width and row height against a fixed old screenshot state with the same selected store.

final result: passed
