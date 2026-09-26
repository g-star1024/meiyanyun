package com.meiyun.customer;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户旅程只读聚合（M3-07 / DESIGN-M3 §3 D2 D5 D6）：
 * 六阶段时间轴 APPT 预约 / ARRIVE 到店 / CONSULT 咨询 / PAY 消费 / FOLLOW 回访 / REBUY 复购，
 * JdbcTemplate 直读 appointment/arrival/consultation/txn_order/followup/repurchase 六张既有表
 * （T2 直读范式，MergeExecuteService 跨域直读先例），零新表零迁移。
 *
 * <p>touch_event（V52）按 DDL「仅存不算」语义不进节点；仅取窗口内最近触点时刻参与风险分级
 * （DESIGN §3 D2 点名 touch_event 的落点：有近期触点者流失风险降级）。</p>
 *
 * <p>候选客户＝窗口内六阶段表任一事件的客户（UNION），主档读侧过滤横切：
 * anonymized_at / merged_into 非空剔除（CustomerRepository 撞单/匿名化读侧过滤惯例），
 * 门店数据域 SQL 等价 DataScope.storeSpec（SELF 域加 owner_staff_id＝本人，ownedSpec 语义）。</p>
 *
 * <p>性能口径（DESIGN §6）：限时段（days 默认 90，钳 7..365）＋列表 limit（默认 50，钳 1..200）；
 * KPI 四卡基于候选全集计算（不随 limit 截断）。金额列库内为分，出口 DTO 转元（全站金额口径）。</p>
 */
@Service
public class JourneyService {

    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");
    private static final String[] STAGES = {"APPT", "ARRIVE", "CONSULT", "PAY", "FOLLOW", "REBUY"};
    private static final String[] STAGE_TITLES = {"预约到店", "到店接待", "咨询面诊", "消费支付", "回访关怀", "复购升单"};

    public record JourneyNodeView(String stage, String date, String title, String desc,
                                  Double amount, String operator, boolean done) {}

    public record JourneyCustomerView(String id, String name, String avatarLetter, String phoneMask,
                                      String level, String currentStage, String risk,
                                      List<JourneyNodeView> nodes) {}

    public record JourneyKpiView(int inProgress, int convertedThisWeek, int avgDays, int churnRisk) {}

    public record JourneyView(int days, JourneyKpiView kpi, List<JourneyCustomerView> customers) {}

    private final JdbcTemplate jdbc;

