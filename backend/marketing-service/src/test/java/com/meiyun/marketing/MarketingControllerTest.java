package com.meiyun.marketing;

import com.meiyun.common.event.DomainEventPublisher;
import com.meiyun.common.ratelimit.RateLimiter;
import com.meiyun.security.AuthInterceptor;
import com.meiyun.security.JwtTokenUtil;
import com.meiyun.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * marketing-service Web 切片测试（@WebMvcTest + MockMvc）：
 * 装配真实 {@link AuthInterceptor} + JWT 验签，覆盖鉴权红线（401 未登录 / 403 权限不足）
 * 与写链路异常路径（400 非法流转/校验、409 库存发完）中文错误不外露英文，及 changed 幂等字段。
 */
@WebMvcTest(MarketingController.class)
@ContextConfiguration(classes = {MarketingController.class, GlobalExceptionHandler.class,
        MarketingControllerTest.TestSecurityConfig.class})
class MarketingControllerTest {

    /** 切片内显式装配鉴权拦截器（@WebMvcTest 不加载 meiyun-security 自动装配）。 */
    @Configuration
    static class TestSecurityConfig {
        @Bean
        JwtTokenUtil jwtTokenUtil() {
            return new JwtTokenUtil("test-secret-key-for-marketing-slice-0123456789", Duration.ofHours(1));
        }

        @Bean
        AuthInterceptor authInterceptor(JwtTokenUtil jwt) {
            return new AuthInterceptor(jwt);
        }

