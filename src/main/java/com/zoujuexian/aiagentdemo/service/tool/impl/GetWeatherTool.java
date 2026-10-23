package com.zoujuexian.aiagentdemo.service.tool.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zoujuexian.aiagentdemo.service.tool.ToolCallbackBuilder;
import com.zoujuexian.aiagentdemo.service.tool.InnerTool;
import com.zoujuexian.aiagentdemo.service.tool.aci.AciResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 天气查询工具 (符合 ACI 工业级强类型规范)
 * <p>
 * 获取指定城市某一天的天气信息，返回值遵循 AciResponse 强类型结构化 JSON 规范。
 */
@Component
public class GetWeatherTool implements InnerTool {

    @Override
    public List<ToolCallback> loadToolCallbacks() {
        Map<String, Map<String, String>> properties = Map.of(
                "location", Map.of("type", "string", "description", "城市名称，例如：北京、上海"),
                "date", Map.of("type", "string", "description", "日期，格式 yyyy-MM-dd，例如：2026-04-04")
        );

        return Collections.singletonList(ToolCallbackBuilder.build(
                "get_weather",
                "获取指定城市某一天的天气信息。注意：必须由用户明确提供具体日期；若用户未提供具体日期，切勿随意编造日期调用工具，必须直接反问用户要查询哪一天的天气。",
                properties,
                List.of("location", "date"),
                this::execute
        ));
    }

    public String execute(String argumentsJson) {
        JSONObject args = JSON.parseObject(argumentsJson);
        String location = args != null ? args.getString("location") : null;
        String date = args != null ? args.getString("date") : null;

        if (location == null || location.isBlank()) {
            return AciResponse.error(
                    "MISSING_PARAM",
                    "未提供目标城市名称（location）。请先向用户询问要查询哪个城市。",
                    "location",
                    null
            ).toJson();
        }
        if (date == null || date.isBlank()) {
            return AciResponse.error(
                    "MISSING_PARAM",
                    "缺少必要参数 'date'（日期）。必须提供具体日期（如 2026-04-05 或今天/明天）才能查询，请向用户反问确认想查询哪一天的天气。",
                    "date",
                    null
            ).toJson();
        }

        return AciResponse.success(Map.of(
                "location", location,
                "date", date,
                "condition", "晴天",
                "temperature", "22°C",
                "wind", "微风"
        )).toJson();
    }
}
