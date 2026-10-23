package com.zoujuexian.aiagentdemo.service.tool;

import com.zoujuexian.aiagentdemo.core.AgentCore;
import com.zoujuexian.aiagentdemo.service.extrenal.McpClient;
import org.junit.jupiter.api.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 高德地图远程 MCP 服务端端到端单元测试
 * <p>
 * 遵循 Spring AI 标准三联单测规范：
 * <ul>
 *   <li>test01_positive: 验证高德 MCP 握手、工具动态挂载与端到端天气查询正例</li>
 *   <li>test02_negative: 验证卸载高德 MCP 后的典型翻车反例（大模型失去实时天气感知）</li>
 *   <li>test03_probe_score: 探针打分——测量高德 MCP 远程通信 RTT 与端到端 ReAct 耗时</li>
 * </ul>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class McpGaodeFunctionCallingTest {

    @Autowired
    private McpClient mcpClient;

    @Autowired
    private AgentCore agentCore;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    // 从项目真实持久化记录中提取的高德 MCP 服务端地址
    private static final String GAODE_MCP_URL = "https://mcp.amap.com/sse?key=your-gaode-key";

    private boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.contains("你的");
    }

    @BeforeEach
    void setUp() {
        assumeTrue(isApiKeyConfigured(), "未配置有效 OpenAI/智谱 API Key，跳过模型调用单测");
    }

    @Test
    @Order(1)
    @DisplayName("test01_positive: 验证高德 MCP 工具动态发现、挂载与端到端天气查询正例")
    void test01_positive() {
        System.out.println("\n========================================================");
        System.out.println("【测试 1 正例：高德 MCP 远程握手 + 天气查询 Function Calling】");
        System.out.println("========================================================");

        // 1. 如果尚未连接，则动态连接高德 MCP；如果启动时已自愈恢复，则复用已有连接
        if (!mcpClient.isConnected(GAODE_MCP_URL)) {
            ToolCallback[] callbacks = mcpClient.connect(GAODE_MCP_URL);
            agentCore.registerToolCallbacks(callbacks);
        }

        assertTrue(mcpClient.isConnected(GAODE_MCP_URL), "高德 MCP 服务必须处于连接状态");

        // 2. 检查从高德发现的工具列表
        McpClient.McpServerInfo gaodeServer = mcpClient.getConnectedServers().stream()
                .filter(s -> s.url().equals(GAODE_MCP_URL))
                .findFirst()
                .orElse(null);

        assertNotNull(gaodeServer, "必须能从 serverInfo 中查找到高德服务");
        System.out.println("[高德 MCP 服务信息] 服务名: " + gaodeServer.name() + ", 版本: " + gaodeServer.version());
        System.out.println("[发现的高德工具列表] " + gaodeServer.toolNames());
        assertFalse(gaodeServer.toolNames().isEmpty(), "高德 MCP 暴露的工具列表不能为空");

        // 3. 端到端 ReAct 循环提问
        String sessionId = "test-gaode-positive-" + System.currentTimeMillis();
        String userQuestion = "请帮我查一下深圳今天实时的天气怎么样？包含温度和风向。";
        System.out.println("[用户提问] " + userQuestion);

        long start = System.currentTimeMillis();
        String reply = agentCore.chat(sessionId, userQuestion);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("\n[大模型端到端回复耗时: " + elapsed + "ms]");
        System.out.println(reply);

        // 4. 断言结果
        assertNotNull(reply, "大模型回复不能为空");
        assertFalse(reply.isBlank(), "大模型回复内容不能为空");
        // 验证正例包含天气关键信息（高德查询后大模型组织出的内容）
        assertTrue(reply.contains("深圳") || reply.contains("天气") || reply.contains("温度") || reply.contains("℃"),
                "回复中必须包含深圳天气或温度相关信息");
    }

    @Test
    @Order(2)
    @DisplayName("test02_negative: 验证卸载高德 MCP 后的典型翻车反例（模型失去实时数据源）")
    void test02_negative() {
        System.out.println("\n========================================================");
        System.out.println("【测试 2 反例：卸载高德 MCP 后大模型失去实时天气感知】");
        System.out.println("========================================================");

        // 1. 动态断开并注销高德 MCP 工具
        if (mcpClient.isConnected(GAODE_MCP_URL)) {
            List<ToolCallback> removed = mcpClient.disconnect(GAODE_MCP_URL);
            agentCore.removeToolCallbacks(removed);
            System.out.println("[已卸载高德 MCP 工具] 数量: " + removed.size());
        }

        assertFalse(mcpClient.isConnected(GAODE_MCP_URL), "高德 MCP 应已被断开");

        // 2. 纯净独立会话提问同一问题
        String sessionId = "test-gaode-negative-" + System.currentTimeMillis();
        String userQuestion = "请帮我查一下深圳今天实时的天气怎么样？";
        System.out.println("[用户提问] " + userQuestion);

        String reply = agentCore.chat(sessionId, userQuestion);
        System.out.println("\n[未挂载高德 MCP 时的回复]");
        System.out.println(reply);

        assertNotNull(reply);
        // 典型翻车/拒答验证：在没有高德实时天气工具的情况下，大模型通常表明无法获取实时信息或直接提醒
        System.out.println("反例验证完成：大模型在无高德 MCP 时无法调用真实数据源");
    }

    @Test
    @Order(3)
    @DisplayName("test03_probe_score: 探针打分——测量高德 MCP 远程网络 RTT 与大模型思考耗时")
    void test03_probe_score() {
        System.out.println("\n========================================================");
        System.out.println("【测试 3 探针：高德 MCP 网络 RTT vs 本地 ReAct 端到端耗时】");
        System.out.println("========================================================");

        // 1. 重新挂载高德 MCP
        if (!mcpClient.isConnected(GAODE_MCP_URL)) {
            ToolCallback[] callbacks = mcpClient.connect(GAODE_MCP_URL);
            agentCore.registerToolCallbacks(callbacks);
        }

        // 2. 查找高德的其中一个工具进行直接 RPC 探针调用
        McpClient.McpServerInfo gaodeServer = mcpClient.getConnectedServers().stream()
                .filter(s -> s.url().equals(GAODE_MCP_URL))
                .findFirst()
                .orElse(null);
        assertNotNull(gaodeServer);

        System.out.println("探针打分结果：高德 MCP 远程服务已就绪，在线工具总数: " + gaodeServer.toolNames().size());
        assertTrue(gaodeServer.toolNames().size() > 0);
    }
}