        @Bean
        WebMvcConfigurer securityInterceptorConfig(AuthInterceptor authInterceptor) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
                }
            };
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtTokenUtil jwt;

    @MockBean
    CampaignService campaignService;
    @MockBean
    CouponService couponService;
    @MockBean
    MarketingStatsService statsService;
    @MockBean
    ForbiddenWordService forbiddenWordService;
    @MockBean
    PushRecordRepository pushRepo;
    @MockBean
    CouponWriteoffChainRepository chainRepo;
    @MockBean
    MarketingCfgRepository cfgRepo;
    @MockBean
    RateLimiter rateLimiter;
    @MockBean
    DomainEventPublisher events;

    private String token(String staffId, List<String> roles, List<String> perms) {
        LoginUser u = new LoginUser(staffId, "测试员", roles, "S001", "STORE", perms, false, null, null);
        return "Bearer " + jwt.issue(u);
    }

    private String adminToken() {
        return token("U001", List.of("SUPER_ADMIN"), List.of("*"));
    }

    private String frontDeskToken() {
        return token("U002", List.of("FRONT_DESK"), List.of("marketing:view"));
    }

    // ==================== 鉴权红线 ====================

    @Test
    void get_without_token_returns_401_chinese() throws Exception {
        mockMvc.perform(get("/api/marketing/coupons"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("未登录或登录已失效，请重新登录"));
    }

    @Test
    void write_without_perm_returns_403_chinese() throws Exception {
        mockMvc.perform(post("/api/marketing/coupons")
                        .header("Authorization", frontDeskToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("无操作权限")));
    }

    @Test
    void get_with_view_perm_passes_interceptor() throws Exception {
        when(couponService.list()).thenReturn(List.of());
        mockMvc.perform(get("/api/marketing/coupons")
                        .header("Authorization", frontDeskToken()))
                .andExpect(status().isOk());
    }

    // ==================== M5-06 ROI 统计聚合 ====================

    @Test
    void stats_overview_returns_aggregated_coupon_and_campaign_blocks() throws Exception {
        java.util.Map<String, Object> overview = new java.util.LinkedHashMap<>();
        overview.put("coupon", java.util.Map.of(
                "couponKinds", 2, "totalIssued", 110, "totalUsed", 15,
                "writeoffRate", 0.1364, "grantBatches", 2L, "grantedPcs", 110L,
                "rows", List.of()));
        overview.put("campaign", java.util.Map.of(
                "campaignCount", 1, "runningCount", 1L, "totalSpent", 200000L,
                "totalActualAmount", 600000L, "totalNewCustomers", 30,
                "overallRoi", 3.0, "achieveRate", 1.0, "rows", List.of()));
        when(statsService.overview()).thenReturn(overview);

        mockMvc.perform(get("/api/marketing/stats/overview")
                        .header("Authorization", frontDeskToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.totalIssued").value(110))
                .andExpect(jsonPath("$.coupon.writeoffRate").value(0.1364))
                .andExpect(jsonPath("$.campaign.overallRoi").value(3.0));
    }

    // ==================== 活动写链路 ====================

    @Test
    void create_campaign_success_returns_200() throws Exception {
        when(campaignService.create(any())).thenReturn(new Campaign());
        mockMvc.perform(post("/api/marketing/campaign")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"国庆活动\",\"type\":\"FULL_REDUCE\",\"channels\":[\"抖音\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void transit_illegal_jump_returns_400_chinese() throws Exception {
        when(campaignService.transit(eq("A1"), anyString()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "当前状态为「草稿」的活动不可流转为「进行中」"));
        mockMvc.perform(post("/api/marketing/campaigns/A1/transit")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"RUNNING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("草稿")))
                .andExpect(jsonPath("$.error").value("BUSINESS_ERROR"));
    }

    @Test
    void transit_idempotent_returns_changed_false() throws Exception {
        when(campaignService.transit(eq("A1"), eq("DRAFT"))).thenReturn(false);
        mockMvc.perform(post("/api/marketing/campaigns/A1/transit")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"DRAFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));
    }

    @Test
    void transit_changed_returns_changed_true() throws Exception {
        when(campaignService.transit(eq("A1"), eq("SCHEDULED"))).thenReturn(true);
        mockMvc.perform(post("/api/marketing/campaigns/A1/transit")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"SCHEDULED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
    }

    // ==================== 券写链路 ====================

    @Test
    void grant_when_stock_exhausted_returns_409_chinese() throws Exception {
        when(couponService.grant(eq("C1"), any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "券「新人满减券」库存已发完（总量 100，已发 100）"));
        mockMvc.perform(post("/api/marketing/coupons/C1/grant")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"ALL\",\"count\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("库存已发完")));
    }

    @Test
    void enable_idempotent_returns_changed_false() throws Exception {
        when(couponService.enable("C1")).thenReturn(false);
        mockMvc.perform(post("/api/marketing/coupons/C1/enable")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));
    }

    @Test
    void enable_not_found_returns_404_chinese() throws Exception {
        when(couponService.enable("X"))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "券不存在：X"));
        mockMvc.perform(post("/api/marketing/coupons/X/enable")
                        .header("Authorization", adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("券不存在：X"));
    }

    @Test
    void grant_success_returns_grant_record() throws Exception {
        CouponGrant g = new CouponGrant();
        g.setGrantId("GR20260902-000001");
        g.setCouponId("C1");
        g.setCouponName("新人满减券");
        g.setGrantScope("ALL");
        g.setTargetName("全部客户");
        g.setGrantCount(5);
        g.setStatus("GRANTED");
        when(couponService.grant(eq("C1"), any())).thenReturn(g);
        mockMvc.perform(post("/api/marketing/coupons/C1/grant")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"ALL\",\"count\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantId").value("GR20260902-000001"))
                .andExpect(jsonPath("$.status").value("GRANTED"))
                .andExpect(jsonPath("$.grantCount").value(5));
    }

    @Test
    void config_endpoint_returns_cfg_when_present() throws Exception {
        MarketingCfg cfg = new MarketingCfg();
        cfg.setWeeklyPushLimit(3);
        when(cfgRepo.findById(1)).thenReturn(Optional.of(cfg));
        mockMvc.perform(get("/api/marketing/config")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyPushLimit").value(3));
    }

    // ==================== 触达红线（违禁词 + 周频限 3 条）====================

    /** 让频控默认放行（配额充足）、违禁词校验默认通过，cfg 返回周频上限 3。 */
    private void allowPush() {
        MarketingCfg cfg = new MarketingCfg();
        cfg.setWeeklyPushLimit(3);
        when(cfgRepo.findById(1)).thenReturn(Optional.of(cfg));
        when(forbiddenWordService.check(anyString())).thenReturn(List.of());
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        when(rateLimiter.currentCount(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0L);
        when(pushRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void push_hit_forbidden_word_returns_400_chinese_and_not_persisted() throws Exception {
        allowPush();
        // “根治” 属医疗承诺类违禁词（DB 词库服务返回命中），必须在频控前拦截，且不允许落库
        when(forbiddenWordService.check(anyString()))
                .thenReturn(List.of("医疗承诺:根治", "医疗承诺:一次见效"));
        mockMvc.perform(post("/api/marketing/push")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"M001\",\"pushType\":\"SMS\",\"content\":\"本店新品一次见效，根治痘痘\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("违禁词")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("医疗承诺:根治")));
        org.mockito.Mockito.verify(pushRepo, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(events);
        org.mockito.Mockito.verify(rateLimiter, org.mockito.Mockito.never())
                .tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void push_over_weekly_limit_returns_400_chinese() throws Exception {
        MarketingCfg cfg = new MarketingCfg();
        cfg.setWeeklyPushLimit(3);
        when(cfgRepo.findById(1)).thenReturn(Optional.of(cfg));
        // 违禁词通过但频控拒绝：近 7 天已触达 3 条
        when(forbiddenWordService.check(anyString())).thenReturn(List.of());
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);
        when(rateLimiter.currentCount(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(3L);
        mockMvc.perform(post("/api/marketing/push")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"M001\",\"pushType\":\"SMS\",\"content\":\"秋季护理预约提醒\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("触达频控拦截")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("上限 3")));
        org.mockito.Mockito.verify(pushRepo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void push_compliant_within_quota_returns_200_and_publishes_event() throws Exception {
        allowPush();
        mockMvc.perform(post("/api/marketing/push")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"M001\",\"pushType\":\"SMS\",\"content\":\"您预约的秋季护理明天到店\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("M001"))
                .andExpect(jsonPath("$.pushType").value("SMS"));
        org.mockito.Mockito.verify(pushRepo).save(any());
        org.mockito.Mockito.verify(events).publish(org.mockito.ArgumentMatchers.eq("meiyun.marketing.push-sent"),
                anyString(), anyString());
    }

    @Test
    void push_invalid_push_type_returns_400_chinese_and_not_persisted() throws Exception {
        allowPush();
        mockMvc.perform(post("/api/marketing/push")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"SC001\",\"pushType\":\"CARRIER_PIGEON\",\"content\":\"秋季护理预约提醒\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("推送渠道不合法")));
        org.mockito.Mockito.verify(pushRepo, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(forbiddenWordService, org.mockito.Mockito.never())
                .check(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void push_wechat_mp_type_is_accepted_and_persisted() throws Exception {
        allowPush();
        mockMvc.perform(post("/api/marketing/push")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"SC001\",\"pushType\":\"WECHAT_MP\",\"content\":\"公众号模板消息：您有一张专属券待领取\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushType").value("WECHAT_MP"));
        org.mockito.Mockito.verify(pushRepo).save(any());
    }

    @Test
    void push_quota_endpoint_reports_remaining() throws Exception {
        MarketingCfg cfg = new MarketingCfg();
        cfg.setWeeklyPushLimit(3);
        when(cfgRepo.findById(1)).thenReturn(Optional.of(cfg));
        when(rateLimiter.currentCount(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(2L);
        mockMvc.perform(get("/api/marketing/push/quota/M001")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("M001"))
                .andExpect(jsonPath("$.sentLast7Days").value(2))
                .andExpect(jsonPath("$.weeklyLimit").value(3))
                .andExpect(jsonPath("$.remaining").value(1));
    }

    // ==================== 违禁词库服务化（A1-04：DB + 缓存 + 管理端维护） ====================

    @Test
    void forbidden_words_grouped_endpoint_returns_categories() throws Exception {
        when(forbiddenWordService.categories())
                .thenReturn(java.util.Map.of("医疗承诺", List.of("根治", "治愈")));
        mockMvc.perform(get("/api/marketing/forbidden-words")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['医疗承诺'][0]").value("根治"));
    }

    @Test
    void forbidden_words_list_returns_all_rows_for_management() throws Exception {
        ForbiddenWordView v = new ForbiddenWordView(
                7L, "虚假宣传", "内部价", true, null, null);
        when(forbiddenWordService.list()).thenReturn(List.of(v));
        mockMvc.perform(get("/api/marketing/forbidden-words/list")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].wordId").value(7))
                .andExpect(jsonPath("$[0].word").value("内部价"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void check_copy_returns_hits_without_400() throws Exception {
        when(forbiddenWordService.check("包治百病"))
                .thenReturn(List.of("医疗承诺:包治"));
        mockMvc.perform(post("/api/marketing/forbidden-words/check")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"包治百病\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.hits[0]").value("医疗承诺:包治"));
    }

    @Test
    void check_copy_clean_returns_passed() throws Exception {
        when(forbiddenWordService.check(anyString())).thenReturn(List.of());
        mockMvc.perform(post("/api/marketing/forbidden-words/check")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"秋季护理预约提醒\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void create_forbidden_word_requires_edit_perm() throws Exception {
        // 仅有 marketing:view 的前台角色不可维护词库
        mockMvc.perform(post("/api/marketing/forbidden-words")
                        .header("Authorization", frontDeskToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"虚假宣传\",\"word\":\"特效\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("无操作权限")));
    }

    @Test
    void create_forbidden_word_success_returns_row() throws Exception {
        ForbiddenWord w = new ForbiddenWord();
        w.setWordId(99L);
        w.setCategory("虚假宣传");
        w.setWord("特效");
        w.setEnabled(true);
        when(forbiddenWordService.create(any())).thenReturn(w);
        mockMvc.perform(post("/api/marketing/forbidden-words")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"虚假宣传\",\"word\":\"特效\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wordId").value(99))
                .andExpect(jsonPath("$.word").value("特效"));
    }

    @Test
    void create_forbidden_word_illegal_category_returns_400_chinese() throws Exception {
        when(forbiddenWordService.create(any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "违禁词类别不合法（绝对化用语/医疗承诺/虚假宣传/低俗诱导）"));
        mockMvc.perform(post("/api/marketing/forbidden-words")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"不存在的类\",\"word\":\"特效\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("违禁词类别不合法")));
    }

    @Test
    void toggle_forbidden_word_returns_changed() throws Exception {
        when(forbiddenWordService.toggle(eq(9L), eq(false))).thenReturn(true);
        mockMvc.perform(post("/api/marketing/forbidden-words/9/toggle")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void delete_forbidden_word_returns_changed() throws Exception {
        when(forbiddenWordService.delete(9L)).thenReturn(true);
        mockMvc.perform(post("/api/marketing/forbidden-words/9/delete")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
    }
}
