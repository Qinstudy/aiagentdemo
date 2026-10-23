package com.zoujuexian.aiagentdemo.service.tool.aci;

import com.alibaba.fastjson.JSON;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ACI (Agent-Computer Interface) 工业级强类型工具响应协议
 * <p>
 * 彻底告别模糊易碎的非结构化纯文本短语，遵循 ACI 核心规范：
 * <ul>
 *   <li><b>status</b>: 宏观状态 (SUCCESS | RETRY_REQUIRED | ERROR)</li>
 *   <li><b>code</b>: 机器级语义状态码 (OK | NOT_FOUND | UNSUPPORTED_SUFFIX | MISSING_PARAM 等)</li>
 *   <li><b>data</b>: 成功时的强类型业务负载对象</li>
 *   <li><b>diagnostic</b>: 失败或重试时的自愈诊断信息（指定出错字段、原值、原因及建议重试入参）</li>
 * </ul>
 */
public class AciResponse<T> {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_RETRY_REQUIRED = "RETRY_REQUIRED";
    public static final String STATUS_ERROR = "ERROR";

    private String status;
    private String code;
    private T data;
    private AciDiagnostic diagnostic;

    public AciResponse() {}

    public AciResponse(String status, String code, T data, AciDiagnostic diagnostic) {
        this.status = status;
        this.code = code;
        this.data = data;
        this.diagnostic = diagnostic;
    }

    public static <T> AciResponse<T> success(T data) {
        return new AciResponse<>(STATUS_SUCCESS, "OK", data, null);
    }

    public static <T> AciResponse<T> error(String code, String message, String field, Object receivedValue) {
        return new AciResponse<>(STATUS_ERROR, code, null, new AciDiagnostic(
                field,
                receivedValue,
                message,
                "ASK_USER",
                null
        ));
    }

    public static <T> AciResponse<T> retry(String code, String message, String field, Object receivedValue, Map<String, Object> suggestedParams) {
        return new AciResponse<>(STATUS_RETRY_REQUIRED, code, null, new AciDiagnostic(
                field,
                receivedValue,
                message,
                "RETRY",
                suggestedParams
        ));
    }

    public String toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("code", code);
        if (data != null) {
            map.put("data", data);
        }
        if (diagnostic != null) {
            Map<String, Object> diagMap = new LinkedHashMap<>();
            diagMap.put("field", diagnostic.getField());
            diagMap.put("receivedValue", diagnostic.getReceivedValue());
            diagMap.put("message", diagnostic.getMessage());
            diagMap.put("suggestedAction", diagnostic.getSuggestedAction());
            if (diagnostic.getSuggestedParams() != null) {
                diagMap.put("suggestedParams", diagnostic.getSuggestedParams());
            }
            map.put("diagnostic", diagMap);
        }
        return JSON.toJSONString(map);
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public AciDiagnostic getDiagnostic() { return diagnostic; }
    public void setDiagnostic(AciDiagnostic diagnostic) { this.diagnostic = diagnostic; }

    public static class AciDiagnostic {
        private String field;
        private Object receivedValue;
        private String message;
        private String suggestedAction;
        private Map<String, Object> suggestedParams;

        public AciDiagnostic() {}

        public AciDiagnostic(String field, Object receivedValue, String message, String suggestedAction, Map<String, Object> suggestedParams) {
            this.field = field;
            this.receivedValue = receivedValue;
            this.message = message;
            this.suggestedAction = suggestedAction;
            this.suggestedParams = suggestedParams;
        }

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }

        public Object getReceivedValue() { return receivedValue; }
        public void setReceivedValue(Object receivedValue) { this.receivedValue = receivedValue; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getSuggestedAction() { return suggestedAction; }
        public void setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; }

        public Map<String, Object> getSuggestedParams() { return suggestedParams; }
        public void setSuggestedParams(Map<String, Object> suggestedParams) { this.suggestedParams = suggestedParams; }
    }
}
