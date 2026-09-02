#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从前端 config/dictionary.ts 解析全部字典常量，生成 sys_dictionary 种子 SQL。
映射：常量名=category，条目key=dict_code，value=dict_value，label=dict_label，
      color=dict_color，icon=dict_icon，序号=sort_order。
跳过 export type 类型别名。
"""
import re
import sys

DICT_TS = "/Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform/frontend/src/config/dictionary.ts"
OUT_SQL = "/Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform/backend/db/migration/V2__seed_dictionaries.sql"

def sql_escape(s: str) -> str:
    return s.replace("'", "''")

def main():
    with open(DICT_TS, encoding="utf-8") as f:
        text = f.read()

    # 匹配：export const XXX_NAME = { ... } as const
    const_re = re.compile(
        r"export\s+const\s+([A-Z_]+)\s*=\s*\{(.*?)\}\s*as\s+const",
        re.DOTALL,
    )
    # 匹配条目：KEY: { value: '...', label: '...', color: '...'? , icon: '...'? }
    item_re = re.compile(
        r"([A-Z0-9_]+)\s*:\s*\{([^}]*)\}",
        re.DOTALL,
    )
    field_re = re.compile(r"(value|label|color|icon)\s*:\s*'([^']*)'")

    categories = []
    total = 0
    for m in const_re.finditer(text):
        cat = m.group(1)
        body = m.group(2)
        items = []
        for im in item_re.finditer(body):
            code = im.group(1)
            fields = dict(field_re.findall(im.group(2)))
            if "value" not in fields or "label" not in fields:
                continue
            items.append({
                "code": code,
                "value": fields["value"],
                "label": fields["label"],
                "color": fields.get("color", ""),
                "icon": fields.get("icon", ""),
            })
        if items:
            categories.append((cat, items))
            total += len(items)

    # 生成 SQL
    lines = []
    lines.append("-- V2: 全站数据字典种子（由前端 config/dictionary.ts 自动生成，勿手改）")
    lines.append("-- 分类采用前端扁平枚举命名（CUSTOMER_SOURCE 等），与前端 51 类一一对应。")
    lines.append("BEGIN;")
    lines.append("")
    lines.append("-- 清空旧种子（旧大类 CUSTOMER/APPOINTMENT/ORDER 及测试数据），重置序列")
    lines.append("TRUNCATE sys_dictionary RESTART IDENTITY CASCADE;")
    lines.append("")
    lines.append("INSERT INTO sys_dictionary (category, dict_code, dict_value, dict_label, dict_color, dict_icon, sort_order, is_enabled, created_by) VALUES")
    rows = []
    for cat, items in categories:
        for idx, it in enumerate(items, start=1):
            color = f"'{sql_escape(it['color'])}'" if it["color"] else "NULL"
            icon = f"'{sql_escape(it['icon'])}'" if it["icon"] else "NULL"
            rows.append(
                f"  ('{cat}', '{sql_escape(it['code'])}', '{sql_escape(it['value'])}', "
                f"'{sql_escape(it['label'])}', {color}, {icon}, {idx}, TRUE, 0)"
            )
    lines.append(",\n".join(rows) + ";")
    lines.append("")
    lines.append("COMMIT;")
    lines.append("")

    with open(OUT_SQL, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"解析到 {len(categories)} 个分类，共 {total} 条字典项")
    print(f"SQL 已生成: {OUT_SQL}")
    # 打印分类清单核对
    for cat, items in categories:
        print(f"  {cat}: {len(items)} 条")

if __name__ == "__main__":
    main()
