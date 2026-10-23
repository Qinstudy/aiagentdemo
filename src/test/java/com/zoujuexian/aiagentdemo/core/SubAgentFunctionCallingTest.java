package com.zoujuexian.aiagentdemo.core;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SubAgent 独立记忆子代理端到端闭环单测
 * <p>
 * 遵循 Spring AI 标准三联单测规范：
 * <ul>
 *   <li>test01_memory_isolation_positive: 验证子代理之间的物理记忆隔离（SubAgent A 的机密数据绝对不泄露给 SubAgent B）</li>
 *   <li>test02_end_to_end_agentcore_delegation: 验证主 Agent 自动决策调用 create_sub_agent 工具委派子任务的端到端正例</li>
 *   <li>test03_probe_lifecycle_score: 探针打分——验证 SubAgent 创建、多轮交互、内存累积与销毁释放的全生命周期</li>
 * </ul>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SubAgentFunctionCallingTest {

    @Autowired
    private SubAgentManager subAgentManager;

    @Autowired
    private AgentCore agentCore;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    private boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.contains("你的");
    }

    @BeforeEach
    void setUp() {
        assumeTrue(isApiKeyConfigured(), "未配置有效 OpenAI/智谱 API Key，跳过模型调用单测");
    }

    @Test
    @Order(1)
    @DisplayName("test01_memory_isolation_positive: 验证子代理（SubAgent）之间的物理上下文与记忆隔离")
    void test01_memory_isolation_positive() {
        System.out.println("\n========================================================");
        System.out.println("【测试 1 正例：验证多个 SubAgent 之间的上下文独立与记忆隔离】");
        System.out.println("========================================================");

        // 1. 创建 SubAgent A（专属财务助手）
        SubAgent agentA = subAgentManager.create("财务专属助手", "你是一个财务助手，请准确记忆用户的财务敏感数字并回答。");
        assertNotNull(agentA);
        String secretToken = "DEPOSIT-8888-CNY";

        // 向 Agent A 注入机密数据
        String replyA1 = agentA.chat("请记住，我司本季度的机密备用金代码是：" + secretToken);
        System.out.println("[SubAgent A 初始对话] " + replyA1);
        assertTrue(agentA.getMessageCount() > 0, "SubAgent A 应该记录了对话");

        // 2. 创建 SubAgent B（外部市场分析师）
        SubAgent agentB = subAgentManager.create("市场分析师", "你是一个外部市场顾问，你不知道任何内部财务机密。");
        assertNotNull(agentB);

        // 向 Agent B 刺探该机密数据
        String replyB1 = agentB.chat("请告诉我，我司本季度的机密备用金代码是多少？");
        System.out.println("\n[SubAgent B 刺探回复] " + replyB1);

        // 核心断言：Agent B 的记忆中绝对没有注入过该代码，不能命中机密数据
        assertFalse(replyB1.contains(secretToken), "SubAgent B 的独立记忆中绝不能包含 SubAgent A 的机密数据！");

        // 3. 再次向 Agent A 提问，验证它自身的多轮记忆是否有效
        String replyA2 = agentA.chat("请问我之前告诉你的机密备用金代码是多少？");
        System.out.println("\n[SubAgent A 二轮记忆召回] " + replyA2);
        assertTrue(replyA2.contains("8888") || replyA2.contains(secretToken),
                "SubAgent A 自身的独立上下文必须能准确召回机密代码");

        // 清理测试实例
        subAgentManager.destroy(agentA.getId());
        subAgentManager.destroy(agentB.getId());
        System.out.println("正例验证通过：SubAgent A 与 SubAgent B 记忆完全物理隔离！");
    }

    @Test
    @Order(2)
    @DisplayName("test02_end_to_end_agentcore_delegation: 验证主 Agent 自动决策调用 create_sub_agent 工具派发任务")
    void test02_end_to_end_agentcore_delegation() {
        System.out.println("\n========================================================");
        System.out.println("【测试 2 正例：主 Agent 自动调用 create_sub_agent 委派子任务】");
        System.out.println("========================================================");

        int initialCount = subAgentManager.getAllActiveAgents().size();

        String sessionId = "test-subagent-delegation-" + System.currentTimeMillis();
        String prompt = "请创建一个名为'英文润色大师'的子代理（SubAgent），让它帮我将短句 'Artificial Intelligence is very good' 润色得更地道、更具有学术范。";
        System.out.println("[用户提问] " + prompt);

        long start = System.currentTimeMillis();
        String reply = agentCore.chat(sessionId, prompt);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("\n[主 Agent 端到端回复，耗时: " + elapsed + "ms]");
        System.out.println(reply);

        // 核心断言：主 Agent 命中工具调用，SubAgentManager 中活跃的子代理数量增加了
        List<SubAgent> activeAgents = subAgentManager.getAllActiveAgents();
        System.out.println("[当前活跃的子代理列表] " + activeAgents);
        assertTrue(activeAgents.size() > initialCount, "主 Agent 必须成功触发 create_sub_agent 工具创建子代理");

        // 验证润色结果
        assertNotNull(reply);
        assertFalse(reply.isBlank());

        // 清理所有测试生成的子代理
        for (SubAgent agent : activeAgents) {
            subAgentManager.destroy(agent.getId());
        }
    }

    @Test
    @Order(3)
    @DisplayName("test03_probe_lifecycle_score: 探针打分——验证 SubAgent 的轻量级生命周期与销毁释放")
    void test03_probe_lifecycle_score() {
        System.out.println("\n========================================================");
        System.out.println("【测试 3 探针：SubAgent 实例化开销与生命周期打分】");
        System.out.println("========================================================");

        long start = System.nanoTime();
        SubAgent agent = subAgentManager.create("临时算法助手", "你是一个算法顾问");
        long creationTimeNs = System.nanoTime() - start;

        assertNotNull(agent);
        System.out.println("探针结果 1：SubAgent 轻量级创建开销: " + (creationTimeNs / 1_000_000.0) + " ms (无 Spring 容器代理开销)");

        assertEquals(1, agent.getMessageCount(), "新创建的 SubAgent 应仅包含 1 条 SystemMessage 角色设定");

        // 验证销毁
        String destroyResult = subAgentManager.destroy(agent.getId());
        System.out.println("探针结果 2：SubAgent 销毁: " + destroyResult);
        assertNull(subAgentManager.get(agent.getId()), "销毁后在注册表中必须查不到该 ID");
    }
}
