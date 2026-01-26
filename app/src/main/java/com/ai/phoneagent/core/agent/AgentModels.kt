package com.ai.phoneagent.core.agent

/**
 * 解析的Agent动作
 * 核心数据结构，被 UiAutomationAgent、ActionParser、ActionExecutor 等模块使用
 */
data class ParsedAgentAction(
    val metadata: String,           // "do" 或 "finish"
    val actionName: String?,        // 动作名称（如 tap, swipe）
    val fields: Map<String, String>, // 动作参数
    val raw: String = ""            // 原始响应
)
