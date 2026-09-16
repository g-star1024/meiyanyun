package com.meiyun.security;

import java.util.List;

/**
 * 当前登录用户（由 JWT claims 解析，ThreadLocal 持有，请求结束清理）。
 *
 * @param staffId   工号
 * @param staffName 姓名
 * @param roles     角色码集合（一人可多角色，权限取并集）
 * @param storeCode 所属门店编码（区域/集团角色可空）
 * @param scope     数据域：SELF / STORE / BRAND / REGION / GROUP
 * @param perms     权限码集合（超管为 ["*"]）
 * @param devLogin  是否开发期免密登录（dev-login），留痕/水印用
 * @param region    所属大区中文（华东/华北…）；区域经理有值，门店/集团可空
 * @param stores    数据域可见门店编码集合（登录时按组织树预解析下发，服务端自包含过滤用）
 * @param realSub   超管代操作（impersonate）真实操作人工号；普通登录为 null
 * @param act       超管代操作时被切换人工号（与 staffId 同值，审计留痕冗余）；普通登录为 null
 * @param issuedAt  token 签发时刻（JWT iat，epoch 秒）；代操作退出算会话时长用，系统身份为 null
 */
public record LoginUser(String staffId, String staffName, List<String> roles,
                        String storeCode, String scope, List<String> perms,
                        boolean devLogin, String region, List<String> stores,
                        String realSub, String act, Long issuedAt) {

    /** 兼容旧调用（meiyun-security 升级前的 9 参构造）：无 impersonate 双 claim。 */
    public LoginUser(String staffId, String staffName, List<String> roles,
                     String storeCode, String scope, List<String> perms,
                     boolean devLogin, String region, List<String> stores) {
        this(staffId, staffName, roles, storeCode, scope, perms, devLogin, region, stores, null, null, null);
    }

    /** 是否处于超管代操作态（JWT 携带 realSub/act 双 claim）。 */
    public boolean impersonating() {
        return realSub != null && !realSub.isBlank();
    }

    public boolean isSuper() {
        return roles != null && roles.contains("SUPER_ADMIN");
    }

    public boolean hasPerm(String perm) {
        if (isSuper()) return true;
        if (perms == null) return false;
        return perms.contains("*") || perms.contains(perm);
    }
}