    public JourneyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JourneyView view(int days, int limit) {
        int d = Math.min(Math.max(days, 7), 365);
        int lim = Math.min(Math.max(limit, 1), 200);
        LocalDate today = LocalDate.now(BIZ_ZONE);
        LocalDate fromDate = today.minusDays(d);
        OffsetDateTime from = fromDate.atStartOfDay(BIZ_ZONE).toOffsetDateTime();

        // 1) 候选客户主档：窗口内六阶段表任一事件（UNION）＋读侧过滤（匿名化/已合并剔除）＋门店数据域
        List<Object> custParams = new ArrayList<>();
        custParams.add(fromDate);
        custParams.add(from);
        custParams.add(from);
        custParams.add(from);
        custParams.add(fromDate);
        custParams.add(from);
        String custSql = """
                SELECT c.customer_id, c.name, c.phone, c.level, c.status
                  FROM customer c
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                   AND c.customer_id IN (
                         SELECT customer_id FROM appointment WHERE customer_id IS NOT NULL AND appt_date >= ?
                         UNION SELECT customer_id FROM arrival WHERE arrived_at >= ?
                         UNION SELECT customer_id FROM consultation WHERE created_at >= ?
                         UNION SELECT customer_id FROM txn_order WHERE created_at >= ?
                         UNION SELECT customer_id FROM followup WHERE plan_date >= ?
                         UNION SELECT customer_id FROM repurchase WHERE created_at >= ?
                   )
                """ + customerStoreClause(DataScope.current(), custParams);
        Map<String, Cust> custs = new LinkedHashMap<>();
        jdbc.query(custSql, rs -> {
            Cust c = new Cust();
            c.id = rs.getString("customer_id");
            c.name = rs.getString("name");
            c.phone = rs.getString("phone");
            c.level = rs.getString("level");
            c.status = rs.getString("status");
            custs.put(c.id, c);
        }, custParams.toArray());
        if (custs.isEmpty()) {
            return new JourneyView(d, new JourneyKpiView(0, 0, 0, 0), List.of());
        }

        // 2) 六阶段表窗口内全拉（各一次查询，ORDER BY 时间 DESC → 每客户每阶段首行即最新），内存归并
        jdbc.query("""
                SELECT customer_id, project, appt_date, appt_time, doctor, source, status, arrived_at
                  FROM appointment WHERE customer_id IS NOT NULL AND appt_date >= ?
                 ORDER BY appt_date DESC, appt_time DESC
                """, rs -> {
            Cust c = custs.get(rs.getString("customer_id"));
            if (c == null || c.appt != null) {
                return;
            }
            c.appt = new ApptRow(rs.getString("project"), rs.getObject("appt_date", LocalDate.class),
                    rs.getString("appt_time"), rs.getString("doctor"), rs.getString("source"),
                    rs.getString("status"), rs.getObject("arrived_at", OffsetDateTime.class));
            c.bumpEvent(c.appt.arrivedAt() != null ? c.appt.arrivedAt()
                    : c.appt.date().atStartOfDay(BIZ_ZONE).toOffsetDateTime());
        }, fromDate);

        jdbc.query("""
                SELECT customer_id, channel, status, arrived_at, done_at, operator
                  FROM arrival WHERE arrived_at >= ?
                 ORDER BY arrived_at DESC
                """, rs -> {
            Cust c = custs.get(rs.getString("customer_id"));
            if (c == null || c.arrive != null) {
                return;
            }
            c.arrive = new ArriveRow(rs.getString("channel"), rs.getString("status"),
                    rs.getObject("arrived_at", OffsetDateTime.class),
                    rs.getObject("done_at", OffsetDateTime.class), rs.getString("operator"));
            c.bumpEvent(c.arrive.arrivedAt());
        }, from);

        jdbc.query("""
                SELECT customer_id, needs, consultant, created_at
                  FROM consultation WHERE created_at >= ?
                 ORDER BY created_at DESC
                """, rs -> {
            Cust c = custs.get(rs.getString("customer_id"));
            if (c == null || c.consult != null) {
                return;
            }
            c.consult = new ConsultRow(rs.getString("needs"), rs.getString("consultant"),
                    rs.getObject("created_at", OffsetDateTime.class));
            c.bumpEvent(c.consult.at());
        }, from);

        jdbc.query("""
                SELECT customer_id, order_no, project, amount, consultant, status, created_at
                  FROM txn_order WHERE created_at >= ? AND status <> '已取消'
                 ORDER BY created_at DESC
                """, rs -> {
            Cust c = custs.get(rs.getString("customer_id"));
            if (c == null || c.order != null) {
                return;
            }
            c.order = new OrderRow(rs.getString("order_no"), rs.getString("project"),
                    rs.getObject("amount", Long.class), rs.getString("consultant"),
                    rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class));
            c.bumpEvent(c.order.at());
        }, from);

        jdbc.query("""
                SELECT customer_id, project, method, status, plan_date, done_at, followup_by
                  FROM followup WHERE plan_date >= ?
                 ORDER BY plan_date DESC
                """, rs -> {
            Cust c = custs.get(rs.getString("customer_id"));
            if (c == null || c.follow != null) {
                return;
            }
            c.follow = new FollowRow(rs.getString("project"), rs.getString("method"), rs.getString("status"),
                    rs.getObject("plan_date", LocalDate.class),
                    rs.getObject("done_at", OffsetDateTime.class), rs.getString("followup_by"));
            c.bumpEvent(c.follow.doneAt() != null ? c.follow.doneAt()
                    : c.follow.planDate().atStartOfDay(BIZ_ZONE).toOffsetDateTime());
        }, fromDate);

        jdbc.query("""
                SELECT customer_id, biz_type, target_project, transfer_amount, status, signed_at3, created_at
                  FROM repurchase WHERE created_at >= ?
                 ORDER BY created_at DESC
                """, rs -> {
            Cust c = custs.get(rs.getString("customer_id"));
            if (c == null || c.rebuy != null) {
                return;
            }
            c.rebuy = new RebuyRow(rs.getString("biz_type"), rs.getString("target_project"),
                    rs.getObject("transfer_amount", Long.class), rs.getString("status"),
                    rs.getObject("signed_at3", OffsetDateTime.class),
                    rs.getObject("created_at", OffsetDateTime.class));
            c.bumpEvent(c.rebuy.at());
        }, from);

