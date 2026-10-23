package com.zoujuexian.aiagentdemo.service.tool;

import com.alibaba.fastjson.JSON;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ToolCallback 构建辅助类
 * <p>
 * 提供简洁的 API 将工具名称、描述、参数定义和执行逻辑组装为 Spring AI ToolCallback。
 */
public final class ToolCallbackBuilder {

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> TOOL_CALL_COUNTS = new java.util.concurrent.ConcurrentHashMap<>();

    public static int getCallCount(String toolName) {
        return TOOL_CALL_COUNTS.computeIfAbsent(toolName, k -> new java.util.concurrent.atomic.AtomicInteger(0)).get();
    }

    public static void resetCallCounts() {
        TOOL_CALL_COUNTS.clear();
    }

    private ToolCallbackBuilder() {
    }

    /**
     * 构建一个 ToolCallback
     *
     * @param name           工具名称
     * @param description    工具描述
     * @param properties     参数属性定义（JSON Schema properties）
     * @param requiredParams 必填参数列表
     * @param executor       执行函数，接收原始 JSON 字符串，返回结果字符串
     * @return Spring AI ToolCallback 实例
     */
    public static ToolCallback build(String name,
                                     String description,
                                     Map<String, Map<String, String>> properties,
                                     List<String> requiredParams,
                                     Function<String, String> executor) {

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        schema.put("required", requiredParams);
        String inputSchema = JSON.toJSONString(schema);

        org.springframework.ai.tool.definition.ToolDefinition toolDefinition = DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return toolDefinition;
            }

            @Override
            public String call(String toolInput) {
                TOOL_CALL_COUNTS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();

                System.out.println("\n╔══════════════════════════════════════════");
                System.out.println("║ 🔧 [Tool Call] " + name);
                System.out.println("║ 📥 入参: " + truncate(toolInput, 200));
                System.out.println("╚══════════════════════════════════════════");

                long startTime = System.currentTimeMillis();
                try {// ToolCallbackBuilder，调用工具类下面的执行方法，例如：GetStockPriceTool.execute
                    String result = executor.apply(toolInput);
                    long elapsed = System.currentTimeMillis() - startTime;

                    System.out.println("\n╔══════════════════════════════════════════");
                    System.out.println("║ ✅ [Tool Result] " + name);
                    System.out.println("║ ⏱️ 耗时: " + elapsed + "ms");
                    System.out.println("║ 📤 结果: " + truncate(result, 300));
                    System.out.println("╚══════════════════════════════════════════\n");
                    return result;
                } catch (Exception exception) {
                    long elapsed = System.currentTimeMillis() - startTime;

                    System.err.println("\n╔══════════════════════════════════════════");
                    System.err.println("║ ❌ [Tool Error] " + name);
                    System.err.println("║ ⏱️ 耗时: " + elapsed + "ms");
                    System.err.println("║ 💥 异常: " + exception.getMessage());
                    System.err.println("╚══════════════════════════════════════════\n");
                    throw exception;
                }
            }
        };
    }

    /**
     * 截断字符串，超过 maxLength 时追加省略号，同时将换行替换为空格以保持日志单行可读
     */
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
}
