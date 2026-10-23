package com.zoujuexian.aiagentdemo.service.tool;

import com.zoujuexian.aiagentdemo.core.AgentCore;
import com.zoujuexian.aiagentdemo.service.tool.impl.GetStockPriceTool;
import com.zoujuexian.aiagentdemo.service.tool.impl.GetWeatherTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tool 机制 (Function Calling) 与 ReAct 循环核心原理端到端闭环单测
 * <p>
 * 遵循标准单测规范，严格对比李博杰《大模型智能体导论》与 Datawhale《Hello-Agents》核心理论：
 * <ul>
 *   <li>test01_positive: 验证工具注册与正常端到端 Function Calling 正例</li>
 *   <li>test02_phenomenon1_schema_gating: 验证【现象 1】大模型前置 Schema 门禁拦截（Thought 阶段判定缺少必填参数 date，零工具调用直接反问哪一天）</li>
 *   <li>test03_phenomenon2_react_observation_healing: 验证【现象 2】真实 ReAct 循环闭环（Thought ➔ Action ➔ Observation 工具报错 ➔ Reflection 模型自愈）</li>
 *   <li>test04_probe_score: 探针打分——本地工具纳秒级执行开销与系统开销检测</li>
 * </ul>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ToolFunctionCallingTest {

    @Autowired
    private AgentCore agentCore;

    @Autowired
    private GetWeatherTool getWeatherTool;

    @Autowired
    private GetStockPriceTool getStockPriceTool;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    private boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.contains("你的");
    }

    @BeforeEach
    void setUp() {
        // 每轮单测执行前重置调用探针计数器，确保测试间物理隔离与精确可观测
        ToolCallbackBuilder.resetCallCounts();
    }

    @Test
    @Order(1)
    @DisplayName("test01_positive: 验证工具自动注册与大模型端到端 Function Calling 正例")
    void test01_positive() {
        System.out.println("\n========================================================");
        System.out.println("【测试 1：工具注册验证 + 端到端 Function Calling 自动调用】");
        System.out.println("========================================================");

        // 1. 验证 InnerTool 能够正确生成 ToolCallback 定义和 JSON Schema
        List<ToolCallback> weatherCallbacks = getWeatherTool.loadToolCallbacks();
        assertNotNull(weatherCallbacks);
        assertEquals(1, weatherCallbacks.size());

        ToolCallback weatherCallback = weatherCallbacks.get(0);
        assertEquals("get_weather", weatherCallback.getToolDefinition().name());
        assertNotNull(weatherCallback.getToolDefinition().inputSchema());
        System.out.println("✅ [工具定义验证] 工具名称: " + weatherCallback.getToolDefinition().name());
        System.out.println("✅ [工具定义验证] 入参Schema: " + weatherCallback.getToolDefinition().inputSchema());

        // 2. 如果配置了真实 API Key，发起端到端 Function Calling 真实调用
        assumeTrue(isApiKeyConfigured(), "未配置有效 API Key，跳过真实大模型网络交互测试");

        String sessionId = "test-tool-session-001";
        String userQuery = "请帮我查一下苹果公司股票代码 AAPL 的当前价格和涨跌幅是多少？";
        System.out.println("\n🗣️ [用户提问] " + userQuery);

        long startTime = System.currentTimeMillis();
        String response = agentCore.chat(sessionId, userQuery);
        long elapsedMs = System.currentTimeMillis() - startTime;

        System.out.println("\n🤖 [大模型最终回复]\n" + response);
        System.out.println("⏱️ 端到端耗时: " + elapsedMs + " ms");

        // 3. 断言工具确实被触发调用了 1 次 (Action 发生)
        int callCount = ToolCallbackBuilder.getCallCount("get_stock_price");
        System.out.println("🔍 [可观测探针] get_stock_price 工具被调用次数: " + callCount);
        assertTrue(callCount >= 1, "大模型必须自主决策并发起 Action 调用股票工具");

        // 4. 断言大模型成功将工具结果整合至回复
        assertNotNull(response);
        assertTrue(response.contains("150.25") || response.contains("150"), "回复中必须包含工具返回的真实价格数据 150.25");
        assertTrue(response.contains("2.5%") || response.contains("2.5"), "回复中必须包含工具返回的涨跌幅数据 2.5%");

        System.out.println("\n>>> ✅ 正例测试通过：大模型成功自主决策、调用 GetStockPriceTool 并完美将数据整合至回复！");
    }

    @Test
    @Order(2)
    @DisplayName("test02_phenomenon1_schema_gating: 验证【现象 1】大模型前置 Schema 门禁拦截（零工具调用直接反问哪一天）")
    void test02_phenomenon1_schema_gating() {
        System.out.println("\n========================================================");
        System.out.println("【测试 2：现象 1 验证 —— 大模型前置 Schema 门禁拦截（零工具调用）】");
        System.out.println("========================================================");

        assumeTrue(isApiKeyConfigured(), "未配置有效 API Key，跳过真实大模型交互测试");

        String sessionId = "test-schema-gating-002";
        // 用户模糊提问：只给了城市，没给日期
        String vagueQuery = "请帮我查一下深圳的天气。";
        System.out.println("\n🗣️ [用户模糊提问（缺少必填 date）] " + vagueQuery);

        long startTime = System.currentTimeMillis();
        String response = agentCore.chat(sessionId, vagueQuery);
        long elapsedMs = System.currentTimeMillis() - startTime;

        System.out.println("\n🤖 [大模型回复]\n" + response);
        System.out.println("⏱️ 端到端耗时: " + elapsedMs + " ms");

        // 1. 核心可观测断言：控制台【完全没有】跳出工具调用！工具调用计数必须严格为 0
        int weatherCallCount = ToolCallbackBuilder.getCallCount("get_weather");
        System.out.println("🔍 [可观测探针] get_weather 工具被调用次数: " + weatherCallCount);
        assertEquals(0, weatherCallCount, "【现象 1 核心特征】大模型在 Thought 阶段因 Schema 必填参数缺失直接拦截，绝不调用工具！调用次数必须为 0！");

        // 2. 断言大模型直接在回复中向用户主动反问澄清日期
        assertNotNull(response);
        boolean asksForDate = response.contains("哪一天")
                || response.contains("哪天")
                || response.contains("具体日期")
                || response.contains("日期")
                || response.contains("什么时间")
                || response.contains("什么时候");
        assertTrue(asksForDate, "大模型未调工具，而是直接向用户反问澄清日期！实际回复: " + response);

        System.out.println("\n>>> ✅ 现象 1 验证通过：大模型严格遵循 Tool Schema 门禁，零工具调用直接反问用户！");
    }

    @Test
    @Order(3)
    @DisplayName("test03_phenomenon2_react_observation_healing: 验证【现象 2】真实 ReAct 闭环（Action ➔ 错误 Observation ➔ 模型自愈）")
    void test03_phenomenon2_react_observation_healing() {
        System.out.println("\n========================================================");
        System.out.println("【测试 3：现象 2 验证 —— 真实 ReAct 闭环（工具错误 Observation ➔ 模型自愈）】");
        System.out.println("========================================================");

        // 1. 本地工具层验证：传入不存在的股票代码，工具返回 ACI 强类型结构化失败 Observation
        List<ToolCallback> callbacks = getStockPriceTool.loadToolCallbacks();
        ToolCallback stockCallback = callbacks.get(0);
        String notExistJson = "{\"symbol\": \"NOTEXIST999\"}";
        String toolObservation = stockCallback.call(notExistJson);
        System.out.println("🛠️ [工具层 ACI 强类型 Observation 观察反馈]:\n" + toolObservation);
        assertTrue(toolObservation.contains("\"status\":\"ERROR\""), "工具应遵循 ACI 规范返回 status: ERROR");
        assertTrue(toolObservation.contains("\"code\":\"SYMBOL_NOT_FOUND\""), "工具应返回机器可读错误码 SYMBOL_NOT_FOUND");
        assertTrue(toolObservation.contains("NOTEXIST999"), "错误 Observation 中应指明未找到的股票代码");

        // 2. 端到端 ReAct 闭环验证：用户提问一个语法合规但业务不存在的标的
        assumeTrue(isApiKeyConfigured(), "未配置有效 API Key，跳过真实大模型网络交互测试");

        String sessionId = "test-react-healing-003";
        String invalidQuery = "请帮我查一下股票代码 NOTEXIST999 的当前价格是多少？";
        System.out.println("\n🗣️ [用户提问不存在的股票] " + invalidQuery);

        long startTime = System.currentTimeMillis();
        String response = agentCore.chat(sessionId, invalidQuery);
        long elapsedMs = System.currentTimeMillis() - startTime;

        System.out.println("\n🤖 [大模型自愈后的最终回复]\n" + response);
        System.out.println("⏱️ 端到端耗时: " + elapsedMs + " ms");

        // 3. 核心可观测断言：
        // 【与现象 1 的本质区别】：ReAct 模式下 Action 必须真实触发（调用次数 >= 1）！
        int stockCallCount = ToolCallbackBuilder.getCallCount("get_stock_price");
        System.out.println("🔍 [可观测探针] get_stock_price 工具被调用次数: " + stockCallCount);
        assertTrue(stockCallCount >= 1, "【现象 2 核心特征】必须真实发生 Action 工具调用！");

        // 4. 断言大模型观察到工具返回的失败 Observation，理解并自愈向用户解释
        assertNotNull(response);
        boolean healsAndExplains = response.contains("未找到")
                || response.contains("不存在")
                || response.contains("无法找到")
                || response.contains("核实")
                || response.contains("NOTEXIST999")
                || response.contains("AAPL");
        assertTrue(healsAndExplains, "大模型接收到工具返回的失败 Observation 后，必须反思自愈并向用户友好解释！实际回复: " + response);

        System.out.println("\n>>> ✅ 现象 2 验证通过：工具执行发生 ➔ 宿主返回错误 Observation ➔ 模型动态反思自愈！");
    }

    @Test
    @Order(4)
    @DisplayName("test04_react_multi_turn_self_healing_retry: 验证多轮 ReAct 闭环自愈重试（第 1 次报错 ➔ 观察错误反馈 ➔ 第 2 次自我修正并调用成功）")
    void test04_react_multi_turn_self_healing_retry() {
        System.out.println("\n========================================================");
        System.out.println("【测试 4：多轮 ReAct 闭环自愈 —— 工具报错 ➔ 自我修正 ➔ 二次重试成功】");
        System.out.println("========================================================");

        assumeTrue(isApiKeyConfigured(), "未配置有效 API Key，跳过真实大模型网络交互测试");

        String sessionId = "test-react-multiturn-healing-004";
        // 故意传入带特定后缀的标的代码 BABA-HK，由于用户明确指定代码为 BABA-HK，大模型首次必须传入该代码
        String query = "请帮我查一下美股股票代码 BABA-HK 的当前价格和涨跌幅是多少？";
        System.out.println("\n🗣️ [用户提问] " + query);

        long startTime = System.currentTimeMillis();
        String response = agentCore.chat(sessionId, query);
        long elapsedMs = System.currentTimeMillis() - startTime;

        System.out.println("\n🤖 [大模型自愈重试后的最终回复]\n" + response);
        System.out.println("⏱️ 端到端耗时: " + elapsedMs + " ms");

        int stockCallCount = ToolCallbackBuilder.getCallCount("get_stock_price");
        System.out.println("🔍 [可观测探针] get_stock_price 工具被调用总次数: " + stockCallCount);

        // 核心断言 1：必须发生至少 2 次工具调用（第 1 次传 BABA-HK 失败，第 2 次修正为 BABA 成功）
        assertEquals(2, stockCallCount, "【ReAct 真实多轮自愈核心特征】必须经历 2 次工具调用（初次报错 ➔ 自我纠错 ➔ 重试成功）！");

        // 核心断言 2：大模型最终成功拿到了 BABA 的真实数据 (88.50 和 +3.1%)
        assertNotNull(response);
        assertTrue(response.contains("88.50") || response.contains("88.5"), "最终回复必须包含纠错重试后拿到的真实股价 88.50！");
        assertTrue(response.contains("3.1%") || response.contains("3.1"), "最终回复必须包含纠错重试后拿到的真实涨跌幅 +3.1%！");

        System.out.println("\n>>> ✅ ReAct 真实多轮自愈重试测试通过：大模型遭遇工具报错后未放弃，而是动态反思、自我修正参数并二次重试成功！");
    }

    @Test
    @Order(5)
    @DisplayName("test05_probe_score: 探针检测——本地工具执行基准耗时与系统开销打分")
    void test05_probe_score() {
        System.out.println("\n========================================================");
        System.out.println("【测试 5：探针打分——本地工具纳秒级执行开销检测】");
        System.out.println("========================================================");

        List<ToolCallback> callbacks = getStockPriceTool.loadToolCallbacks();
        ToolCallback stockCallback = callbacks.get(0);

        long startNano = System.nanoTime();
        String result = stockCallback.call("{\"symbol\": \"GOOGL\"}");
        long durationNano = System.nanoTime() - startNano;

        double durationMs = durationNano / 1_000_000.0;
        System.out.println("================ 本地工具探针指标 ================");
        System.out.println("工具名称     : " + stockCallback.getToolDefinition().name());
        System.out.println("本地执行耗时 : " + durationMs + " ms");
        System.out.println("返回载荷长度 : " + result.length() + " 字符");
        System.out.println("==================================================");

        assertTrue(durationMs < 50.0, "本地工具执行延迟应在 50ms 以内（当前纯内存模拟）");
        assertTrue(result.contains("GOOGL"));

        System.out.println(">>> ✅ 探针测试通过：本地工具拦截与调度开销极低，符合高并发低延迟标准！");
    }
}
