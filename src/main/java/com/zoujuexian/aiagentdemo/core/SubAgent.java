package com.zoujuexian.aiagentdemo.core;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 子代理（SubAgent）
 * <p>
 * 运行时动态创建的轻量级 Agent 实例，拥有独立的对话记忆和 system prompt，
 * 共享主 Agent 的 ChatClient。适用于需要独立上下文的子任务场景，
 * 如代码审查、翻译、数据分析等。
 * <p>
 * 与 AgentCore 的区别：
 * <ul>
 *   <li>不包含意图识别、RAG 等复杂编排逻辑</li>
 *   <li>不是 Spring Bean，由 SubAgentManager 动态管理生命周期</li>
 *   <li>专注于在独立记忆上下文中完成特定子任务</li>
 * </ul>
 */
public class SubAgent {

    private final String id;
    private final String name;
    private final ChatClient chatClient;
    private final ChatMemory memory;
    private final LocalDateTime createdAt;

    /**
     * @param id         唯一标识
     * @param name       SubAgent 名称（由主 LLM 指定）
     * @param systemPrompt SubAgent 的角色定义（由主 LLM 指定）
     * @param chatClient 共享的 ChatClient 实例
     */
    public SubAgent(String id, String name, String systemPrompt, ChatClient chatClient) {
        this.id = id;
        this.name = name;
        this.chatClient = chatClient;
        // 分配独立的私有记忆容器（不启用摘要压缩，物理隔离）
        this.memory = ChatMemory.forSubAgent();
        // 注入子代理专属岗位职责
        this.memory.setSystemPrompt(systemPrompt);
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 在独立的记忆上下文中与 SubAgent 对话
     *
     * @param userMessage 用户/主 Agent 发送的消息
     * @return SubAgent 的回复
     */
    public String chat(String userMessage) {
        memory.addMessage(new UserMessage(userMessage));

        List<Message> messages = memory.getMessages();
        Prompt prompt = new Prompt(messages, OpenAiChatOptions.builder()
                .temperature(0.7)
                .maxTokens(2048)
                .build());

        String response = chatClient.prompt(prompt).call().content();
        String safeResponse = (response != null) ? response : "";

        memory.addMessage(new AssistantMessage(safeResponse));
        return safeResponse;
    }

    /**
     * 获取当前记忆中的消息轮数
     */
    public int getMessageCount() {
        return memory.getMessages().size();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "SubAgent{id='" + id + "', name='" + name + "', messages=" + getMessageCount()
                + ", createdAt=" + createdAt + "}";
    }
}
