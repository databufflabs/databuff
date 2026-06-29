package com.databuff.apm.web.config;

import com.databuff.apm.web.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/webapi", clazz -> clazz.isAnnotationPresent(RestController.class)
                && !clazz.getPackageName().startsWith("com.databuff.apm.web.ai.mcp.standard"));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(
                        "/webapi/api/**",
                        "/webapi/service/**",
                        "/webapi/trace/**",
                        "/webapi/globalTopology/**",
                        "/webapi/cockpit/**",
                        "/webapi/metric/**",
                        "/webapi/business/**",
                        "/webapi/alarm/**",
                        "/webapi/monitor/**",
                        "/webapi/slowInterface/**",
                        "/webapi/metrics/**",
                        "/webapi/notify/**",
                        "/webapi/respPolicy/**",
                        "/webapi/meta/**")
                .excludePathPatterns(
                        "/webapi/api/v1/auth/login");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl assetCache = CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic();
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(assetCache)
                .resourceChain(true);
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Portal frontend uses Vue Router base "/databuff"; root "/login" would not match any route.
        registry.addRedirectViewController("/", "/databuff/login");
        registry.addRedirectViewController("/login", "/databuff/login");

        registry.addViewController("/databuff").setViewName("forward:/index.html");
        registry.addViewController("/databuff/").setViewName("forward:/index.html");
        registry.addViewController("/databuff/**").setViewName("forward:/index.html");
    }
}
