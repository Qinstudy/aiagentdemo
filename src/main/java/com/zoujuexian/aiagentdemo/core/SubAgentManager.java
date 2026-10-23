package com.zoujuexian.aiagentdemo.core;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SubAgent 生命周期管理器
 * <p>
 * 负责 SubAgent 的创建、查找、销毁，维护所有活跃 SubAgent 实例的注册表。
 * 线程安全，支持并发访问。
 */
@Component
public class SubAgentManager {

    /** 活跃的 SubAgent 实例，key 为 agentId */
    private final Map<String, SubAgent> activeAgents = new ConcurrentHashMap<>();

    /** 共享的 ChatClient，所有 SubAgent 复用 */
    private ChatClient chatClient;

    /**
     * 设置共享的 ChatClient（主 Agent 切换模型时需要同步更新）
     */
    public void setChatClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 创建一个新的 SubAgent
     *
     * @param name         SubAgent 名称（描述其角色）
     * @param systemPrompt SubAgent 的角色定义和行为约束
     * @return 创建的 SubAgent 实例。 ChatMemory.forSubAgent() 分配私有的ChatMemory 实例
     */
    public SubAgent create(String name, String systemPrompt) {
        String agentId = generateAgentId();
        SubAgent subAgent = new SubAgent(agentId, name, systemPrompt, chatClient);
        activeAgents.put(agentId, subAgent);
        System.out.println("[SubAgent] 已创建: " + subAgent);
        return subAgent;
    }

    /**
     * 创建 SubAgent 并立即执行首个任务
     *
     * @param name         SubAgent 名称
     * @param systemPrompt SubAgent 的角色定义
     * @param initialTask  首个任务消息
     * @return SubAgent 对首个任务的回复
     * 子代理将回复存入自己的私有记忆，并把结果作为纯文本 String 向上交出。
     */
    public String createAndChat(String name, String systemPrompt, String initialTask) {
        SubAgent subAgent = create(name, systemPrompt);
        String response = subAgent.chat(initialTask);
        return "[SubAgent " + subAgent.getId() + " (" + name + ")] " + response;
    }

    /**
     * 向指定 SubAgent 发送消息
     *
     * @param agentId SubAgent 的唯一标识
     * @param message 消息内容
     * @return SubAgent 的回复，找不到时返回错误提示
     */
    public String chat(String agentId, String message) {
        SubAgent subAgent = activeAgents.get(agentId);
        if (subAgent == null) {
            return "[错误] 未找到 SubAgent: " + agentId + "。请先使用 create_sub_agent 创建。";
        }
        String response = subAgent.chat(message);
        return "[SubAgent " + agentId + " (" + subAgent.getName() + ")] " + response;
    }

    /**
     * 销毁指定 SubAgent，释放其记忆
     *
     * @param agentId SubAgent 的唯一标识
     * @return 操作结果描述
     */
    public String destroy(String agentId) {
        SubAgent removed = activeAgents.remove(agentId);
        if (removed == null) {
            return "未找到 SubAgent: " + agentId;
        }
        System.out.println("[SubAgent] 已销毁: " + removed);
        return "已销毁 SubAgent: " + removed.getName() + " (id=" + agentId + ")";
    }

    /**
     * 列出所有活跃的 SubAgent
     *
     * @return 活跃 SubAgent 的描述列表
     */
    public String listActiveAgents() {
        if (activeAgents.isEmpty()) {
            return "当前没有活跃的 SubAgent。";
        }
        StringBuilder result = new StringBuilder("活跃的 SubAgent 列表：\n");
        for (SubAgent agent : activeAgents.values()) {
            result.append("- ").append(agent.toString()).append("\n");
        }
        return result.toString().trim();
    }

    /**
     * 获取指定 SubAgent
     */
    public SubAgent get(String agentId) {
        return activeAgents.get(agentId);
    }

    /**
     * 获取所有活跃的 SubAgent
     */
    public List<SubAgent> getAllActiveAgents() {
        return new ArrayList<>(activeAgents.values());
    }

    /**
     * 生成唯一的 SubAgent ID（短格式，便于模型引用）
     */
    private String generateAgentId() {
        return "sa-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