        // 3) touch_event（V52「仅存不算」）：不进节点，仅最近触点时刻参与风险分级
        jdbc.query("""
                SELECT customer_id, MAX(at) AS last_touch FROM touch_event
                 WHERE customer_id IS NOT NULL AND at >= ? GROUP BY customer_id
                """, rs -> {
            Cust c = custs.get(rs.getString("customer_id"));
            if (c != null) {
                c.lastTouch = rs.getObject("last_touch", OffsetDateTime.class);
            }
        }, from);

        // 4) 逐客户构六节点 + currentStage/risk + KPI 全集累计
        OffsetDateTime monday = today.with(DayOfWeek.MONDAY).atStartOfDay(BIZ_ZONE).toOffsetDateTime();
        List<JourneyCustomerView> all = new ArrayList<>();
        int inProgress = 0, converted = 0, churn = 0;
        long spanSum = 0;
        int spanCnt = 0;
        for (Cust c : custs.values()) {
            List<JourneyNodeView> nodes = buildNodes(c);
            List<OffsetDateTime> doneTimes = new ArrayList<>();
            int lastDoneIdx = -1;
            for (int i = 0; i < nodes.size(); i++) {
                JourneyNodeView n = nodes.get(i);
                if (n.done()) {
                    lastDoneIdx = i;
                    OffsetDateTime t = doneTimeOf(c, i);
                    if (t != null) {
                        doneTimes.add(t);
                    }
                }
            }
            String currentStage = STAGES[Math.min(lastDoneIdx + 1, STAGES.length - 1)];
            OffsetDateTime lastAct = c.lastEvent;
            if (c.lastTouch != null && (lastAct == null || c.lastTouch.isAfter(lastAct))) {
                lastAct = c.lastTouch;
            }
            long idle = lastAct == null ? 999 : ChronoUnit.DAYS.between(lastAct.toLocalDate(), today);
            String risk = ("流失".equals(c.status) || idle > 45) ? "HIGH"
                    : ("沉睡".equals(c.status) || idle > 20) ? "MEDIUM" : "LOW";

            if (lastDoneIdx < STAGES.length - 1) {
                inProgress++;
            }
            if ("HIGH".equals(risk)) {
                churn++;
            }
            if (c.order != null && ("已收款".equals(c.order.status()) || "已核销".equals(c.order.status()))
                    && !c.order.at().isBefore(monday)) {
                converted++;
            }
            if (doneTimes.size() >= 2) {
                OffsetDateTime min = doneTimes.stream().min(Comparator.naturalOrder()).orElseThrow();
                OffsetDateTime max = doneTimes.stream().max(Comparator.naturalOrder()).orElseThrow();
                spanSum += ChronoUnit.DAYS.between(min.toLocalDate(), max.toLocalDate());
                spanCnt++;
            }
            all.add(new JourneyCustomerView(c.id, c.name, avatarLetter(c.name), maskPhone(c.phone),
                    c.level, currentStage, risk, nodes));
        }

