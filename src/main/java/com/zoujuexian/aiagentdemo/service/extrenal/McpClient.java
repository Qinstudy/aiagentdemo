package com.zoujuexian.aiagentdemo.service.extrenal;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client 连接器
 * <p>
 * 封装连接外部 MCP Server 的逻辑，支持 Streamable HTTP 和 SSE 传输协议。
 * 支持按 URL 动态连接/断开 MCP 服务，维护已连接服务的状态信息。
 */
@Component
public class McpClient {

    private final Map<String, McpSyncClient> clientsByUrl = new ConcurrentHashMap<>();
    private final Map<String, McpServerInfo> serverInfoByUrl = new ConcurrentHashMap<>();
    private final Map<String, List<ToolCallback>> toolCallbacksByUrl = new ConcurrentHashMap<>();
    private final ServerStore store;

    public McpClient() {
        this.store = new ServerStore("src/main/resources/data/mcp-servers.json");
    }

    /**
     * 获取已持久化保存的 MCP Server URL 列表
     */
    public List<String> getSavedUrls() {
        return store.load();
    }

    /**
     * 连接到外部 MCP Server，自动检测传输协议。
     * <p>
     * 优先尝试 Streamable HTTP（2025-03-26 规范），失败后自动回退到 SSE（2024-11-05 规范）。
     *
     * @param serverUrl 完整的 MCP Server 端点 URL
     * @return 从该 MCP Server 获取到的工具回调列表
     * @throws IllegalStateException 如果该 URL 已经连接
     */
    public ToolCallback[] connect(String serverUrl) {
        if (clientsByUrl.containsKey(serverUrl)) {
            throw new IllegalStateException("该 MCP 服务已连接: " + serverUrl);
        }

        // McpSyncClient 来自 Anthropic 官方规范的底层 Java SDK（io.modelcontextprotocol）
        McpSyncClient mcpClient;
        McpSchema.InitializeResult initResult;

        // 优先尝试 Streamable HTTP，失败后回退到 SSE
        try {
            mcpClient = connectWithStreamableHttp(serverUrl);
            initResult = mcpClient.initialize();
            System.out.println("[MCP Client] 使用 Streamable HTTP 传输连接成功");
        } catch (Exception streamableException) {
            System.out.println("[MCP Client] Streamable HTTP 连接失败（" + streamableException.getMessage() + "），尝试 SSE 传输...");
            try {
                mcpClient = connectWithSse(serverUrl);
                initResult = mcpClient.initialize();
                System.out.println("[MCP Client] 使用 SSE 传输连接成功");
            } catch (Exception sseException) {
                throw new RuntimeException("两种传输协议均连接失败。"
                        + " Streamable HTTP: " + streamableException.getMessage()
                        + " | SSE: " + sseException.getMessage());
            }
        }

        String serverName = initResult.serverInfo().name();
        String serverVersion = initResult.serverInfo().version();
        System.out.println("[MCP Client] 已连接到: " + serverName + " v" + serverVersion);


        // 步骤 2：【关键转译中枢】Spring AI 提供的适配器介入！ SyncMcpToolCallbackProvider 来自 org.springframework.ai.mcp
        SyncMcpToolCallbackProvider toolCallbackProvider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpClient)
                .build();

        // 步骤 3：Provider 向高德发送 tools/list，并转译为 Spring AI 的 ToolCallback 数组
        ToolCallback[] rawCallbacks = toolCallbackProvider.getToolCallbacks();
        List<String> toolNames = new ArrayList<>();
        ToolCallback[] toolCallbacks = new ToolCallback[rawCallbacks.length];

        for (int i = 0; i < rawCallbacks.length; i++) {
            ToolCallback original = rawCallbacks[i];
            toolNames.add(original.getToolDefinition().name());
            System.out.println("  - " + original.getToolDefinition().name()
                    + ": " + original.getToolDefinition().description());
            // 包装日志代理
            toolCallbacks[i] = wrapWithLogging(original);
        }
        System.out.println("[MCP Client] 发现 " + toolCallbacks.length + " 个远程工具");

