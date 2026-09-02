package com.meiyun.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * meiyun-security 自动装配：各服务只要依赖本模块即获得
 * JWT 验签 + {@link RequirePerm} 鉴权拦截器，无需各自配置。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityProperties.class)
public class MeiyunSecurityAutoConfiguration {

    @Bean
    public JwtTokenUtil jwtTokenUtil(SecurityProperties props) {
        return new JwtTokenUtil(props.getSecret(), props.getTtl());
    }

    @Bean
    public AuthInterceptor authInterceptor(JwtTokenUtil jwtTokenUtil) {
        return new AuthInterceptor(jwtTokenUtil);
    }

    /** Bean Validation 异常统一中文化（@NotBlank/@NotNull 等英文默认消息不外露）。 */
    @Bean
    public ChineseValidationAdvice chineseValidationAdvice() {
        return new ChineseValidationAdvice();
    }

    @Bean
    public WebMvcConfigurer meiyunSecurityWebConfig(AuthInterceptor authInterceptor,
                                                   SecurityProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(authInterceptor)
                        .addPathPatterns("/api/**")
                        .excludePathPatterns(props.getPublicPaths().toArray(new String[0]));
            }
        };
    }
}
