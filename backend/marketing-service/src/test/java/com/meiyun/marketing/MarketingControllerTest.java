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
}
