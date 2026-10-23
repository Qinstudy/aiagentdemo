package com.zoujuexian.aiagentdemo.core;

import com.zoujuexian.aiagentdemo.service.tool.InnerTool;
import com.zoujuexian.aiagentdemo.service.rag.RagService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Agent 核心编排器
 * <p>
 * 基于 Spring AI ChatClient 实现，自动支持 ReAct 循环（工具调用）。
 * 职责单一：管理对话记忆 + 工具回调 + 模型调用，不关心工具来源。
 */
@Component
public class AgentCore implements InitializingBean , ApplicationContextAware {

    /** 按 sessionId 隔离的对话记忆，支持多客户端并发 */
    private final Map<String, ChatMemory> sessionMemories = new ConcurrentHashMap<>();
    private String systemPromptText;
    private final List<ToolCallback> toolCallbacks = new ArrayList<>();
    private ApplicationContext applicationContext;

    @Resource
    private ChatClient chatClient;

    @Resource
    private IntentRecognizer intentRecognizer;

    @Resource
    private RagService ragService;

    @Resource
    private SubAgentManager subAgentManager;

    /** 模型推理参数 */
    private Double temperature = 0.7;
    private Integer maxTokens = 2048;
    private Double topP = 1.0;

    @Override
    public void afterPropertiesSet() throws Exception {
        this.systemPromptText = "你是一个智能助手，具备知识库检索（RAG）、工具调用（Function Calling）、"
                + "技能执行（Skill）、MCP 协议连接和子代理（SubAgent）等能力。\n"
                + "当遇到需要独立上下文记忆的复杂子任务时，你可以创建 SubAgent 来处理。\n"
                + "请根据用户的问题，合理选择使用工具或直接回答。\n"
                + "回答时请简洁准确，必要时引用工具返回的结果。";

        // 初始化 SubAgentManager，共享 ChatClient
        subAgentManager.setChatClient(chatClient);

        // 自动发现所有 InnerTool Bean，统一加载 ToolCallback
        Collection<InnerTool> innerTools = applicationContext.getBeansOfType(InnerTool.class).values();
        List<ToolCallback> allCallbacks = new ArrayList<>();
        for (InnerTool tool : innerTools) {
            try {
                allCallbacks.addAll(tool.loadToolCallbacks());
            } catch (Exception exception) {
                System.err.println("[Tool] " + tool.getClass().getSimpleName() + " 加载失败: " + exception.getMessage());
            }
        }

        registerToolCallbacks(allCallbacks);

        System.out.println("\n========================================");
        System.out.println("  AI Agent 已就绪（已加载 " + allCallbacks.size() + " 个工具）");
        System.out.println("  HTTP API: POST /api/chat");
        System.out.println("  MCP 管理: GET/POST /api/mcp/*");
        System.out.println("========================================\n");
    }

    /**
     * 运行时切换模型
     *
     * @param modelConfig 新的模型配置
     */
    public void switchModel(ModelConfig modelConfig) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(modelConfig.getBaseUrl())
                .apiKey(modelConfig.getApiKey());

        if (modelConfig.getCompletionsPath() != null) {
            apiBuilder.completionsPath(modelConfig.getCompletionsPath());
        }

