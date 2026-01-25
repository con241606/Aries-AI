/*
 * =====================================================================================
 * 文件：ActionHandler.kt
 * 状态：已废弃 (Deprecated)
 * 替代：com.ai.phoneagent.core.executor.ActionExecutor
 *       com.ai.phoneagent.core.parser.ActionParser
 *       com.ai.phoneagent.core.cache.ScreenshotManager
 * 删除时间：待定（保留以便回滚）
 * =====================================================================================
 *
 * 原功能：动作处理器，负责执行解析后的Agent动作
 *
 * 重构说明：
 * - 原 ActionHandler 职责过多（截图、UI树获取、动作执行）
 * - 重构为多个单一职责组件：
 *   * ActionExecutor: 动作执行
 *   * ActionParser: 动作解析
 *   * ScreenshotManager: 截图管理
 *
 * 主要变化：
 * 1. 分离关注点：每个类只做一件事
 * 2. 可配置的参数（如截图质量、UI树截断长度等）
 * 3. 更好的错误处理和日志
 * 4. 智能Tap+Type合并执行
 *
 * 迁移指南：
 * - 替换 takeScreenshot() 为 ScreenshotManager
 * - 替换 getUIHierarchy() 为直接调用 service
 * - 替换 executeAction() 为 ActionExecutor
 *
 * 保留此文件以便必要时回滚，建议在充分测试后删除
 *
package com.ai.phoneagent.core.agent

import android.content.Context
import android.util.Log
import com.ai.phoneagent.PhoneAgentAccessibilityService
import com.ai.phoneagent.core.tools.AIToolHandler
import com.ai.phoneagent.data.model.AITool
import com.ai.phoneagent.data.model.ImageResultData
import com.ai.phoneagent.data.model.ToolParameter
import com.ai.phoneagent.data.model.ToolResult
import kotlinx.coroutines.delay

/**
 * 动作处理器
 * 负责执行解析后的Agent动作
 */
class ActionHandler(
    private val context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    companion object {
        private const val TAG = "ActionHandler"
    }

    private val toolHandler = AIToolHandler.getInstance(context)

    /**
     * 执行Agent动作
     */
    suspend fun executeAction(action: ParsedAgentAction): ToolResult {
        Log.d(TAG, "Executing action: ${action.actionName}, metadata: ${action.metadata}")

        // 如果是finish动作，直接返回成功
        if (action.metadata == "finish") {
            val message = action.fields["message"] ?: "任务完成"
            return ToolResult(
                toolName = "finish",
                success = true,
                result = com.ai.phoneagent.data.model.StringResultData(message)
            )
        }

        // 获取动作名称
        val actionName = action.actionName ?: return ToolResult(
            toolName = "unknown",
            success = false,
            error = "动作名称为空"
        )

        // 构建工具调用
        val tool = AITool(
            name = actionName,
            parameters = action.fields.map { (key, value) ->
                ToolParameter(name = key, value = value)
            }
        )

        // 执行工具
        return try {
            val result = toolHandler.executeTool(tool)

            // 添加延迟，让UI有时间更新
            if (result.success) {
                delay(300)
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action: $actionName", e)
            ToolResult(
                toolName = actionName,
                success = false,
                error = "执行失败: ${e.message}"
            )
        }
    }

    /**
     * 获取当前截图
     */
    suspend fun takeScreenshot(): ToolResult {
        val service = PhoneAgentAccessibilityService.instance
        if (service == null) {
            return ToolResult(
                toolName = "screenshot",
                success = false,
                error = "无障碍服务未启用"
            )
        }

        val screenshot = service.tryCaptureScreenshotBase64()
        return if (screenshot != null) {
            ToolResult(
                toolName = "screenshot",
                success = true,
                result = ImageResultData(
                    width = screenshot.width,
                    height = screenshot.height,
                    base64Data = screenshot.base64Png
                )
            )
        } else {
            ToolResult(
                toolName = "screenshot",
                success = false,
                error = "截图失败"
            )
        }
    }

    /**
     * 获取UI层次结构
     */
    suspend fun getUIHierarchy(maxChars: Int = 3000): String {
        val service = PhoneAgentAccessibilityService.instance
        if (service == null) {
            return "(无障碍服务未启用)"
        }

        val maxNodes = 30
        val uiTree = service.getUiHierarchy(format = "xml", detail = "minimal", maxNodes = maxNodes)

        // 限制长度
        return if (uiTree.length > maxChars) {
            uiTree.substring(0, maxChars) + "\n... (已截断)"
        } else {
            uiTree
        }
    }
}
*/
