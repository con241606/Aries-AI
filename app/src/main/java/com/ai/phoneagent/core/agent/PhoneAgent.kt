package com.ai.phoneagent.core.agent

import android.content.Context
import android.util.Log
import com.ai.phoneagent.net.AutoGlmClient
import com.ai.phoneagent.net.ChatRequestMessage
import com.ai.phoneagent.data.model.ImageResultData
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Aries AI - AI驱动的手机自动化Agent
 * 参考成熟实现进行适配
 */
class PhoneAgent(
    private val context: Context,
    private val config: AgentConfig = AgentConfig(),
    private val actionHandler: ActionHandler
) {
    companion object {
        private const val TAG = "PhoneAgent"

        fun buildSystemPrompt(): String {
            val formattedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))
            return (
                    """
今天的日期是: $formattedDate
你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。
你必须严格按照要求输出以下格式：
thinking: {你的思考过程}
action: do(...)/finish(...)

其中：
- thinking: 对你为什么选择这个操作的简短推理说明。
- action: 本次执行的具体操作指令，必须严格遵循下方定义的指令格式。

操作指令及其作用如下：
- do(action="Launch", app="xxx")
    Launch是启动目标app的操作，这比通过主屏幕导航更快。
- do(action="Tap", element=[x,y])
    Tap是点击操作，点击屏幕上的特定点（坐标从左上角(0,0)到右下角(999,999)）。
- do(action="Tap", element=[x,y], message="重要操作")
    与Tap一致，用于财产、支付、隐私等敏感按钮。
- do(action="Type", text="xxx") / do(action="Type_Name", text="xxx")
    Type是输入操作，在当前聚焦的输入框中输入文本（输入前无需手动清空）。
- do(action="Interact")
    当存在多个满足条件的选项时，请求进一步指引。
- do(action="Swipe", start=[x1,y1], end=[x2,y2])
    Swipe是滑动操作，用于滚动、切页、下拉等。
- do(action="Note", message="True")
    记录当前页面内容以便后续总结。
- do(action="Call_API", instruction="xxx")
    总结或评论当前页面或已记录的内容。
- do(action="Long Press", element=[x,y]) / do(action="Double Tap", element=[x,y])
    长按或双击指定坐标。
- do(action="Take_over", message="xxx")
    需要用户协助登录/验证时的接管操作。
- do(action="Back") / do(action="Home") / do(action="Wait", duration="x seconds")
    分别对应返回、回到桌面、等待页面加载。
- finish(message="xxx")
    结束任务并返回终止信息。

必须遵循的规则：
1. 在执行任何操作前，先检查当前app是否是目标app，如果不是，先执行 Launch。
2. 如果进入到了无关页面，先执行 Back；如无变化，尝试左上角返回或右上角关闭。
3. 如果页面未加载出内容，最多连续 Wait 三次，否则执行 Back 重新进入。
4. 如果页面显示网络问题，需要重新加载，请点击重新加载。
5. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试 Swipe 滑动查找。
6. 遇到价格区间、时间区间等筛选条件，如果没有完全符合的，可以放宽要求。
7. 在做小红书总结类任务时一定要筛选图文笔记。
8. 购物车全选后再点击全选可以把状态设为全不选；购物车里已有商品时，先全选再取消后再操作目标商品。
9. 在做外卖任务时，如果购物车已有其他商品，先清空再购买用户指定的外卖。
10. 点多个外卖时尽量在同一店铺下单，若未找到需说明未找到的商品。
11. 严格遵循用户意图执行任务，可多次搜索或滑动查找（如搜索关键词拆分或调整）。
12. 选择日期时，如果滑动方向与目标相反，请向反方向滑动。
13. 有多个可选择的项目栏时，逐个查找，不要在同一栏多次循环。
14. 执行下一步前检查上一步是否生效，若点击无效可等待或微调位置后重试，再不生效则跳过并说明。
15. 滑动不生效时调整起始点或距离；若可能已到边界则反向滑动，仍无结果则说明后继续。
16. 做游戏任务时若在战斗页面有自动战斗须开启，历史状态相似需检查是否开启自动战斗。
17. 若搜索结果不合适，可能页面不对，返回上一级重新搜索；三次仍无结果则 finish(message="原因").
18. 结束任务前检查是否完整准确完成，若有错选/漏选/多选需回退纠正。
"""
                            .trimIndent()
            )
        }
    }

    private var _stepCount = 0

    val stepCount: Int
        get() = _stepCount
    private val contextHistory = mutableListOf<Pair<String, String>>()

    /**
     * 构建系统提示词，包含18条执行规则
     */
    private fun buildSystemPrompt(): String {
        val today = LocalDate.now()
        val weekNames =
                listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val formattedDate =
                today.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")) +
                        " " + weekNames[today.dayOfWeek.ordinal]

        return buildString {
            appendLine("今天的日期是: $formattedDate")
            appendLine("你是Phone Agent，一个智能手机自动化执行系统。")
            appendLine(
                    "你必须严格按以下格式输出，否则应用无法正确解析动作："
            )
            appendLine()
            appendLine("【思考开始】")
            appendLine("{think}")
            appendLine("【思考结束】")
            appendLine()
            appendLine("【回答开始】")
            appendLine("{action}")
            appendLine("【回答结束】")
            appendLine()
            appendLine("其中 {think} 是推理说明，{action} 是具体指令（do(...) 或 finish(...)）")
            appendLine()
            appendLine("18条必须遵循的执行规则：")
            appendLine("1. 在执行任何操作前，先检查当前app是否是目标app，如果不是，先执行 Launch")
            appendLine(
                    "2. 如果进入到了无关页面，先执行 Back。如果执行Back后页面没有变化，请点击页面左上角的返回键或右上角的X号"
            )
            appendLine(
                    "3. 如果页面未加载出内容，最多连续 Wait 三次，否则执行 Back重新进入"
            )
            appendLine("4. 如果页面显示网络问题，需要重新加载，请点击重新加载")
            appendLine(
                    "5. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试 Swipe 滑动查找"
            )
            appendLine("6. 遇到价格区间、时间区间等筛选条件，如果没有完全符合的，可以放宽要求")
            appendLine("7. 在做小红书总结类任务时一定要筛选图文笔记")
            appendLine(
                    "8. 购物车全选后再点击全选可以把状态设为全不选，在做购物车任务时需要点击全选后再取消全选"
            )
            appendLine("9. 在做外卖任务时，如果店铺购物车里已经有其他商品需要先把购物车清空")
            appendLine(
                    "10. 在做点外卖任务时，如果用户需要点多个外卖，请尽量在同一店铺进行购买"
            )
            appendLine(
                    "11. 请严格遵循用户意图执行任务，用户的特殊要求可以执行多次搜索和滑动查找"
            )
            appendLine(
                    "12. 在选择日期时，如果原滑动方向与预期日期越来越远，请向反方向滑动查找"
            )
            appendLine(
                    "13. 执行任务过程中如果有多个可选择的项目栏，请逐个查找每个项目栏，直到完成任务"
            )
            appendLine(
                    "14. 在执行下一步操作前请一定要检查上一步的操作是否生效，如果点击没生效可能因为app反应较慢，请先等待一下"
            )
            appendLine(
                    "15. 在执行任务中如果遇到滑动不生效的情况，请调整一下起始点位置，增大滑动距离重试"
            )
            appendLine(
                    "16. 在做游戏任务时如果在战斗页面有自动战斗一定要开启自动战斗"
            )
            appendLine(
                    "17. 如果没有合适的搜索结果，可能是因为搜索页面不对，请返回到搜索页面的上一级尝试重新搜索"
            )
            appendLine(
                    "18. 在结束任务前请一定要仔细检查任务是否完整准确的完成，如果出现错选、漏选、多选的情况，请返回之前的步骤进行纠正"
            )
        }
    }

    /**
     * 运行Agent执行任务
     * @param task 任务描述
     * @param apiKey AutoGLM API Key
     * @param model 模型名称
     * @param systemPrompt 系统提示词
     * @param onStep 每步执行后的回调
     * @param isPausedFlow 暂停状态Flow
     * @return 最终消息
     */
    suspend fun run(
        task: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        onStep: (suspend (StepResult) -> Unit)? = null,
        isPausedFlow: StateFlow<Boolean>? = null
    ): String {
        Log.d(TAG, "Starting agent task: $task")
        _stepCount = 0
        contextHistory.clear()

        try {
            // 构建初始消息
            val messages = mutableListOf<ChatRequestMessage>()
            messages.add(ChatRequestMessage(role = "system", content = buildSystemPrompt()))
            messages.add(ChatRequestMessage(role = "user", content = systemPrompt))
            messages.add(ChatRequestMessage(role = "user", content = "任务: $task"))

            // 执行步骤循环（无硬限制，由任务自动结束）
            var step = 1
            while (step <= config.maxSteps) {
                _stepCount = step

                // 检查暂停状态
                awaitIfPaused(isPausedFlow)

                // 执行单步
                val stepResult = executeStep(
                    step = step,
                    task = task,
                    apiKey = apiKey,
                    model = model,
                    messages = messages
                )

                // 回调
                onStep?.invoke(stepResult)

                // 检查是否完成
                if (stepResult.finished) {
                    Log.d(TAG, "Task finished at step $step")
                    return stepResult.message ?: "任务完成"
                }

                // 检查是否失败
                if (!stepResult.success) {
                    Log.e(TAG, "Step $step failed: ${stepResult.message}")
                    return stepResult.message ?: "执行失败"
                }

                // 添加延迟
                delay(config.stepDelayMs)
                step++
            }

            return "达到最大步数限制(${config.maxSteps}步)，任务可能未完成。建议提高maxSteps或检查Agent逻辑"

        } catch (e: CancellationException) {
            Log.d(TAG, "Agent cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Agent error", e)
            return "执行出错: ${e.message}"
        }
    }

    /**
     * 执行单步
     */
    private suspend fun executeStep(
        step: Int,
        task: String,
        apiKey: String,
        model: String,
        messages: MutableList<ChatRequestMessage>
    ): StepResult {
        try {
            Log.d(TAG, "Step $step: Taking screenshot and getting UI")

            // 1. 获取当前屏幕截图
            val screenshotResult = actionHandler.takeScreenshot()
            val screenshot = if (screenshotResult.success && screenshotResult.result is ImageResultData) {
                screenshotResult.result as ImageResultData
            } else {
                Log.w(TAG, "Screenshot failed, continuing without it")
                null
            }

            // 2. 获取UI层次结构
            val uiTree = actionHandler.getUIHierarchy(config.maxUiTreeChars)

            // 3. 构建当前观察消息
            val observationContent = buildObservationMessage(screenshot, uiTree, step)
            messages.add(ChatRequestMessage(role = "user", content = observationContent))

            // 4. 调用模型
            Log.d(TAG, "Step $step: Calling AI model")
            val response = requestModelWithRetry(
                apiKey = apiKey,
                model = model,
                messages = messages,
                step = step
            )

            if (response.isFailure) {
                val error = response.exceptionOrNull()
                return StepResult(
                    success = false,
                    finished = false,
                    action = null,
                    thinking = null,
                    message = "模型调用失败: ${error?.message}"
                )
            }

            val responseText = response.getOrNull() ?: ""
            Log.d(TAG, "Step $step: Got AI response: ${responseText.take(200)}")

            // 应用内容过滤 - 去除过度的敏感检查
            val filteredResponse = ContentFilter.sanitizeModelOutput(responseText)
            
            // 5. 解析动作
            val action = parseActionWithRepair(
                apiKey = apiKey,
                model = model,
                messages = messages,
                step = step,
                responseText = filteredResponse
            )

            if (action.metadata != "do" && action.metadata != "finish") {
                return StepResult(
                    success = false,
                    finished = false,
                    action = null,
                    thinking = null,
                    message = "无法解析AI响应为有效动作"
                )
            }

            // 6. 添加AI响应到历史
            messages.add(ChatRequestMessage(role = "assistant", content = responseText))

            // 7. 检查是否完成
            if (action.metadata == "finish") {
                val finishMessage = action.fields["message"] ?: "任务完成"
                return StepResult(
                    success = true,
                    finished = true,
                    action = action,
                    thinking = extractThinking(responseText),
                    message = finishMessage
                )
            }

            // 8. 执行动作
            Log.d(TAG, "Step $step: Executing action: ${action.actionName}")
            val actionResult = actionHandler.executeAction(action)

            // 9. 记录执行结果到历史
            val resultMessage = if (actionResult.success) {
                "执行成功: ${action.actionName}"
            } else {
                "执行失败: ${actionResult.error}"
            }
            contextHistory.add(action.raw to resultMessage)

            // 10. 管理上下文长度
            manageContextLength(messages)

            return StepResult(
                success = actionResult.success,
                finished = false,
                action = action,
                thinking = extractThinking(responseText),
                message = resultMessage
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error in step $step", e)
            return StepResult(
                success = false,
                finished = false,
                action = null,
                thinking = null,
                message = "步骤执行出错: ${e.message}"
            )
        }
    }

    /**
     * 构建观察消息
     */
    private fun buildObservationMessage(
        screenshot: ImageResultData?,
        uiTree: String,
        step: Int
    ): String {
        val sb = StringBuilder()
        sb.appendLine("【第 $step 步观察】")
        
        if (screenshot != null) {
            sb.appendLine("屏幕截图 (${screenshot.width}x${screenshot.height}):")
            sb.appendLine("data:image/png;base64,${screenshot.base64Data}")
        } else {
            sb.appendLine("(未能获取截图)")
        }
        
        sb.appendLine()
        sb.appendLine("UI层次结构:")
        sb.appendLine(uiTree)
        
        return sb.toString()
    }

    /**
     * 提取思考过程
     */
    private fun extractThinking(response: String): String? {
        // 尝试提取thinking内容
        val thinkingMatch = Regex("thinking:\\s*(.+?)(?=\\naction:|$)", RegexOption.DOT_MATCHES_ALL)
            .find(response)
        return thinkingMatch?.groupValues?.getOrNull(1)?.trim()
    }

    /**
     * 解析动作并修复
     */
    private suspend fun parseActionWithRepair(
        apiKey: String,
        model: String,
        messages: MutableList<ChatRequestMessage>,
        step: Int,
        responseText: String
    ): ParsedAgentAction {
        var action = parseAgentAction(responseText)
        
        if (action.metadata == "do" || action.metadata == "finish") {
            return action
        }

        // 尝试修复
        repeat(config.maxParseRepairs) { attempt ->
            Log.d(TAG, "Step $step: Attempting parse repair ${attempt + 1}/${config.maxParseRepairs}")
            
            val repairPrompt = """
                你的输出格式不正确。请严格按照以下格式重新输出：
                
                thinking: [你的思考过程]
                action: do(动作名称, 参数1=值1, 参数2=值2)
                
                或者如果任务完成：
                action: finish(message=完成信息)
            """.trimIndent()
            
            messages.add(ChatRequestMessage(role = "user", content = repairPrompt))
            
            val repairResult = requestModelWithRetry(apiKey, model, messages, step)
            if (repairResult.isSuccess) {
                val repaired = repairResult.getOrNull() ?: ""
                messages.add(ChatRequestMessage(role = "assistant", content = repaired))
                
                action = parseAgentAction(repaired)
                if (action.metadata == "do" || action.metadata == "finish") {
                    return action
                }
            }
        }
        
        return action
    }

    /**
     * 解析Agent动作
     */
    private fun parseAgentAction(text: String): ParsedAgentAction {
        val trimmed = text.trim()
        
        // 提取action行
        val actionLine = Regex("action:\\s*(.+?)(?=\\n|$)", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)?.trim() ?: trimmed
        
        // 解析 do(...) 或 finish(...)
        val doMatch = Regex("""do\s*\(\s*([^,\)]+)\s*(?:,\s*(.+?))?\s*\)""", RegexOption.DOT_MATCHES_ALL)
            .find(actionLine)
        
        if (doMatch != null) {
            val actionName = doMatch.groupValues[1].trim()
            val paramsStr = doMatch.groupValues.getOrNull(2)?.trim() ?: ""
            val fields = parseParameters(paramsStr)
            
            return ParsedAgentAction(
                metadata = "do",
                actionName = actionName,
                fields = fields,
                raw = text
            )
        }
        
        val finishMatch = Regex("""finish\s*\(\s*(.+?)\s*\)""", RegexOption.DOT_MATCHES_ALL)
            .find(actionLine)
        
        if (finishMatch != null) {
            val paramsStr = finishMatch.groupValues[1].trim()
            val fields = parseParameters(paramsStr)
            
            return ParsedAgentAction(
                metadata = "finish",
                actionName = null,
                fields = fields,
                raw = text
            )
        }
        
        // 无法解析
        return ParsedAgentAction(
            metadata = "error",
            actionName = null,
            fields = emptyMap(),
            raw = text
        )
    }

    /**
     * 解析参数
     */
    private fun parseParameters(paramsStr: String): Map<String, String> {
        if (paramsStr.isEmpty()) return emptyMap()
        
        val params = mutableMapOf<String, String>()
        val parts = paramsStr.split(",")
        
        for (part in parts) {
            val trimmed = part.trim()
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex > 0) {
                val key = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                params[key] = value
            }
        }
        
        return params
    }

    /**
     * 带重试的模型请求
     */
    private suspend fun requestModelWithRetry(
        apiKey: String,
        model: String,
        messages: List<ChatRequestMessage>,
        step: Int
    ): Result<String> {
        val maxAttempts = (config.maxModelRetries + 1).coerceAtLeast(1)
        var lastErr: Throwable? = null

        for (attempt in 0 until maxAttempts) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()

            val result = withContext(Dispatchers.IO) {
                AutoGlmClient.sendChatResult(
                    apiKey = apiKey,
                    messages = messages,
                    model = model,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    topP = config.topP,
                    frequencyPenalty = config.frequencyPenalty
                )
            }

            if (result.isSuccess) return result

            val err = result.exceptionOrNull()
            if (err is CancellationException) throw err
            lastErr = err

            val retryable = isRetryableModelError(err)
            if (!retryable || attempt >= maxAttempts - 1) break

            val waitMs = computeModelRetryDelayMs(attempt)
            Log.d(TAG, "Step $step: Model call failed (${attempt + 1}/$maxAttempts), retrying in ${waitMs}ms...")
            delay(waitMs)
        }

        return Result.failure(lastErr ?: IOException("Unknown model error"))
    }

    /**
     * 判断是否可重试的错误
     */
    private fun isRetryableModelError(t: Throwable?): Boolean {
        if (t == null) return false
        if (t is CancellationException) return false
        if (t is IOException) return true
        
        // 检查HTTP错误码
        val message = t.message ?: ""
        if (message.contains("429") || message.contains("rate limit", ignoreCase = true)) return true
        if (message.contains("500") || message.contains("503")) return true
        
        return false
    }

    /**
     * 计算重试延迟
     */
    private fun computeModelRetryDelayMs(attempt: Int): Long {
        val base = config.modelRetryBaseDelayMs.coerceAtLeast(0L)
        val mult = 1L shl attempt.coerceIn(0, 6)
        return (base * mult).coerceAtMost(6000L)
    }

    /**
     * 管理上下文长度
     */
    private fun manageContextLength(messages: MutableList<ChatRequestMessage>) {
        // 简单实现：保留系统提示+最近N轮对话
        if (messages.size > config.maxHistoryTurns * 2 + 2) {
            // 保留第一条（system）和第二条（初始任务）
            val toKeep = messages.take(2).toMutableList()
            // 保留最近的N轮
            val recent = messages.takeLast(config.maxHistoryTurns * 2)
            toKeep.addAll(recent)
            
            messages.clear()
            messages.addAll(toKeep)
        }
    }

    /**
     * 等待暂停状态恢复
     */
    private suspend fun awaitIfPaused(isPausedFlow: StateFlow<Boolean>?) {
        if (isPausedFlow == null) return
        
        while (isPausedFlow.value) {
            delay(200)
        }
    }
}
