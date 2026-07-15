#!/usr/bin/env python3
"""受控导入题库试卷。默认只预览；不会打印密码、答案或题目正文。"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
import re
import sys
from datetime import datetime
from decimal import Decimal
from pathlib import Path

import pymysql


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="受控题库试卷导入（默认 dry-run）")
    parser.add_argument("--input", required=True, type=Path, help="已人工复核的 UTF-8 JSON 文件")
    tenant = parser.add_mutually_exclusive_group(required=True)
    tenant.add_argument("--tenant-id", type=int)
    tenant.add_argument("--tenant-code", help="当前库无 tenant.code；这里按 tenant.name 精确匹配")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--database", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--operator-user-id", type=int, help="--apply 时必填，写操作日志")
    parser.add_argument("--apply", action="store_true", help="显式执行；缺省只预览")
    parser.add_argument("--confirm-sha", help="--apply 时必须与输入文件 SHA-256 完全一致")
    return parser.parse_args()


def load_document(path: Path) -> tuple[dict, str]:
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest().upper()
    document = json.loads(raw.decode("utf-8"))
    papers = document.get("papers")
    if not isinstance(papers, list) or not papers:
        raise ValueError("输入文件必须包含非空 papers 数组")
    seen: set[str] = set()
    for paper in papers:
        code = required_code(paper.get("paperCode"), "paperCode")
        if code in seen:
            raise ValueError(f"试卷编号重复：{code}")
        seen.add(code)
        if not str(paper.get("paperName") or "").strip():
            raise ValueError(f"{code} 缺少 paperName")
        questions = paper.get("questions")
        if not isinstance(questions, list) or not questions:
            raise ValueError(f"{code} 必须至少包含一道题")
        for question in questions:
            if str(question.get("questionType") or "").upper() not in {"SINGLE_CHOICE", "TEXT", "NUMBER", "ESSAY"}:
                raise ValueError(f"{code} 包含不支持的题型")
            if not str(question.get("questionText") or "").strip() or not str(question.get("standardAnswer") or "").strip():
                raise ValueError(f"{code} 的题目正文和标准答案不能为空")
            if Decimal(str(question.get("score") or 0)) <= 0:
                raise ValueError(f"{code} 的题目分值必须大于 0")
    return document, digest


def required_code(value: object, field: str) -> str:
    code = str(value or "").strip().upper()
    if not re.fullmatch(r"[A-Z0-9_\-]{2,80}", code):
        raise ValueError(f"{field} 只能包含大写字母、数字、下划线或短横线")
    return code


def connect(args: argparse.Namespace):
    password = os.environ.get("MYSQL_PASSWORD")
    if password is None:
        password = getpass.getpass("请输入 MySQL 密码（不会回显或记录）：")
    if not password:
        raise ValueError("MySQL 密码不能为空")
    return pymysql.connect(
        host=args.host, port=args.port, user=args.username, password=password,
        database=args.database, charset="utf8mb4", autocommit=False,
        cursorclass=pymysql.cursors.DictCursor,
    )


def resolve_tenant(cursor, args: argparse.Namespace) -> tuple[int, str]:
    if args.tenant_id is not None:
        cursor.execute("select id, name from tenant where id=%s", (args.tenant_id,))
    else:
        cursor.execute("select id, name from tenant where name=%s", (args.tenant_code,))
    row = cursor.fetchone()
    if not row:
        raise ValueError("目标租户不存在")
    return int(row["id"]), str(row["name"])


def normalized_paper(paper: dict) -> dict:
    return {
        "paperName": str(paper.get("paperName") or "").strip(),
        "roleScope": str(paper.get("roleScope") or "").strip(),
        "passScore": str(Decimal(str(paper.get("passScore", 80))).quantize(Decimal("0.01"))),
        "enabled": bool(paper.get("enabled", True)),
        "questions": [{
            "questionType": str(q["questionType"]).upper(),
            "questionText": str(q["questionText"]).strip(),
            "options": q.get("options") or [],
            "standardAnswer": str(q["standardAnswer"]).strip(),
            "acceptKeywords": str(q.get("acceptKeywords") or "").strip(),
            "score": str(Decimal(str(q["score"])).quantize(Decimal("0.01"))),
            "sortOrder": int(q.get("sortOrder") or index + 1),
        } for index, q in enumerate(paper["questions"])],
    }


def current_paper(cursor, tenant_id: int, code: str) -> dict | None:
    cursor.execute(
        "select id, paper_name, role_scope, pass_score, enabled from training_exam_paper "
        "where tenant_id=%s and paper_code=%s", (tenant_id, code))
    row = cursor.fetchone()
    if not row:
        return None
    cursor.execute(
        "select question_type, question_text, options_json, standard_answer, accept_keywords, score, sort_order "
        "from training_exam_question where tenant_id=%s and paper_id=%s order by sort_order, id",
        (tenant_id, row["id"]))
    questions = []
    for item in cursor.fetchall():
        questions.append({
            "questionType": item["question_type"], "questionText": item["question_text"],
            "options": json.loads(item["options_json"] or "[]"), "standardAnswer": item["standard_answer"],
            "acceptKeywords": item["accept_keywords"] or "", "score": str(Decimal(item["score"]).quantize(Decimal("0.01"))),
            "sortOrder": int(item["sort_order"]),
        })
    cursor.execute(
        "select count(*) as amount from training_exam_attempt where tenant_id=%s and paper_id=%s",
        (tenant_id, row["id"]))
    return {
        "id": int(row["id"]), "attempts": int(cursor.fetchone()["amount"]),
        "normalized": {
            "paperName": row["paper_name"], "roleScope": row["role_scope"] or "",
            "passScore": str(Decimal(row["pass_score"]).quantize(Decimal("0.01"))),
            "enabled": bool(row["enabled"]), "questions": questions,
        },
    }


def make_plan(cursor, tenant_id: int, document: dict) -> list[dict]:
    plan: list[dict] = []
    incoming_codes = set()
    for paper in document["papers"]:
        code = required_code(paper["paperCode"], "paperCode")
        incoming_codes.add(code)
        existing = current_paper(cursor, tenant_id, code)
        desired = normalized_paper(paper)
        if not existing:
            action = "ADD"
        elif existing["normalized"] == desired:
            action = "NO_CHANGE"
        elif existing["attempts"] > 0:
            action = "NEW_VERSION"
        else:
            action = "UPDATE"
        plan.append({"action": action, "code": code, "questions": len(desired["questions"]), "existing": existing, "paper": paper})
    prefix = str(document.get("managedCodePrefix") or "").strip().upper()
    if document.get("deactivateMissing") is True:
        if not prefix:
            raise ValueError("deactivateMissing=true 时必须提供 managedCodePrefix")
        cursor.execute(
            "select id, paper_code from training_exam_paper where tenant_id=%s and enabled=1 and paper_code like %s",
            (tenant_id, prefix + "%"))
        for row in cursor.fetchall():
            if row["paper_code"] not in incoming_codes:
                plan.append({"action": "DISABLE", "code": row["paper_code"], "paperId": int(row["id"]), "questions": 0})
    return plan


def print_plan(tenant_id: int, tenant_name: str, digest: str, plan: list[dict]) -> None:
    counts = {key: sum(1 for item in plan if item["action"] == key) for key in ("ADD", "UPDATE", "NEW_VERSION", "DISABLE", "NO_CHANGE")}
    question_count = sum(item["questions"] for item in plan if item["action"] in {"ADD", "UPDATE", "NEW_VERSION"})
    print(f"目标租户: {tenant_name} (ID={tenant_id})")
    print(f"输入 SHA-256: {digest}")
    print("变更预览: " + ", ".join(f"{key}={value}" for key, value in counts.items()) + f", 题目={question_count}")
    for item in plan:
        print(f"- {item['action']}: {item['code']} ({item['questions']} 题)")


def insert_questions(cursor, tenant_id: int, paper_id: int, paper: dict) -> None:
    for index, question in enumerate(normalized_paper(paper)["questions"]):
        cursor.execute(
            "insert into training_exam_question "
            "(tenant_id,paper_id,question_type,question_text,options_json,standard_answer,accept_keywords,score,sort_order,enabled) "
            "values (%s,%s,%s,%s,%s,%s,%s,%s,%s,1)",
            (tenant_id, paper_id, question["questionType"], question["questionText"],
             json.dumps(question["options"], ensure_ascii=False), question["standardAnswer"],
             question["acceptKeywords"] or None, question["score"], question["sortOrder"]),
        )


def apply_plan(cursor, tenant_id: int, operator_id: int, digest: str, plan: list[dict]) -> None:
    cursor.execute("select display_name from auth_user where id=%s and tenant_id=%s and enabled=1", (operator_id, tenant_id))
    operator = cursor.fetchone()
    if not operator:
        raise ValueError("操作人不存在、已停用或不属于目标租户")
    stamp = datetime.now().strftime("%Y%m%d%H%M%S")
    for item in plan:
        action = item["action"]
        if action == "NO_CHANGE":
            continue
        if action == "DISABLE":
            cursor.execute("update training_exam_paper set enabled=0, updated_at=now() where tenant_id=%s and id=%s", (tenant_id, item["paperId"]))
            target_id = str(item["paperId"])
        else:
            paper = item["paper"]
            normalized = normalized_paper(paper)
            code = item["code"]
            if action == "NEW_VERSION":
                cursor.execute("update training_exam_paper set enabled=0, updated_at=now() where tenant_id=%s and id=%s", (tenant_id, item["existing"]["id"]))
                code = (code[:62] + "__V" + stamp)[:80]
                item["versionCode"] = code
            if action in {"ADD", "NEW_VERSION"}:
                cursor.execute(
                    "insert into training_exam_paper (tenant_id,paper_code,paper_name,role_scope,pass_score,enabled) values (%s,%s,%s,%s,%s,%s)",
                    (tenant_id, code, normalized["paperName"], normalized["roleScope"] or None, normalized["passScore"], normalized["enabled"]),
                )
                paper_id = cursor.lastrowid
            else:
                paper_id = item["existing"]["id"]
                cursor.execute("delete from training_exam_paper_question_link where tenant_id=%s and paper_question_id in (select id from training_exam_question where tenant_id=%s and paper_id=%s)", (tenant_id, tenant_id, paper_id))
                cursor.execute("delete from training_exam_question where tenant_id=%s and paper_id=%s", (tenant_id, paper_id))
                cursor.execute(
                    "update training_exam_paper set paper_name=%s,role_scope=%s,pass_score=%s,enabled=%s,updated_at=now() where tenant_id=%s and id=%s",
                    (normalized["paperName"], normalized["roleScope"] or None, normalized["passScore"], normalized["enabled"], tenant_id, paper_id),
                )
            insert_questions(cursor, tenant_id, paper_id, paper)
            target_id = str(paper_id)
        cursor.execute(
            "insert into operation_log (tenant_id,operator_id,operator_name,action,target_type,target_id,reason) values (%s,%s,%s,%s,'training_exam_paper',%s,%s)",
            (tenant_id, operator_id, operator["display_name"], "受控导入试卷", target_id, f"{action}; input_sha256={digest}"),
        )


def main() -> int:
    args = arguments()
    try:
        document, digest = load_document(args.input)
        if args.apply and (not args.confirm_sha or args.confirm_sha.upper() != digest):
            raise ValueError("--apply 必须同时提供与预览一致的 --confirm-sha")
        if args.apply and not args.operator_user_id:
            raise ValueError("--apply 必须提供 --operator-user-id")
        connection = connect(args)
        try:
            with connection.cursor() as cursor:
                tenant_id, tenant_name = resolve_tenant(cursor, args)
                plan = make_plan(cursor, tenant_id, document)
                print_plan(tenant_id, tenant_name, digest, plan)
                if not args.apply:
                    print("DRY-RUN：未写入数据库。确认后使用 --apply --confirm-sha <上述哈希>。")
                    connection.rollback()
                    return 0
                apply_plan(cursor, tenant_id, args.operator_user_id, digest, plan)
            connection.commit()
            print("APPLIED：已提交受控变更并写入操作日志。")
            return 0
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()
    except Exception as error:
        print(f"导入停止：{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
