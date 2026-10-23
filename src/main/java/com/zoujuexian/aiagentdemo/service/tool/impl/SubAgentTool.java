package com.zoujuexian.aiagentdemo.service.tool.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zoujuexian.aiagentdemo.core.SubAgentManager;
import com.zoujuexian.aiagentdemo.service.tool.InnerTool;
import com.zoujuexian.aiagentdemo.service.tool.ToolCallbackBuilder;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SubAgent 工具
 * <p>
 * 将 SubAgent 的创建、对话、销毁能力注册为主 Agent 的 Tool，
 * 让主 LLM 自主决策何时需要创建 SubAgent 来处理子任务。
 * <p>
 * 提供 3 个工具：
 * <ul>
 *   <li>create_sub_agent：创建 SubAgent 并执行首个任务</li>
 *   <li>chat_with_sub_agent：与已有 SubAgent 继续对话</li>
 *   <li>destroy_sub_agent：销毁 SubAgent 释放资源</li>
 * </ul>
 */
@Component
public class SubAgentTool implements InnerTool {

    @Resource
    private SubAgentManager subAgentManager;

    @Override
    public List<ToolCallback> loadToolCallbacks() {
        return List.of(
                buildCreateSubAgentCallback(),
                buildChatWithSubAgentCallback(),
                buildDestroySubAgentCallback()
        );
    }

    /**
     * 工具1：创建 SubAgent 并执行首个任务
     * <p>
     * 当主 Agent 判断某个子任务需要独立的上下文记忆来处理时（如代码审查、翻译、
     * 数据分析、多步推理等），调用此工具创建一个专门的 SubAgent。
     */
    private ToolCallback buildCreateSubAgentCallback() {
        Map<String, Map<String, String>> properties = Map.of(
                "name", Map.of("type", "string",
                        "description", "SubAgent 的名称，描述其角色，如：代码审查专家、翻译助手、数据分析师"),
                "system_prompt", Map.of("type", "string",
                        "description", "SubAgent 的角色定义和行为约束，定义它应该如何工作、擅长什么、输出格式等"),
                "task", Map.of("type", "string",
                        "description", "交给 SubAgent 的首个任务描述")
        );

        return ToolCallbackBuilder.build(
                "create_sub_agent",
                "创建一个拥有独立记忆的子代理（SubAgent）来处理特定子任务。"
                        + "SubAgent 拥有独立的对话上下文，适合需要多轮交互、独立推理的复杂子任务。"
                        + "创建后会立即执行首个任务并返回结果。"
                        + "返回结果中包含 SubAgent 的 ID，后续可通过 chat_with_sub_agent 继续与其对话。",
                properties,
                List.of("name", "system_prompt", "task"),
                this::executeCreate
        );
    }

    /**
     * 工具2：与已有 SubAgent 继续对话
     */
    private ToolCallback buildChatWithSubAgentCallback() {
        Map<String, Map<String, String>> properties = Map.of(
                "agent_id", Map.of("type", "string",
                        "description", "SubAgent 的唯一标识 ID（由 create_sub_agent 返回）"),
                "message", Map.of("type", "string",
                        "description", "发送给 SubAgent 的消息")
        );

        return ToolCallbackBuilder.build(
                "chat_with_sub_agent",
                "与已创建的 SubAgent 继续对话。SubAgent 会记住之前的对话内容，"
                        + "适合需要多轮交互来逐步完善结果的场景。",
                properties,
                List.of("agent_id", "message"),
                this::executeChat
        );
    }

    /**
     * 工具3：销毁 SubAgent
     */
    private ToolCallback buildDestroySubAgentCallback() {
        Map<String, Map<String, String>> properties = Map.of(
                "agent_id", Map.of("type", "string",
                        "description", "要销毁的 SubAgent 的唯一标识 ID")
        );

        return ToolCallbackBuilder.build(
                "destroy_sub_agent",
                "销毁一个 SubAgent，释放其记忆资源。当子任务完成后应及时销毁不再需要的 SubAgent。",
                properties,
                List.of("agent_id"),
                this::executeDestroy
        );
    }

    private String executeCreate(String argumentsJson) {
        JSONObject args = JSON.parseObject(argumentsJson);
        String name = args.getString("name");
        // 并将大模型生成的子人设作为其独占的 SystemMessage
        String systemPrompt = args.getString("system_prompt");
        String task = args.getString("task");
        return subAgentManager.createAndChat(name, systemPrompt, task);
    }

    private String executeChat(String argumentsJson) {
        JSONObject args = JSON.parseObject(argumentsJson);
        String agentId = args.getString("agent_id");
        String message = args.getString("message");
        return subAgentManager.chat(agentId, message);
    }

    private String executeDestroy(String argumentsJson) {
        JSONObject args = JSON.parseObject(argumentsJson);
        String agentId = args.getString("agent_id");
        return subAgentManager.destroy(agentId);
    }
}
