package com.example.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.util.Set;

/**
 * SPA 单页应用前端路由转发配置。
 * 对所有请求，若不属于后端 API / 企微回调 / 静态资源，则 forward 到 index.html，
 * 交由 Vue Router (History 模式) 处理。
 */
@Configuration
public class SpaRoutingConfig {

    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".html", ".htm", ".js", ".mjs", ".css", ".map",
            ".json", ".ico", ".png", ".jpg", ".jpeg", ".gif",
            ".svg", ".webp", ".woff", ".woff2", ".ttf", ".eot",
            ".mp4", ".webm", ".wav", ".mp3", ".pdf"
    );

    @Bean
    public FilterRegistrationBean<Filter> spaForwardFilter() {
        Filter filter = new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletRequest request = (HttpServletRequest) req;
                String uri = request.getRequestURI();
                String contextPath = request.getContextPath();
                if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
                    uri = uri.substring(contextPath.length());
                }

                // 1) 后端 API / 企微回调，直接放行
                if (uri.startsWith("/api/") || "/api".equals(uri)
                        || uri.startsWith("/wecom/") || "/wecom".equals(uri)) {
                    chain.doFilter(req, res);
                    return;
                }

                // 2) 明确的静态资源扩展名（如 /assets/index.abc123.js），放行交给静态资源处理器
                int dot = uri.lastIndexOf('.');
                if (dot >= 0) {
                    String ext = uri.substring(dot).toLowerCase();
                    if (STATIC_EXTENSIONS.contains(ext)) {
                        chain.doFilter(req, res);
                        return;
                    }
                }

                // 3) 其余：SPA 路由 → 转发到 index.html
                request.getRequestDispatcher("/index.html").forward(req, res);
            }
        };

        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("spaForwardFilter");
        return reg;
    }
}
