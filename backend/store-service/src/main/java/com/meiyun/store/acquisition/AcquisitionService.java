package com.meiyun.store.acquisition;

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
public class AcquisitionService {

    static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Set<String> TYPES = Set.of("TRIAL", "GROUP", "REFERRAL");

    private static final long MAX_FEN = 100_000_000L * 100;

    private final AcquisitionRepository repo;
    private final AqNoGenerator noGen;
    private final ConsumableAuditRecorder audit;

    public AcquisitionService(AcquisitionRepository repo, AqNoGenerator noGen, ConsumableAuditRecorder audit) {
        this.repo = repo;
        this.noGen = noGen;
        this.audit = audit;
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "拓客活动不存在");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String filterStoreCode) {
        List<AcquisitionCampaign> rows;
        if ("__NONE__".equals(filterStoreCode)) {
            rows = List.of();
        } else {
            Specification<AcquisitionCampaign> spec = DataScope.storeSpec("storeCode");
            if (filterStoreCode != null && !filterStoreCode.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), filterStoreCode.trim()));
            }
            rows = repo.findAll(spec, Sort.by(Sort.Direction.DESC, "startDate"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (AcquisitionCampaign r : rows) out.add(toView(r));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        return toView(mustGet(id));
    }

    @Transactional
    public synchronized Map<String, Object> create(String storeCode, CreateCmd cmd, String actor) {
        if (cmd == null) throw badReq("请提供拓客活动内容");
        String name = cmd.name() == null ? "" : cmd.name().trim();
        if (name.isEmpty()) throw badReq("请填写活动名称");
        if (name.length() > 64) throw badReq("活动名称超出允许长度");
        String type = cmd.type() == null ? "" : cmd.type().trim();
        if (!TYPES.contains(type)) throw badReq("活动类型不合法");
        long budgetFen = checkFen(cmd.budget(), "活动预算");
        String channel = cmd.channel() == null ? "" : cmd.channel().trim();
        if (channel.isEmpty()) channel = "私域社群";
        if (channel.length() > 128) throw badReq("投放渠道超出允许长度");
        String owner = cmd.owner() == null ? "" : cmd.owner().trim();
        if (owner.isEmpty()) owner = actor;
        if (owner.length() > 64) throw badReq("负责人超出允许长度");

        OffsetDateTime now = OffsetDateTime.now(BIZ_ZONE);
        OffsetDateTime start = parseDate(cmd.startDate(), now);
        OffsetDateTime end = parseDate(cmd.endDate(), start.plusDays(30));
        if (end.isBefore(start)) throw badReq("结束时间不能早于开始时间");

        AcquisitionCampaign r = new AcquisitionCampaign();
        r.setAqNo(noGen.nextAqNo());
        r.setStoreCode(storeCode);
        r.setName(name);
        r.setType(type);
        r.setExposure(0);
        r.setArrival(0);
        r.setDeal(0);
        r.setBudgetFen(budgetFen);
        r.setSpentFen(0L);
        r.setStatus("DRAFT");
        r.setStartDate(start);
        r.setEndDate(end);
        r.setOwner(owner);
        r.setChannel(channel);
        repo.save(r);
        audit.record("ACQUISITION", r.getAqNo(), actor, "CREATE",
                "{\"aqNo\":" + jsonStr(r.getAqNo()) + ",\"name\":" + jsonStr(r.getName())
                        + ",\"type\":" + jsonStr(r.getType()) + "}");
        return toView(r);
    }

    @Transactional
    public Map<String, Object> launch(Long id, String actor) {
        AcquisitionCampaign r = mustGet(id);
        if (!"DRAFT".equals(r.getStatus())) throw badReq("仅草稿状态的活动可启用");
        r.setStatus("ONGOING");
        repo.save(r);
        audit.record("ACQUISITION", r.getAqNo(), actor, "LAUNCH",
                "{\"aqNo\":" + jsonStr(r.getAqNo()) + "}");
        return toView(r);
    }

    @Transactional
    public Map<String, Object> end(Long id, String actor) {
        AcquisitionCampaign r = mustGet(id);
        if (!"ONGOING".equals(r.getStatus())) throw badReq("仅进行中的活动可结束");
        r.setStatus("ENDED");
        r.setEndDate(OffsetDateTime.now(BIZ_ZONE));
        repo.save(r);
        audit.record("ACQUISITION", r.getAqNo(), actor, "END",
                "{\"aqNo\":" + jsonStr(r.getAqNo()) + "}");
        return toView(r);
    }

    public record CreateCmd(String name, String type, Long budget, String channel,
                            String startDate, String endDate, String owner) {}

    public record SeedCampaign(String name, String type, int exposure, int arrival, int deal,
                               long budgetYuan, long spentYuan, String status, int startAgo,
                               String owner, String channel) {}

    @Transactional
    public void seed(String storeCode, String aqNo, SeedCampaign c) {
        OffsetDateTime now = OffsetDateTime.now(BIZ_ZONE);
        OffsetDateTime start = now.minusDays(c.startAgo());
        OffsetDateTime end = start.plusDays(30);
        AcquisitionCampaign r = new AcquisitionCampaign();
        r.setAqNo(aqNo);
        r.setStoreCode(storeCode);
        r.setName(c.name());
        r.setType(c.type());
        r.setExposure(c.exposure());
        r.setArrival(c.arrival());
        r.setDeal(c.deal());
        r.setBudgetFen(c.budgetYuan() * 100);
        r.setSpentFen(c.spentYuan() * 100);
        r.setStatus(c.status());
        r.setStartDate(start);
        r.setEndDate(end);
        r.setOwner(c.owner());
        r.setChannel(c.channel());
        repo.save(r);
    }

    private AcquisitionCampaign mustGet(Long id) {
        return repo.findById(id).orElseThrow(AcquisitionService::notFound);
    }

    private static long checkFen(Long yuan, String label) {
        if (yuan == null) throw badReq("请提供" + label);
        if (yuan < 0) throw badReq(label + "不能为负数");
        long fen = yuan * 100;
        if (fen > MAX_FEN) throw badReq(label + "超出允许范围");
        return fen;
    }

    private static OffsetDateTime parseDate(String s, OffsetDateTime fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return OffsetDateTime.parse(s.trim());
        } catch (Exception e) {
            throw badReq("时间格式不合法");
        }
    }

    private Map<String, Object> toView(AcquisitionCampaign r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("no", r.getAqNo());
        row.put("storeCode", r.getStoreCode());
        row.put("name", r.getName());
        row.put("type", r.getType());
        row.put("exposure", r.getExposure());
        row.put("arrival", r.getArrival());
        row.put("deal", r.getDeal());
        row.put("budget", (r.getBudgetFen() == null ? 0 : r.getBudgetFen()) / 100);
        row.put("spent", (r.getSpentFen() == null ? 0 : r.getSpentFen()) / 100);
        row.put("status", r.getStatus());
        row.put("startDate", r.getStartDate().toString());
        row.put("endDate", r.getEndDate().toString());
        row.put("owner", r.getOwner());
        row.put("channel", r.getChannel());
        return row;
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
