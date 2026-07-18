#!/usr/bin/env python
"""Generate idempotent SQL for loss_item_config from the approved Excel price rows.

This script intentionally reads only:
- 每日报损表: row 2 item names, row 35 unit prices
- 水果检查表: row 2 item names, row 35 unit prices

It does not read or import any dated daily report rows.
"""

from __future__ import annotations

import argparse
import re
from decimal import Decimal, InvalidOperation
from pathlib import Path

import openpyxl


SHEETS = ("每日报损表", "水果检查表")
SPECIAL_UNITS = {
    "新鲜牛油果": "个",
}


def sql_string(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def normalized_name(raw: object) -> str:
    text = "" if raw is None else str(raw)
    text = re.sub(r"[\r\n]+", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"[（(]\s*单位\s*[:：]\s*[^）)]+[）)]", "", text).strip()
    return re.sub(r"\s+", "", text)


def unit_from_label(raw: object, item_name: str) -> str:
    text = "" if raw is None else str(raw)
    match = re.search(r"[（(]\s*单位\s*[:：]\s*([^）)]+)[）)]", text)
    if match:
        return match.group(1).strip()
    if item_name in SPECIAL_UNITS:
        return SPECIAL_UNITS[item_name]
    return "克"


def decimal_price(raw: object) -> Decimal | None:
    if raw is None or raw == "":
        return None
    try:
        return Decimal(str(raw)).quantize(Decimal("0.0001"))
    except (InvalidOperation, ValueError):
        return None


def item_code(sheet_name: str, column_index: int) -> str:
    prefix = "DAILY_LOSS" if sheet_name == "每日报损表" else "FRUIT_CHECK"
    return f"{prefix}_{column_index:03d}"


def extract_rows(xlsx_path: Path, tenant_id: int) -> list[dict[str, object]]:
    workbook = openpyxl.load_workbook(xlsx_path, data_only=True, read_only=True)
    rows: list[dict[str, object]] = []
    for sheet_name in SHEETS:
        if sheet_name not in workbook.sheetnames:
            raise SystemExit(f"缺少工作表: {sheet_name}")
        sheet = workbook[sheet_name]
        for column in range(1, sheet.max_column + 1):
            raw_name = sheet.cell(2, column).value
            price = decimal_price(sheet.cell(35, column).value)
            name = normalized_name(raw_name)
            if not name or price is None:
                continue
            if name == "日期":
                continue
            rows.append({
                "tenant_id": tenant_id,
                "item_code": item_code(sheet_name, column),
                "item_name": name,
                "category": sheet_name,
                "unit": unit_from_label(raw_name, name),
                "unit_price": price,
                "source_sheet": sheet_name,
            })
    return rows


def build_sql(rows: list[dict[str, object]]) -> str:
    values = []
    for row in rows:
        values.append(
            "("
            + ", ".join([
                str(row["tenant_id"]),
                sql_string(str(row["item_code"])),
                sql_string(str(row["item_name"])),
                sql_string(str(row["category"])),
                sql_string(str(row["unit"])),
                str(row["unit_price"]),
                sql_string(str(row["source_sheet"])),
                "1",
                "current_timestamp",
                "current_timestamp",
            ])
            + ")"
        )
    return """insert into loss_item_config(
  tenant_id, item_code, item_name, category, unit, unit_price, source_sheet,
  active, created_at, updated_at
)
values
  {values}
on duplicate key update
  item_name = values(item_name),
  category = values(category),
  unit = values(unit),
  unit_price = values(unit_price),
  source_sheet = values(source_sheet),
  active = values(active),
  updated_at = current_timestamp;
""".format(values=",\n  ".join(values))


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate loss_item_config SQL from Excel price rows.")
    parser.add_argument("--xlsx", required=True, help="Path to 每克损耗单价表 Excel file")
    parser.add_argument("--tenant-id", type=int, default=1, help="Target tenant id, default 1")
    parser.add_argument("--out", help="Output .sql path. Prints to stdout when omitted.")
    args = parser.parse_args()

    rows = extract_rows(Path(args.xlsx), args.tenant_id)
    sql = build_sql(rows)
    if args.out:
        Path(args.out).write_text(sql, encoding="utf-8")
    else:
        print(sql)
    print(f"-- extracted {len(rows)} loss item config rows", flush=True)


if __name__ == "__main__":
    main()