        // 5) 最近活跃在前，limit 截列表；KPI 四卡基于候选全集（不随 limit 截断）
        all.sort((a, b) -> 0); // 占位，下行按 lastEvent 重排
        Map<String, OffsetDateTime> lastActById = new LinkedHashMap<>();
        for (Cust c : custs.values()) {
            OffsetDateTime lastAct = c.lastEvent;
            if (c.lastTouch != null && (lastAct == null || c.lastTouch.isAfter(lastAct))) {
                lastAct = c.lastTouch;
            }
            lastActById.put(c.id, lastAct);
        }
        all.sort(Comparator.comparing((JourneyCustomerView v) -> {
                    OffsetDateTime t = lastActById.get(v.id());
                    return t == null ? OffsetDateTime.MIN : t;
                }).reversed());
        int avgDays = spanCnt == 0 ? 0 : Math.round((float) spanSum / spanCnt);
        List<JourneyCustomerView> page = all.size() > lim ? all.subList(0, lim) : all;
        return new JourneyView(d, new JourneyKpiView(inProgress, converted, avgDays, churn), page);
    }

    /** 门店数据域 SQL 片段（等价 DataScope.storeSpec/ownedSpec 谓词语义）；参数追加进 params（顺序绑定）。 */
    private String customerStoreClause(LoginUser u, List<Object> params) {
        if (u == null || u.isSuper() || DataScope.SCOPE_GROUP.equals(u.scope())
                || DataScope.SCOPE_BRAND.equals(u.scope())) {
            return "";
        }
        if (DataScope.SCOPE_REGION.equals(u.scope())) {
            List<String> stores = u.stores();
            if (stores == null || stores.isEmpty()) {
                return "";
            }
            params.addAll(stores);
            return " AND c.store_code IN (" + String.join(",", Collections.nCopies(stores.size(), "?")) + ")";
        }
        // SELF / STORE：绑定本门店；异常账号（无 storeCode）永假不见数据（storePredicate 同语义）
        if (u.storeCode() == null || u.storeCode().isBlank()) {
            return " AND 1=0";
        }
        params.add(u.storeCode());
        String clause = " AND c.store_code = ?";
        if (DataScope.SCOPE_SELF.equals(u.scope()) && u.staffId() != null) {
            params.add(u.staffId());
            clause += " AND c.owner_staff_id = ?";
        }
        return clause;
    }

    private List<JourneyNodeView> buildNodes(Cust c) {
        List<JourneyNodeView> nodes = new ArrayList<>(6);
        nodes.add(apptNode(c.appt));
        nodes.add(arriveNode(c));
        nodes.add(consultNode(c.consult));
        nodes.add(orderNode(c.order));
        nodes.add(followNode(c.follow));
        nodes.add(rebuyNode(c.rebuy));
        return nodes;
    }

    private OffsetDateTime doneTimeOf(Cust c, int stageIdx) {
        return switch (stageIdx) {
            case 0 -> c.appt == null ? null
                    : (c.appt.arrivedAt() != null ? c.appt.arrivedAt()
                    : c.appt.date().atStartOfDay(BIZ_ZONE).toOffsetDateTime());
            case 1 -> c.arrive != null ? (c.arrive.doneAt() != null ? c.arrive.doneAt() : c.arrive.arrivedAt())
                    : (c.appt != null ? c.appt.arrivedAt() : null);
            case 2 -> c.consult == null ? null : c.consult.at();
            case 3 -> c.order == null ? null : c.order.at();
            case 4 -> c.follow == null ? null : c.follow.doneAt();
            case 5 -> c.rebuy == null ? null
                    : (c.rebuy.signedAt3() != null ? c.rebuy.signedAt3() : c.rebuy.at());
            default -> null;
        };
    }

    private JourneyNodeView apptNode(ApptRow r) {
        if (r == null) {
            return pendingNode(0);
        }
        boolean done = "已到店".equals(r.status()) || r.arrivedAt() != null;
        return new JourneyNodeView("APPT", DATE_FMT.format(r.date()), "预约 " + cut(r.project(), 12),
                nullToDash(r.time()) + "·" + r.source() + "·" + r.status(), null, r.doctor(), done);
    }

    private JourneyNodeView arriveNode(Cust c) {
        ArriveRow r = c.arrive;
        if (r == null) {
            // 兜底：无到店登记行但预约单已签到（APPOINTMENT 来源未落 arrival 的历史单）
            if (c.appt != null && c.appt.arrivedAt() != null) {
                return new JourneyNodeView("ARRIVE", DATE_FMT.format(c.appt.arrivedAt()), "到店签到",
                        "预约到店·已签到", null, c.appt.doctor(), true);
            }
            return pendingNode(1);
        }
        boolean done = "DONE".equals(r.status()) || r.doneAt() != null;
        return new JourneyNodeView("ARRIVE", DATE_FMT.format(r.arrivedAt()), "到店接待",
                channelText(r.channel()) + "·" + arriveStatusText(r.status()), null, r.operator(), done);
    }

    private JourneyNodeView consultNode(ConsultRow r) {
        if (r == null) {
            return pendingNode(2);
        }
        return new JourneyNodeView("CONSULT", DATE_FMT.format(r.at()), "咨询面诊",
                cut(r.needs(), 40), null, r.consultant(), true);
    }

    private JourneyNodeView orderNode(OrderRow r) {
        if (r == null) {
            return pendingNode(3);
        }
        boolean done = "已收款".equals(r.status()) || "已核销".equals(r.status());
        Double amount = r.amount() == null ? null : r.amount() / 100.0;
        return new JourneyNodeView("PAY", DATE_FMT.format(r.at()), cut(r.project(), 12),
                "订单 " + r.orderNo() + "·" + r.status(), amount, r.consultant(), done);
    }

    private JourneyNodeView followNode(FollowRow r) {
        if (r == null) {
            return pendingNode(4);
        }
        boolean done = "DONE".equals(r.status());
        String date = r.doneAt() != null ? DATE_FMT.format(r.doneAt()) : DATE_FMT.format(r.planDate());
        return new JourneyNodeView("FOLLOW", date, "回访关怀",
                cut(r.project(), 16) + "·" + followMethodText(r.method()) + "·" + followStatusText(r.status()),
                null, r.by(), done);
    }

    private JourneyNodeView rebuyNode(RebuyRow r) {
        if (r == null) {
            return pendingNode(5);
        }
        boolean done = r.signedAt3() != null;
        Double amount = r.transferAmount() == null ? null : r.transferAmount() / 100.0;
        String title = r.targetProject() == null || r.targetProject().isBlank()
                ? "复购升单" : "复购 " + cut(r.targetProject(), 10);
        return new JourneyNodeView("REBUY", DATE_FMT.format(r.at()), title,
                r.bizType() + "·" + r.status(), amount, null, done);
    }

    private JourneyNodeView pendingNode(int stageIdx) {
        return new JourneyNodeView(STAGES[stageIdx], "", STAGE_TITLES[stageIdx], "暂无记录", null, null, false);
    }

    private static String avatarLetter(String name) {
        return name == null || name.isBlank() ? "?" : name.substring(0, 1);
    }

    /** 手机脱敏（PIPL 最小展示）：11 位 → 138****5678；非 11 位仅留后 4。 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "-";
        }
        if (phone.length() == 11) {
            return phone.substring(0, 3) + "****" + phone.substring(7);
        }
        return "****" + phone.substring(Math.max(0, phone.length() - 4));
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String channelText(String channel) {
        return switch (channel == null ? "" : channel) {
            case "WALK_IN" -> "自然到店";
            case "REFERRAL" -> "转介绍";
            case "MARKETING" -> "线上营销";
            case "APPOINTMENT" -> "预约到店";
            default -> nullToDash(channel);
        };
    }

    private static String arriveStatusText(String status) {
        return switch (status == null ? "" : status) {
            case "WAITING" -> "候诊中";
            case "TRIAGED" -> "已分诊";
            case "CALLED" -> "已叫号";
            case "DONE" -> "已完成";
            case "LEFT" -> "已离开";
            default -> nullToDash(status);
        };
    }

    private static String followMethodText(String method) {
        return switch (method == null ? "" : method) {
            case "PHONE" -> "电话";
            case "WECHAT" -> "微信";
            case "IN_STORE" -> "到店";
            default -> nullToDash(method);
        };
    }

    private static String followStatusText(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING" -> "待回访";
            case "DONE" -> "已回访";
            case "SKIPPED" -> "无需回访";
            default -> nullToDash(status);
        };
    }

    private record ApptRow(String project, LocalDate date, String time, String doctor, String source,
                           String status, OffsetDateTime arrivedAt) {}

    private record ArriveRow(String channel, String status, OffsetDateTime arrivedAt, OffsetDateTime doneAt,
                             String operator) {}

    private record ConsultRow(String needs, String consultant, OffsetDateTime at) {}

    private record OrderRow(String orderNo, String project, Long amount, String consultant, String status,
                            OffsetDateTime at) {}

    private record FollowRow(String project, String method, String status, LocalDate planDate,
                             OffsetDateTime doneAt, String by) {}

    private record RebuyRow(String bizType, String targetProject, Long transferAmount, String status,
                            OffsetDateTime signedAt3, OffsetDateTime at) {}

    /** 聚合中间态（内存分组用，不出本类）。 */
    private static final class Cust {
        String id;
        String name;
        String phone;
        String level;
        String status;
        ApptRow appt;
        ArriveRow arrive;
        ConsultRow consult;
        OrderRow order;
        FollowRow follow;
        RebuyRow rebuy;
        OffsetDateTime lastTouch;
        OffsetDateTime lastEvent;

        void bumpEvent(OffsetDateTime t) {
            if (t != null && (lastEvent == null || t.isAfter(lastEvent))) {
                lastEvent = t;
            }
        }
    }
}
