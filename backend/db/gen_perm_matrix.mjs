// 从前端 src/stores/auth.ts 的 ROLE_PERMISSIONS 提取角色→权限矩阵，
// 生成后端 org-service 的 PermissionMatrix.java（权限后端落库的唯一真源种子）。
// 用法：node backend/db/gen_perm_matrix.mjs
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const authTs = readFileSync(resolve(here, '../../frontend/src/stores/auth.ts'), 'utf8')

const STRINGS = (s) => Array.from(s.matchAll(/'([^']+)'/g), (m) => m[1])

// 1. ALL_VIEW 基线权限
const allViewBlock = authTs.match(/const ALL_VIEW = \[([\s\S]*?)\] as const/)[1]
const ALL_VIEW = STRINGS(allViewBlock)

// 2. 各角色权限块（块内无嵌套括号，非贪婪到 ], 即可）
const rpBlock = authTs.match(/const ROLE_PERMISSIONS: Record<Role, string\[\]> = \{([\s\S]*?)\n\}/)[1]
const roles = {}
for (const m of rpBlock.matchAll(/^ {2}(\w+):\s*\[([\s\S]*?)\],/gm)) {
  const [, role, body] = m
  const perms = new Set()
  if (body.includes('...ALL_VIEW')) ALL_VIEW.forEach((p) => perms.add(p))
  STRINGS(body).forEach((p) => perms.add(p))
  if (role !== 'SUPER_ADMIN') roles[role] = [...perms]
}
roles.SUPER_ADMIN = ['*']

// 3. 权限字典（去重，排除通配）
const dict = new Set()
Object.values(roles).flat().forEach((p) => p !== '*' && dict.add(p))

const order = ['SUPER_ADMIN', 'REGION_MGR', 'STORE_MGR', 'CONSULTANT', 'DOCTOR', 'FRONT_DESK', 'OPERATOR', 'FINANCE']

let java = `package com.meiyun.org;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色-权限矩阵种子（由 backend/db/gen_perm_matrix.mjs 从 frontend/src/stores/auth.ts 生成，勿手改）。
 * 权限后端落库为唯一真源：org-service 启动幂等播种 permission_def / role_permission。
 * 对齐 docs/permission-matrix.md 的 8 角色（无 STORE_MANAGER 第九角色）。
 */
public final class PermissionMatrix {

    private PermissionMatrix() {
    }

    /** 权限字典：全部权限码（resource:action[:field]）。 */
    public static final List<String> ALL_PERMISSIONS = List.of(
${[...dict].map((p) => `            "${p}"`).join(',\n')}
    );

    /** 角色 → 权限码集合（SUPER_ADMIN 为通配 *）。 */
    public static Map<String, List<String>> rolePermissions() {
        Map<String, List<String>> m = new LinkedHashMap<>();
`
for (const r of order) {
  const perms = roles[r]
  java += `        m.put("${r}", List.of(\n`
  java += perms.map((p) => `                "${p}"`).join(',\n')
  java += perms.length ? '\n        ));\n' : '));\n'
}
java += `        return m;
    }
}
`

const out = resolve(here, '../org-service/src/main/java/com/meiyun/org/PermissionMatrix.java')
writeFileSync(out, java)
console.log(`PermissionMatrix.java 生成完成：字典 ${dict.size} 条权限，角色 ${order.length} 个`)
for (const r of order) console.log(`  ${r}: ${roles[r].length} 条`)
