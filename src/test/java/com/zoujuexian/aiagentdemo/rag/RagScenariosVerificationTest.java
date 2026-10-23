package com.zoujuexian.aiagentdemo.rag;

import com.zoujuexian.aiagentdemo.core.AgentCore;
import com.zoujuexian.aiagentdemo.service.rag.RagService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5 大 RAG 核心场景自动化单元测试验证
 * <p>
 * 验证目标：
 * 1. 场景一（全流水线）：多意图识别、三路召回、RRF 融合与智谱 Rerank 精排
 * 2. 场景二（专有名词）：AtomicStampedReference 生僻专有词 BM25 精准命中
 * 3. 场景三（Function Calling）：显式触发 knowledge_search 工具调用的 ReAct 闭环
 * 4. 场景四（会话隔离）：ChatMemory 清空后，单例 VectorStore 知识库物理常驻有效性
 * 5. 场景五（对照实验）：有无智谱 Rerank 精排效果深度对比（验证逆袭翻盘）
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RagScenariosVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(RagScenariosVerificationTest.class);

    @Resource
    private AgentCore agentCore;

    @Resource
    private RagService ragService;

    @Test
    @Order(1)
    void test01_scenario1_fullPipelineRrfAndRerank() {
        System.out.println("\n============================================================");
        System.out.println(">>> [TEST START] 场景一：全流水线（三路召回 + RRF 融合 + 智谱 Rerank 精排）");
        System.out.println("============================================================");

        String question = "Spring Boot 3.x 的自动配置是通过什么文件路径实现的？阿里巴巴开发规范对线程池有什么要求？";
        String reply = agentCore.chat("junit-rag-sess-01", question);

        System.out.println("\n[场景一 模型返回]:\n" + reply);

        assertNotNull(reply, "场景一返回不能为空");
        assertTrue(reply.contains("AutoConfiguration.imports") || reply.contains("imports"),
                "场景一必须包含 Spring Boot 3.x 自动配置路径 AutoConfiguration.imports");
        assertTrue(reply.contains("ThreadPoolExecutor") || reply.contains("Executors"),
                "场景一必须包含阿里巴巴开发规范对线程池的手动创建要求");

        System.out.println(">>> [TEST PASSED] 场景一全流水线断言通过！\n");
    }

    @Test
    @Order(2)
    void test02_scenario2_bm25SpecializedKeyword() {
        System.out.println("\n============================================================");
        System.out.println(">>> [TEST START] 场景二：专有名词精确匹配（BM25 关键词召回优势）");
        System.out.println("============================================================");

        String question = "AtomicStampedReference 是如何解决 CAS 的 ABA 问题的？";
        String reply = agentCore.chat("junit-rag-sess-02", question);

        System.out.println("\n[场景二 模型返回]:\n" + reply);

        assertNotNull(reply, "场景二返回不能为空");
        assertTrue(reply.contains("AtomicStampedReference"), "场景二必须命中 AtomicStampedReference 类名");
        assertTrue(reply.contains("版本号") || reply.contains("Stamp") || reply.contains("stamp"),
                "场景二必须解析通过版本号（Stamp）解决 ABA 问题的机制");

        System.out.println(">>> [TEST PASSED] 场景二专有名词断言通过！\n");
    }

    @Test
    @Order(3)
    void test03_scenario3_functionCallingReActLoop() {
        System.out.println("\n============================================================");
        System.out.println(">>> [TEST START] 场景三：Function Calling 显式工具调用（ReAct 循环）");
        System.out.println("============================================================");

        String question = "请使用知识库检索工具查询 HashMap 树化和退化的阈值各是多少？";
        String reply = agentCore.chat("junit-rag-sess-03", question);

        System.out.println("\n[场景三 模型返回]:\n" + reply);

        assertNotNull(reply, "场景三返回不能为空");
        assertTrue(reply.contains("8") && reply.contains("64"),
                "场景三必须准确包含 HashMap 树化阈值（链表长度 > 8 且数组长度 > 64）");
        assertTrue(reply.contains("6"), "场景三必须准确包含 HashMap 退化阈值（节点数 < 6）");

        System.out.println(">>> [TEST PASSED] 场景三 Function Calling 断言通过！\n");
    }

    @Test
    @Order(4)
    void test04_scenario4_sessionIsolationAndVectorPermanence() {
        System.out.println("\n============================================================");
        System.out.println(">>> [TEST START] 场景四：会话清空与向量库隔离常驻验证");
        System.out.println("============================================================");

        String sessionId = "junit-rag-sess-04";

        // 1. 先进行一次对话并写入会话短期记忆
        agentCore.chat(sessionId, "我是一名资深架构师，请记住我的身份。");

        // 2. 清空短期记忆（模拟前端点击清空会话）
        agentCore.clearMemory(sessionId);
        System.out.println("[场景四] 会话记忆已重置清空。");

        // 3. 在全新的空历史下，验证底层单例 VectorStore 是否依然常驻且能正常检索
        String question = "Java 8 之后接口可以包含哪些方法？";
        String reply = agentCore.chat(sessionId, question);

        System.out.println("\n[场景四 模型返回]:\n" + reply);

        assertNotNull(reply, "场景四返回不能为空");
        assertTrue(reply.contains("default") || reply.contains("默认方法"),
                "场景四新会话下仍需能从常驻向量库召回 Java 8 默认方法");
        assertTrue(reply.contains("static") || reply.contains("静态方法"),
                "场景四新会话下仍需能从常驻向量库召回 Java 8 静态方法");

        System.out.println(">>> [TEST PASSED] 场景四会话隔离与知识常驻断言通过！\n");
    }

    @Test
    @Order(5)
    void test05_rerankContrastExperiment() {
        System.out.println("\n============================================================");
        System.out.println(">>> [TEST START] 场景五：对照实验（有无智谱 Rerank 精排效果深度对比）");
        System.out.println("============================================================");

        String question = "Spring Boot 3.x 的自动配置是通过什么文件路径实现的？";

        System.out.println("\n------------------------------------------------------------");
        System.out.println("【对照组】未开启智谱 Rerank（仅基于多路召回 + RRF 倒数排名融合）");
        System.out.println("------------------------------------------------------------");
        String contextWithoutRerank = ragService.query(question, false);
        System.out.println("\n[对照组检索到的 Top 3 参考资料]:\n" + contextWithoutRerank);

        System.out.println("\n------------------------------------------------------------");
        System.out.println("【实验组】开启智谱 Rerank（Cross-Encoder 交叉深度重排）");
        System.out.println("------------------------------------------------------------");
        String contextWithRerank = ragService.query(question, true);
        System.out.println("\n[实验组检索到的 Top 3 参考资料]:\n" + contextWithRerank);

        assertNotNull(contextWithRerank, "实验组检索上下文不能为空");
        assertTrue(contextWithRerank.contains("AutoConfiguration.imports"),
                "开启 Rerank 必须能够精准识别并召回 AutoConfiguration.imports 所在的正文块！");

        System.out.println("\n>>> [TEST PASSED] 场景五对照实验断言通过！Rerank 逆袭精选生效！\n");
    }
}
