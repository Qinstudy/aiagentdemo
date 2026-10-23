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
 * 股票价格查询工具 (符合 ACI 工业级强类型规范)
 * <p>
 * 获取指定股票的当前价格和涨跌幅，所有返回均封装为 AciResponse 强类型结构化 JSON。
 */
@Component
public class GetStockPriceTool implements InnerTool {

    @Override
    public List<ToolCallback> loadToolCallbacks() {
        Map<String, Map<String, String>> properties = Map.of(
                "symbol", Map.of("type", "string", "description", "股票代码，例如：AAPL、GOOGL、BABA")
        );

        return Collections.singletonList(ToolCallbackBuilder.build(
                "get_stock_price",
                "获取指定股票的当前价格和涨跌幅，返回值遵循 ACI 强类型结构化 JSON 规范",
                properties,
                List.of("symbol"),
                this::execute
        ));
    }

    public String execute(String argumentsJson) {
        JSONObject args = JSON.parseObject(argumentsJson);
        String symbol = args != null ? args.getString("symbol") : null;
        if (symbol == null || symbol.isBlank()) {
            return AciResponse.error(
                    "MISSING_PARAM",
                    "未提供股票代码。请向用户询问要查询的股票代码。",
                    "symbol",
                    null
            ).toJson();
        }
        symbol = symbol.toUpperCase().trim();

        /*// AciResponse.retry(...) 的地方，临时换成 Java 传统的经典抛异常：
        // 传统写法：发现不合规，直接抛运行时异常
        if (symbol.endsWith("-HK")) {
            throw new RuntimeException("【系统错误】美股接口不支持 -HK 后缀，查询终止！");
        }*/

        if ("AAPL".equals(symbol)) {
            return AciResponse.success(Map.of(
                    "symbol", "AAPL",
                    "name", "Apple Inc.",
                    "price", 150.25,
                    "change", "+2.5%",
                    "currency", "USD"
            )).toJson();
        } else if ("GOOGL".equals(symbol)) {
            return AciResponse.success(Map.of(
                    "symbol", "GOOGL",
                    "name", "Alphabet Inc.",
                    "price", 175.50,
                    "change", "+1.2%",
                    "currency", "USD"
            )).toJson();
        } else if ("BABA".equals(symbol)) {
            return AciResponse.success(Map.of(
                    "symbol", "BABA",
                    "name", "Alibaba Group Holding Ltd.",
                    "price", 88.50,
                    "change", "+3.1%",
                    "currency", "USD"
            )).toJson();
        } else if ("BABA-HK".equals(symbol)) {
            // ACI 核心自愈线索：返回 RETRY_REQUIRED + 明确的 suggestedParams 机器可读参数
            return AciResponse.retry(
                    "UNSUPPORTED_SUFFIX",
                    "美股交易系统不支持 '-HK' 后缀。阿里巴巴在美股系统的标准交易代码为 'BABA'。",
                    "symbol",
                    "BABA-HK",
                    Map.of("symbol", "BABA")
            ).toJson();
        } else {
            return AciResponse.error(
                    "SYMBOL_NOT_FOUND",
                    "未在交易所找到股票代码 '" + symbol + "' 的数据。目前仅支持查询 AAPL、GOOGL、BABA 等标的，请向用户核实股票代码是否正确。",
                    "symbol",
                    symbol
            ).toJson();
        }
    }
}
