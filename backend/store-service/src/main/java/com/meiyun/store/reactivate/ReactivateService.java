package com.meiyun.store.reactivate;

import com.meiyun.security.DataScope;
import com.meiyun.store.consumable.ConsumableAuditRecorder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReactivateService {

    static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Set<String> CHANNELS = Set.of("PHONE", "WECHAT", "SMS");

    private static final Map<String, String> ASSIGNEE_FULL = Map.of(
            "林微", "林微（资深咨询师）",
            "白桥", "白桥（私域运营）",
            "苏晴", "苏晴（店长）");

    private final ReactivateRepository repo;
    private final ReactivateLogRepository logRepo;
    private final RcNoGenerator noGen;
    private final ConsumableAuditRecorder audit;

    public ReactivateService(ReactivateRepository repo,
                             ReactivateLogRepository logRepo,
                             RcNoGenerator noGen,
                             ConsumableAuditRecorder audit) {
        this.repo = repo;
        this.logRepo = logRepo;
        this.noGen = noGen;
        this.audit = audit;
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "沉睡客户不存在");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String filterStoreCode) {
        List<ReactivateCustomer> rows;
        if ("__NONE__".equals(filterStoreCode)) {
            rows = List.of();
        } else {
            Specification<ReactivateCustomer> spec = DataScope.storeSpec("storeCode");
            if (filterStoreCode != null && !filterStoreCode.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), filterStoreCode.trim()));
            }
            rows = repo.findAll(spec, Sort.by(Sort.Direction.DESC, "lastVisitDays"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReactivateCustomer r : rows) out.add(toView(r));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        return toView(mustGet(id));
    }

    @Transactional
    public Map<String, Object> assign(Long id, AssignCmd cmd, String actor) {
        if (cmd == null) throw badReq("请提供指派内容");
        String assignee = normalizeAssignee(cmd.assignee());
        String channel = cmd.channel() == null ? "" : cmd.channel().trim();
        if (!CHANNELS.contains(channel)) throw badReq("唤醒渠道不合法");

        ReactivateCustomer c = mustGet(id);
        c.setAssignee(assignee);
        c.setChannel(channel);
        if (rank(c.getStatus()) < rank("ASSIGNED")) c.setStatus("ASSIGNED");
        OffsetDateTime now = OffsetDateTime.now(BIZ_ZONE);
        c.setNextFollowAt(now.plusDays(2));
        repo.save(c);

        ReactivateLog log = new ReactivateLog();
        log.setRcId(c.getId());
        log.setActionBy(actor);
        log.setActionAt(now);
        log.setAction("指派给 " + assignee + "（" + channelLabel(channel) + "）");
        log.setChannel(channel);
        logRepo.save(log);

        audit.record("REACTIVATE", c.getRcNo(), actor, "ASSIGN",
                "{\"rcNo\":" + jsonStr(c.getRcNo()) + ",\"assignee\":" + jsonStr(assignee)
                        + ",\"channel\":" + jsonStr(channel) + "}");
        return toView(c);
    }

    @Transactional
    public Map<String, Object> logVisit(Long id, VisitCmd cmd, String actor) {
        if (cmd == null) throw badReq("请提供回访内容");
        String result = cmd.result() == null ? "" : cmd.result().trim();
        if (result.isEmpty()) throw badReq("请填写回访结果");
        if (result.length() > 255) throw badReq("回访结果超出允许长度");
        boolean recovered = Boolean.TRUE.equals(cmd.recovered());

        ReactivateCustomer c = mustGet(id);
        OffsetDateTime now = OffsetDateTime.now(BIZ_ZONE);

        ReactivateLog log = new ReactivateLog();
        log.setRcId(c.getId());
        log.setActionBy(c.getAssignee() == null ? actor : c.getAssignee());
        log.setActionAt(now);
        log.setAction(recovered ? "客户已挽回" : "回访记录");
        log.setResult(result);
        logRepo.save(log);

        if (recovered) {
            c.setStatus("RECOVERED");
            c.setLastVisitDays(0);
            c.setTier("T30");
        } else if (!"RECOVERED".equals(c.getStatus())) {
            c.setStatus("VISITED");
        }
        repo.save(c);

        audit.record("REACTIVATE", c.getRcNo(), actor, "LOG_VISIT",
                "{\"rcNo\":" + jsonStr(c.getRcNo()) + ",\"recovered\":" + recovered + "}");
        return toView(c);
    }

    public record AssignCmd(String assignee, String channel) {}

    public record VisitCmd(String result, Boolean recovered) {}

    public record SeedCustomer(String name, String level, String phone, int lastVisitDays,
                                long cardBalanceYuan, String status, String assignee,
                                String channel, OffsetDateTime assignAt,
                                OffsetDateTime followAt, OffsetDateTime visitAt,
                                String visitResult) {}

    @Transactional
    public void seed(String storeCode, String rcNo, SeedCustomer c) {
        boolean recovered = "RECOVERED".equals(c.status());
        ReactivateCustomer rc = new ReactivateCustomer();
        rc.setRcNo(rcNo);
        rc.setStoreCode(storeCode);
        rc.setName(c.name());
        rc.setLevel(c.level());
        rc.setPhone(c.phone());
        rc.setLastVisitDays(recovered ? 0 : c.lastVisitDays());
        rc.setCardBalanceFen(c.cardBalanceYuan() * 100);
        rc.setTier(recovered ? "T30" : tierOf(c.lastVisitDays()));
        rc.setStatus(c.status());
        if (c.assignee() != null) {
            rc.setAssignee(c.assignee());
            rc.setChannel(c.channel());
        }
        rc.setNextFollowAt(c.followAt());
        repo.save(rc);

        if (c.assignee() != null && c.assignAt() != null) {
            ReactivateLog al = new ReactivateLog();
            al.setRcId(rc.getId());
            al.setActionBy("苏晴");
            al.setActionAt(c.assignAt());
            al.setAction("指派给 " + c.assignee() + "（" + channelLabel(c.channel()) + "）");
            al.setChannel(c.channel());
            logRepo.save(al);
        }
        if (("VISITED".equals(c.status()) || "RECOVERED".equals(c.status())) && c.visitAt() != null) {
            ReactivateLog vl = new ReactivateLog();
            vl.setRcId(rc.getId());
            vl.setActionBy(c.assignee() == null ? "系统" : c.assignee());
            vl.setActionAt(c.visitAt());
            vl.setAction(recovered ? "客户已挽回" : "回访记录");
            vl.setResult(c.visitResult());
            logRepo.save(vl);
        }
    }

    private ReactivateCustomer mustGet(Long id) {
        return repo.findById(id).orElseThrow(ReactivateService::notFound);
    }

    private static String normalizeAssignee(String raw) {
        if (raw == null) throw badReq("请选择唤醒负责人");
        String v = raw.trim();
        if (v.isEmpty()) throw badReq("请选择唤醒负责人");
        if (ASSIGNEE_FULL.containsKey(v)) return v;
        for (Map.Entry<String, String> e : ASSIGNEE_FULL.entrySet()) {
            if (e.getValue().equals(v)) return e.getKey();
        }
        throw badReq("唤醒负责人不合法");
    }

    private static int rank(String status) {
        return switch (status) {
            case "PENDING" -> 0;
            case "ASSIGNED" -> 1;
            case "VISITED" -> 2;
            case "RECOVERED" -> 3;
            default -> -1;
        };
    }

    static String tierOf(int days) {
        if (days >= 90) return "T90";
        if (days >= 60) return "T60";
        return "T30";
    }

    private static String channelLabel(String channel) {
        return switch (channel) {
            case "PHONE" -> "电话";
            case "WECHAT" -> "企业微信";
            case "SMS" -> "短信";
            default -> "";
        };
    }

    private Map<String, Object> toView(ReactivateCustomer c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("no", c.getRcNo());
        row.put("storeCode", c.getStoreCode());
        row.put("name", c.getName());
        row.put("level", c.getLevel());
        row.put("phone", c.getPhone());
        row.put("lastVisitDays", c.getLastVisitDays());
        row.put("cardBalance", (c.getCardBalanceFen() == null ? 0 : c.getCardBalanceFen()) / 100);
        row.put("tier", c.getTier());
        row.put("status", c.getStatus());
        row.put("assignee", c.getAssignee() == null ? null
                : ASSIGNEE_FULL.getOrDefault(c.getAssignee(), c.getAssignee()));
        row.put("channel", c.getChannel());
        row.put("nextFollowAt", c.getNextFollowAt() == null ? null : c.getNextFollowAt().toString());

        List<Map<String, Object>> logs = new ArrayList<>();
        for (ReactivateLog l : logRepo.findByRcIdOrderByIdDesc(c.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("by", l.getActionBy());
            m.put("at", l.getActionAt().toString());
            m.put("action", l.getAction());
            m.put("channel", l.getChannel());
            m.put("result", l.getResult());
            logs.add(m);
        }
        row.put("logs", logs);
        return row;
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
