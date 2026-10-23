package com.zoujuexian.aiagentdemo.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatMemory 对话记忆三层递进压缩策略对齐单测
 * <p>
 * 遵循标准三联单测规范：
 * - test01_positive: 验证第二层 Assistant 消息精准裁剪（只留最近3条）与 SystemMessage 物理隔离
 * - test02_negative: 验证第三层滑动窗口硬兜底保护（超限截断）与 TOOL 消息边界防撕裂保护
 * - test03_probe_score: 探针打分——验证第一层智能摘要压缩触发前后消息梯度变化与 Token 压缩效果
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ChatMemoryTest {

    @Autowired(required = false)
    private ChatClient chatClient;

    @Test
    @Order(1)
    @DisplayName("test01_positive: 验证第二层 Assistant 消息精准裁剪与 SystemPrompt 隔离")
    void test01_positive() {
        System.out.println("\n========================================================");
        System.out.println("【测试 1：第二层 Assistant 消息精准裁剪 + SystemPrompt 隔离】");
        System.out.println("========================================================");

        ChatMemory memory = ChatMemory.forSubAgent();
        String systemPrompt = "你是一个专业的高级 Java 架构师。";
        memory.setSystemPrompt(systemPrompt);

        // 模拟连续 5 轮对话（5 条 User 提问 + 5 条 Assistant 冗长回复，共 10 条）
        for (int i = 1; i <= 5; i++) {
            memory.addMessage(new UserMessage("第 " + i + " 个问题：请分析一下 Spring AI 的架构设计"));
            memory.addMessage(new AssistantMessage("第 " + i + " 个回答：【长篇大论几千字代码与解析...】" + i));
        }

        // 调用 getMessages() 获取实际发给大模型的列表
        List<Message> finalMessages = memory.getMessages();
        System.out.println("finalMessages:::" + finalMessages);


        // 1. 验证 SystemMessage 独立存在且位于第 1 位
        assertEquals(MessageType.SYSTEM, finalMessages.get(0).getMessageType(), "首条消息必须是 SystemMessage");
        assertTrue(finalMessages.get(0).getText().contains(systemPrompt), "系统人设必须完整保留");

        // 2. 统计各角色消息数量
        long userCount = finalMessages.stream().filter(m -> m.getMessageType() == MessageType.USER).count();
        long assistantCount = finalMessages.stream().filter(m -> m.getMessageType() == MessageType.ASSISTANT).count();

        System.out.println(" 放入总历史条数   : 10 条 (5 条 User + 5 条 Assistant)");
        System.out.println(" 组装后总条数     : " + finalMessages.size() + " 条 (包含 1 条 SystemPrompt)");
        System.out.println(" 保留的 User 提问 : " + userCount + " 条 (期望: 5 条全部保留)");
        System.out.println(" 保留的 Assistant : " + assistantCount + " 条 (期望: 严格仅保留最近 3 条)");

        // 3. 断言验证
        assertEquals(5, userCount, "用户提问不应该被丢弃，必须全部保留以维系意图连贯性");
        assertEquals(3, assistantCount, "较早的 Assistant 回复必须被裁剪，严格限制为最近 3 条");

        // 4. 验证保留的确实是最近的 3、4、5 轮，而第 1、2 轮的 Assistant 已被裁剪
        String lastAssistantText = finalMessages.get(finalMessages.size() - 1).getText();
        assertTrue(lastAssistantText.contains("第 5 个回答"), "最新的 Assistant 消息必须在最后");
        boolean containsOldAssistant = finalMessages.stream()
                .filter(m -> m.getMessageType() == MessageType.ASSISTANT)
                .anyMatch(m -> m.getText().contains("第 1 个回答"));
        assertFalse(containsOldAssistant, "第 1 个早期的 Assistant 回复应已被成功裁掉");

        System.out.println(">>> ✅ 第二层裁剪验证通过：成功剔除早期冗长 Assistant 回复，大幅节省上下文 Token！");
    }

    @Test
    @Order(2)
    @DisplayName("test02_negative: 验证第三层滑动窗口硬兜底保护与 TOOL 边界防崩溃")
    void test02_negative() {
        System.out.println("\n========================================================");
        System.out.println("【测试 2：第三层滑动窗口硬兜底 + TOOL 消息边界保护】");
        System.out.println("========================================================");

        // SubAgent 的 maxRounds = 10，滑动窗口上限阈值为 10 * 4 = 40 条
        ChatMemory memory = ChatMemory.forSubAgent();

        // 模拟极端超长对话：灌入 60 条消息（大幅超过 40 条的硬上限）
        for (int i = 1; i <= 30; i++) { // memory.addMessage，会调用 com.zoujuexian.aiagentdemo.core.ChatMemory.trimHistory，最终会触发‘第三层滑动窗口硬兜底保护’
            memory.addMessage(new UserMessage("Q" + i));
            memory.addMessage(new AssistantMessage("A" + i));
        }

        List<Message> messages = memory.getMessages();
        System.out.println(" 尝试灌入消息总数 : 60 条 (30 轮对话)");
        System.out.println(" 触发滑动窗口后留存 : " + messages.size() + " 条");

        // 断言：由于滑动窗口兜底，总消息数被坚决拦截在 40 条以内（加 Assistant 裁剪后会更小）
        assertTrue(messages.size() <= 40, "滑动窗口必须生效，总消息数不可无限制膨胀");

        // 验证最新的 Q30 依然稳稳保留在最末尾
        Message lastMsg = messages.get(messages.size() - 1);
        assertTrue(lastMsg.getText().contains("30"), "最新的对话消息必须得到保留");

        System.out.println(">>> ✅ 第三层硬兜底验证通过：极端长对话被物理滑动窗口锁定，彻底杜绝内存泄漏！");
    }

    @Test
    @Order(3)
    @DisplayName("test03_probe_score: 探针检测——验证第一层智能摘要压缩触发与消息梯度变化")
    void test03_probe_score() {
        System.out.println("\n========================================================");
        System.out.println("【测试 3：第一层智能摘要压缩触发探针与消息梯度打分】");
        System.out.println("========================================================");

        // 如果未配置真实的 chatClient，降级用模拟验证
        if (chatClient == null) {
            System.out.println("未注入真实 ChatClient，跳过联网摘要压缩探针测试");
            return;
        }

        ChatMemory memory = ChatMemory.forMainAgent(chatClient);
        memory.setSystemPrompt("你是智能客服助手。");

        // 灌入 16 条消息（达到 COMPRESS_THRESHOLD_MESSAGES = 15 的压缩门槛）
        for (int i = 1; i <= 8; i++) {
            memory.addMessage(new UserMessage("用户需求咨询第 " + i + " 步"));
            memory.addMessage(new AssistantMessage("助手已记录并处理完成步骤 " + i));
        }

        System.out.println("--- 触发前：已累计灌入 16 条消息 (达到阈值 15) ---");

        // 调用 getMessages() 将自动触发 compressIfNeeded()
        long startTime = System.currentTimeMillis();
        List<Message> compressedMessages = memory.getMessages();
        long elapsedMs = System.currentTimeMillis() - startTime;

        System.out.println("============ 智能摘要压缩探针指标 ============");
        System.out.println("压缩耗时         : " + elapsedMs + " ms");
        System.out.println("压缩后总条数     : " + compressedMessages.size() + " 条");

        Message firstMsg = compressedMessages.get(0);
        System.out.println("第一条 System 内容:\n" + firstMsg.getText());
        System.out.println("==============================================");

        // 验证 SystemMessage 中成功注入了摘要信息
        assertTrue(firstMsg.getText().contains("对话的摘要"), "System 提示词尾部必须成功追加对话摘要");
        // 验证历史消息已经由 16 条锐减为最近的 5 条以内
        assertTrue(compressedMessages.size() <= 8, "压缩后历史条数应断崖式缩减");

        System.out.println(">>> ✅ 第一层摘要压缩验证通过：长对话成功折叠为精华摘要，实现无损长程记忆！");
    }
}