        OpenAiApi openAiApi = apiBuilder.build();

        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(modelConfig.getChatModel())
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .build();
        ;
        this.chatClient = ChatClient.builder(chatModel).build();
        subAgentManager.setChatClient(this.chatClient);
        System.out.println("[模型切换] 已切换到: " + modelConfig);
    }

    /**
     * 批量注册工具回调（按名称去重，避免重复工具名导致 Spring AI 异常）
     */
    public void registerToolCallbacks(List<ToolCallback> callbacks) {
        if (callbacks == null) return;
        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            toolCallbacks.removeIf(existing -> existing.getToolDefinition().name().equals(name));
            toolCallbacks.add(callback);
        }
    }

    /**
     * 批量注册工具回调
     */
    public void registerToolCallbacks(ToolCallback... callbacks) {
        if (callbacks != null) {
            registerToolCallbacks(Arrays.asList(callbacks));
        }
    }

    /**
     * 移除指定的工具回调
     */
    public void removeToolCallbacks(List<ToolCallback> callbacks) {
        toolCallbacks.removeAll(callbacks);
    }

    /**
     * 获取或创建指定 sessionId 的对话记忆
     */
    private ChatMemory getOrCreateMemory(String sessionId) {
        return sessionMemories.computeIfAbsent(sessionId, id -> {
            ChatMemory memory = ChatMemory.forMainAgent(chatClient);
            memory.setSystemPrompt(systemPromptText);
            return memory;
        });
    }

    /**
     * 设置自定义 system prompt（全局生效，影响后续新建的会话）
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPromptText = systemPrompt;
        // 同步更新所有已有会话的 system prompt
        sessionMemories.values().forEach(memory -> memory.setSystemPrompt(systemPrompt));
    }

    /**
     * 设置模型推理参数
     */
    public void setModelParams(Double temperature, Integer maxTokens, Double topP) {
        if (temperature != null) this.temperature = temperature;
        if (maxTokens != null) this.maxTokens = maxTokens;
        if (topP != null) this.topP = topP;
    }

    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public Double getTopP() { return topP; }

    /**
     * 构建当前模型推理参数
     */
    private OpenAiChatOptions buildChatOptions() {
        return OpenAiChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxTokens)
                .topP(topP)
                .build();
    }

    /**
     * 与 Agent 对话
     * <p>
     * 流程：意图识别 → 按需注入 RAG 上下文 → 大模型调用（含工具调用）
     *
     * @param sessionId 会话 ID，用于隔离不同客户端的对话记忆
     * @param userInput 用户输入
     * @return 模型回复
     */
    public String chat(String sessionId, String userInput) {
        ChatMemory memory = getOrCreateMemory(sessionId);

        // 1. 意图识别
        Intent intent = intentRecognizer.recognize(userInput);

        // 2. 如果是 RAG 意图，先检索知识库并注入上下文
        if (intent == Intent.RAG && ragService.isKnowledgeLoaded()) {
            String ragContext = ragService.query(userInput);
            if (ragContext != null && !ragContext.isBlank()) {
                String enrichedInput = "以下是从知识库中检索到的相关参考资料，请结合这些资料回答用户的问题：\n\n"
                        + ragContext + "\n\n用户问题：" + userInput;
                memory.addMessage(new UserMessage(enrichedInput));
            } else {
                memory.addMessage(new UserMessage(userInput));
            }
        } else {
            memory.addMessage(new UserMessage(userInput));
        }

        // 3. 构建 Prompt（带模型参数）并调用大模型（getMessages 内部自动触发摘要压缩）
        List<Message> messages = memory.getMessages();
        Prompt prompt = new Prompt(messages, buildChatOptions());

        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt);

        if (!toolCallbacks.isEmpty()) {
            requestSpec.toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]));
        }

        String response = requestSpec.call().content();

        memory.addMessage(new AssistantMessage(response != null ? response : ""));

        return response != null ? response : "";
    }

    /**
     * 与 Agent 流式对话
     * <p>
     * 流程与 chat() 相同（意图识别 → RAG 注入 → 大模型调用），
     * 但以 Flux 流式返回每个 token，同时在流结束后将完整响应存入记忆。
     *
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     * @return 流式 token
     */
    public Flux<String> chatStream(String sessionId, String userInput) {
        ChatMemory memory = getOrCreateMemory(sessionId);

        // 1. 意图识别
        Intent intent = intentRecognizer.recognize(userInput);

        // 2. 如果是 RAG 意图，先检索知识库并注入上下文
        if (intent == Intent.RAG && ragService.isKnowledgeLoaded()) {
            String ragContext = ragService.query(userInput);
            if (ragContext != null && !ragContext.isBlank()) {
                String enrichedInput = "以下是从知识库中检索到的相关参考资料，请结合这些资料回答用户的问题：\n\n"
                        + ragContext + "\n\n用户问题：" + userInput;
                memory.addMessage(new UserMessage(enrichedInput));
            } else {
                memory.addMessage(new UserMessage(userInput));
            }
        } else {
            memory.addMessage(new UserMessage(userInput));
        }

        // 3. 构建 Prompt（带模型参数）并流式调用大模型（getMessages 内部自动触发摘要压缩）
        List<Message> messages = memory.getMessages();
        Prompt prompt = new Prompt(messages, buildChatOptions());

        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt);

        if (!toolCallbacks.isEmpty()) {
            requestSpec.toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]));
        }

        StringBuilder fullResponse = new StringBuilder();

        return requestSpec.stream().content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    String response = fullResponse.toString();
                    memory.addMessage(new AssistantMessage(response.isEmpty() ? "" : response));
                })
                .doOnError(error -> {
                    System.err.println("[Stream] 流式对话异常: " + error.getMessage());
                    memory.addMessage(new AssistantMessage(""));
                });
    }

    /**
     * 清空指定会话的对话历史
     *
     * @param sessionId 会话 ID
     */
    public void clearMemory(String sessionId) {
        ChatMemory memory = sessionMemories.remove(sessionId);
        if (memory != null) {
            memory.clear();
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
