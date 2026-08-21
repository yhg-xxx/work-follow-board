package com.example.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 把工作跟进看板暴露为 MCP Server（SSE 传输）。
 *
 * <p>采用 MCP Java SDK v2 的 {@link HttpServletSseServerTransportProvider}（纯 Servlet，
 * 对 Spring Boot 4 / Spring Framework 7 版本安全），挂在 /mcp 命名空间：
 * <ul>
 *   <li>GET  /mcp/sse      —— SSE 长连接（服务端→客户端事件）</li>
 *   <li>POST /mcp/message  —— 客户端→服务端 JSON-RPC 消息（带 sessionId 查询参数）</li>
 * </ul>
 *
 * <p>注意：该 SSE 传输在 SDK v2 中已标记 @Deprecated（官方推荐 Streamable HTTP），
 * 但功能完整、且广泛被现有 MCP 客户端（VS Code / Claude Desktop 等）支持，故此处按需求采用。
 */
@Configuration
public class McpServerConfig {

    /**
     * 显式声明 Spring 托管的 ObjectMapper。
     * 本项目用 spring-boot-starter-webmvc（不含 spring-boot-starter-json），Jackson 自动配置
     * 未生成 ObjectMapper bean，导致 MCP 的 SSE 传输注入失败。这里直接 new 一个 @Primary 实例，
     * 不依赖自动配置，保证注入稳定。
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    @SuppressWarnings("deprecation")
    public HttpServletSseServerTransportProvider mcpTransportProvider(ObjectMapper objectMapper) {
        return HttpServletSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .messageEndpoint("/mcp/message")
                .sseEndpoint("/mcp/sse")
                .build();
    }

    @Bean
    @SuppressWarnings("deprecation")
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> mcpServlet(
            HttpServletSseServerTransportProvider provider) {
        ServletRegistrationBean<HttpServletSseServerTransportProvider> reg =
                new ServletRegistrationBean<>(provider);
        reg.addUrlMappings("/mcp/sse", "/mcp/message");
        reg.setName("mcpSseServerTransport");
        reg.setLoadOnStartup(2);
        return reg;
    }

    @Bean(destroyMethod = "closeGracefully")
    @SuppressWarnings("deprecation")
    public McpSyncServer mcpSyncServer(HttpServletSseServerTransportProvider provider,
                                       TaskReadMcpTools readTools) {
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo("work-follow-board", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .build();
        readTools.registerAll(server);
        return server;
    }
}