        clientsByUrl.put(serverUrl, mcpClient);
        toolCallbacksByUrl.put(serverUrl, Arrays.asList(toolCallbacks));
        serverInfoByUrl.put(serverUrl, new McpServerInfo(serverUrl, serverName, serverVersion, toolNames, true));

        store.add(serverUrl);

        return toolCallbacks;
    }

    /**
     * 使用 Streamable HTTP 传输连接（MCP 2025-03-26 规范）
     */
    private McpSyncClient connectWithStreamableHttp(String serverUrl) {
        URI uri = URI.create(serverUrl);
        String baseUri = uri.getScheme() + "://" + uri.getAuthority();
        String endpoint = uri.getRawPath();
        if (uri.getRawQuery() != null) {
            endpoint += "?" + uri.getRawQuery();
        }

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUri)
                .endpoint(endpoint)
                .openConnectionOnStartup(false)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        return io.modelcontextprotocol.client.McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("AiAgentDemo MCP Client", "1.0.0"))
                .build();
    }

    /**
     * 使用 SSE 传输连接（MCP 2024-11-05 规范）
     * <p>
     * SSE 传输中，baseUri 是完整的服务地址，sseEndpoint 默认为 /sse。
     * 如果 URL 以 /sse 结尾，则自动拆分；否则将整个 URL 作为 baseUri。
     */
    private McpSyncClient connectWithSse(String serverUrl) {
        String baseUri;
        String sseEndpoint;

        if (serverUrl.contains("/sse")) {
            int sseIndex = serverUrl.indexOf("/sse");
            baseUri = serverUrl.substring(0, sseIndex);
            sseEndpoint = serverUrl.substring(sseIndex);
        } else {
            baseUri = serverUrl;
            sseEndpoint = "/sse";
        }

        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(baseUri)
                .sseEndpoint(sseEndpoint)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        return io.modelcontextprotocol.client.McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("AiAgentDemo MCP Client", "1.0.0"))
                .build();
    }

    /**
     * 断开指定 URL 的 MCP 服务
     *
     * @param serverUrl 要断开的 MCP Server URL
     * @return 该服务关联的工具回调列表（用于从 Agent 中移除）
     */
    public List<ToolCallback> disconnect(String serverUrl) {
        McpSyncClient client = clientsByUrl.remove(serverUrl);

        List<ToolCallback> removedCallbacks = toolCallbacksByUrl.remove(serverUrl);
        serverInfoByUrl.remove(serverUrl);

        if (client != null) {
            try {
                client.closeGracefully();
            } catch (Exception exception) {
                System.err.println("[MCP Client] 关闭连接时出错: " + exception.getMessage());
            }
        }

        store.remove(serverUrl);

        System.out.println("[MCP Client] 已断开并移除: " + serverUrl);
        return removedCallbacks != null ? removedCallbacks : List.of();
    }

    /**
     * 获取所有 MCP 服务信息（包括已保存但未连接的）
     */
    public List<McpServerInfo> getAllServers() {
        Map<String, McpServerInfo> result = new LinkedHashMap<>(serverInfoByUrl);

        for (String url : store.load()) {
            if (!result.containsKey(url)) {
                result.put(url, new McpServerInfo(url, null, null, List.of(), false));
            }
        }

        return new ArrayList<>(result.values());
    }

    /**
     * 获取所有已连接的 MCP 服务信息
     */
    public List<McpServerInfo> getConnectedServers() {
        return new ArrayList<>(serverInfoByUrl.values());
    }

    /**
     * 判断指定 URL 是否已连接
     */
    public boolean isConnected(String serverUrl) {
        return clientsByUrl.containsKey(serverUrl);
    }

    /**
     * 关闭所有已连接的 MCP Client
     */
    public void closeAll() {
        for (McpSyncClient client : clientsByUrl.values()) {
            try {
                client.closeGracefully();
            } catch (Exception exception) {
                System.err.println("[MCP Client] 关闭连接时出错: " + exception.getMessage());
            }
        }
        clientsByUrl.clear();
        toolCallbacksByUrl.clear();
        serverInfoByUrl.clear();
        System.out.println("[MCP Client] 所有连接已关闭");
    }

    /**
     * 为 MCP 工具包装日志代理，在调用时打印工具名称、入参、耗时和结果
     */
    private ToolCallback wrapWithLogging(ToolCallback original) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return original.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                String toolName = original.getToolDefinition().name();

                System.out.println("\n╔══════════════════════════════════════════");
                System.out.println("║ 🌐 [MCP Tool Call] " + toolName);
                System.out.println("║ 📥 入参: " + truncate(toolInput, 200));
                System.out.println("╚══════════════════════════════════════════");

                long startTime = System.currentTimeMillis();
                try {
                    String result = original.call(toolInput);
                    long elapsed = System.currentTimeMillis() - startTime;

                    System.out.println("\n╔══════════════════════════════════════════");
                    System.out.println("║ ✅ [MCP Tool Result] " + toolName);
                    System.out.println("║ ⏱️ 耗时: " + elapsed + "ms");
                    System.out.println("║ 📤 结果: " + truncate(result, 300));
                    System.out.println("╚══════════════════════════════════════════\n");
                    return result;
                } catch (Exception exception) {
                    long elapsed = System.currentTimeMillis() - startTime;

                    System.err.println("\n╔══════════════════════════════════════════");
                    System.err.println("║ ❌ [MCP Tool Error] " + toolName);
                    System.err.println("║ ⏱️ 耗时: " + elapsed + "ms");
                    System.err.println("║ 💥 异常: " + exception.getMessage());
                    System.err.println("╚══════════════════════════════════════════\n");
                    throw exception;
                }
            }
        };
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "(null)";
        }
        String oneLine = text.replace("\n", " ").replace("\r", "");
        if (oneLine.length() <= maxLength) {
            return oneLine;
        }
        return oneLine.substring(0, maxLength) + "...（共" + text.length() + "字符）";
    }

    /**
     * 已连接的 MCP 服务信息
     */
    public record McpServerInfo(String url, String name, String version, List<String> toolNames, boolean connected) {
    }

    /**
     * MCP Server URL 持久化存储
     * <p>
     * 将已添加的 MCP Server URL 列表持久化到 JSON 文件中，
     * 应用重启后可自动恢复。
     */
    static class ServerStore {

        private final Path storePath;

        ServerStore(String filePath) {
            this.storePath = Paths.get(filePath);
        }

        List<String> load() {
            if (!Files.exists(storePath)) {
                return new ArrayList<>();
            }
            try {
                String content = Files.readString(storePath, StandardCharsets.UTF_8);
                List<String> urls = JSON.parseArray(content, String.class);
                return urls != null ? new ArrayList<>(urls) : new ArrayList<>();
            } catch (IOException exception) {
                System.err.println("[McpServerStore] 读取持久化文件失败: " + exception.getMessage());
                return new ArrayList<>();
            }
        }

        void save(List<String> urls) {
            try {
                Path parentDir = storePath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }
                String content = JSON.toJSONString(urls, SerializerFeature.PrettyFormat);
                Files.writeString(storePath, content, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                System.err.println("[McpServerStore] 写入持久化文件失败: " + exception.getMessage());
            }
        }

        synchronized void add(String url) {
            List<String> urls = load();
            if (!urls.contains(url)) {
                urls.add(url);
                save(urls);
            }
        }

        synchronized void remove(String url) {
            List<String> urls = load();
            if (urls.remove(url)) {
                save(urls);
            }
        }
    }
}
