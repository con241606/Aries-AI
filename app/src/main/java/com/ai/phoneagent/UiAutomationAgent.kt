package com.ai.phoneagent

import android.accessibilityservice.AccessibilityService
import com.ai.phoneagent.core.agent.ParsedAgentAction
import com.ai.phoneagent.core.cache.ScreenshotManager
import com.ai.phoneagent.core.config.AgentConfiguration
import com.ai.phoneagent.core.executor.ActionExecutor
import com.ai.phoneagent.core.parser.ActionParser
import com.ai.phoneagent.core.templates.PromptTemplates
import com.ai.phoneagent.core.utils.ActionUtils
import com.ai.phoneagent.net.AutoGlmClient
import com.ai.phoneagent.net.ChatRequestMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UiAutomationAgent - 重构后的主Agent
 * 
 * 使用清晰的分层架构：
 * - 配置层：AgentConfiguration
 * - 解析层：ActionParser
 * - 执行层：ActionExecutor
 * - 缓存层：ScreenshotManager
 * - 模板层：PromptTemplates
 * 
 * 职责：
 * 1. 协调各组件完成Agent流程
 * 2. 管理对话历史和上下文
 * 3. 处理模型调用和重试
 * 4. 管理任务状态和进度
 */
class UiAutomationAgent(
    private val config: AgentConfiguration = AgentConfiguration.DEFAULT
) {
    // 组件实例化
    private val actionParser = ActionParser()
    private val actionExecutor = ActionExecutor(config)
    private var screenshotManager: ScreenshotManager? = null

    // Tap+Type 合并执行状态
    private var lastActionWasTap = false
    private var lastTapAction: ParsedAgentAction? = null

    interface Control {
        fun isPaused(): Boolean
        suspend fun confirm(message: String): Boolean
    }

    private object NoopControl : Control {
        override fun isPaused(): Boolean = false
        override suspend fun confirm(message: String): Boolean = false
    }

    private class TakeOverException(message: String) : RuntimeException(message)

    data class AgentResult(
            val success: Boolean,
            val message: String,
            val steps: Int,
    )
    
    /**
     * 运行Agent执行任务
     */
    suspend fun run(
            apiKey: String,
            model: String,
            task: String,
            service: PhoneAgentAccessibilityService,
            control: Control = NoopControl,
            onLog: (String) -> Unit,
    ): AgentResult {
        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // 初始化截图管理器
        screenshotManager = ScreenshotManager(config)
        
        // 智能应用启动
        val smartLaunched = trySmartAppLaunch(task, service, onLog)
        if (smartLaunched) {
            onLog("✓ 应用已快速启动，继续后续操作...")
        }

        // 构建初始消息
        val history = mutableListOf<ChatRequestMessage>()
        history += ChatRequestMessage(
            role = "system",
            content = PromptTemplates.buildSystemPrompt(screenW, screenH, config)
        )
        
        // 清理缓存
        screenshotManager?.clear()
        
        // 重置状态
        lastActionWasTap = false
        lastTapAction = null

        var step = 0
        
        while (step < config.maxSteps) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            awaitIfPaused(control)
            step++
            
            // 更新进度
            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0f,
                    maxSteps = config.maxSteps,
                    subtitle = "读取界面"
            )
            
            // 并行获取截图和UI树
            val (screenshot, rawUiDump) = coroutineScope {
                val screenshotDeferred = async { 
                    screenshotManager?.getOptimizedScreenshot(service) 
                }
                val uiDumpDeferred = async { service.dumpUiTreeWithRetry(maxNodes = config.uiTreeMaxNodes) }
                Pair(screenshotDeferred.await(), uiDumpDeferred.await())
            }
            
            // 更新进度
            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0.15f,
                    maxSteps = config.maxSteps,
                    subtitle = "解析界面"
            )
            
            // 截断UI树
            val uiDump = ActionUtils.truncateUiTree(rawUiDump, config.maxUiTreeChars)

            val currentApp = service.currentAppPackage()
            val screenInfo = "{\"current_app\":\"${currentApp.replace("\"", "")}\"}"

            // 记录截图信息
            if (screenshot != null) {
                onLog("[Step $step] 截图：${screenshot.width}x${screenshot.height}")
            } else {
                onLog("[Step $step] 截图：不可用（将使用纯文本/无障碍树模式）")
            }

            // 构建用户消息
            val userMsg = if (step == 1) {
                        "$task\n\n$screenInfo\n\nUI树：\n$uiDump"
                    } else {
                        "$screenInfo\n\nUI树：\n$uiDump"
                    }

            // 构建消息内容
            val userContent: Any = if (screenshot != null) {
                        listOf(
                                mapOf(
                                        "type" to "image_url",
                        "image_url" to mapOf(
                            "url" to "data:image/jpeg;base64,${screenshot.base64Png}"
                                                )
                                ),
                                mapOf("type" to "text", "text" to userMsg)
                        )
                    } else {
                        userMsg
                    }
            
            // 修剪历史
            trimHistory(history)
            
            // 添加用户消息
            history += ChatRequestMessage(role = "user", content = userContent)
            val observationUserIndex = history.lastIndex

            // 更新进度
            onLog("[Step $step] 请求模型…")
            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0.25f,
                    maxSteps = config.maxSteps,
                    subtitle = "请求模型"
            )
            
            AutomationOverlay.startThinking()
            
            // 调用模型
            val replyResult = requestModelWithRetry(
                apiKey = apiKey,
                model = model,
                messages = history,
                step = step,
                purpose = "请求模型",
                onLog = onLog,
            )

            val finalReply = replyResult.getOrNull()?.trim().orEmpty()
            AutomationOverlay.stopThinking()
            
            // 模型调用失败
            if (finalReply.isBlank()) {
                val err = replyResult.exceptionOrNull()
                val msg = err?.message?.trim().orEmpty().ifBlank { "模型无回复或请求失败" }
                return AgentResult(false, "模型请求失败：${msg.take(320)}", step)
            }

            // 更新进度
            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0.55f,
                    maxSteps = config.maxSteps,
                    subtitle = "解析模型输出"
            )

            // 解析思考和回答
            val (thinking, answer) = actionParser.parseWithThinking(finalReply)
            if (!thinking.isNullOrBlank()) {
                onLog("[Step $step] 思考：${thinking.take(config.logThinkingTruncateLength)}")
                if (step == 1) {
                    val estimatedSteps = actionParser.parseEstimatedSteps(thinking)
                    if (estimatedSteps > 0) {
                        AutomationOverlay.updateEstimatedSteps(estimatedSteps)
                        onLog("[Step $step] 预估总步骤数：$estimatedSteps")
                    }
                }
            }
            onLog("[Step $step] 输出：${answer.take(config.logAnswerTruncateLength)}")

            // 添加助手消息到历史
            history += ChatRequestMessage(role = "assistant", content = finalReply)

            // 解析动作
            val action = parseActionWithRepair(
                            apiKey = apiKey,
                            model = model,
                            history = history,
                            step = step,
                            answerText = answer,
                            onLog = onLog,
                    )
            
            // 检查是否完成
            if (action.metadata == "finish") {
                val msg = action.fields["message"].orEmpty().ifBlank { "已完成" }
                return AgentResult(true, msg, step)
            }
            
            if (action.metadata != "do") {
                return AgentResult(false, "无法解析动作：${action.raw.take(config.logStepTruncateLength)}", step)
            }

            // 更新进度
            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0.68f,
                    maxSteps = config.maxSteps,
                    subtitle = "准备执行"
            )

            // 执行动作
            var currentAction = action
            var execOk = false
            var repairAttempt = 0

            while (true) {
                val actionName = currentAction.actionName
                                ?.trim()
                                ?.trim('"', '\'', ' ')
                                ?.lowercase()
                                .orEmpty()
                
                val displayActionName = ActionUtils.getDisplayActionName(actionName, currentAction.fields)
                AutomationOverlay.updateProgress(
                        step = step,
                        phaseInStep = 0.78f,
                        maxSteps = config.maxSteps,
                        subtitle = "执行 $displayActionName"
                )

                // Take_over 处理
                if (actionName == "take_over" || actionName == "takeover") {
                    val msg = currentAction.fields["message"].orEmpty().ifBlank { "需要用户接管" }
                    return AgentResult(false, msg, step)
                }

                // Note/Call_api 处理
                if (actionName == "note" || actionName == "call_api" || actionName == "interact") {
                    return AgentResult(false, "需要用户交互/扩展能力：${currentAction.raw.take(180)}", step)
                }

                // Tap+Type 合并执行检测
                val isTypeAction = actionName == "type" || actionName == "input" || actionName == "text"
                val wasPreviousTap = lastActionWasTap

                execOk = try {
                            if (isTypeAction && wasPreviousTap) {
                        // 合并执行
                                val previousTapAction = lastTapAction
                                lastActionWasTap = false
                                lastTapAction = null

                                if (previousTapAction != null) {
                                    onLog("[合并执行] Tap + Type")
                                    executeTapAndTypeCombined(
                                        service = service,
                                        tapAction = previousTapAction,
                                        typeAction = currentAction,
                                        uiDump = uiDump,
                                        screenW = screenW,
                                        screenH = screenH,
                                        onLog = onLog
                                    )
                                } else {
                            actionExecutor.execute(currentAction, service, uiDump, screenW, screenH, onLog)
                                }
                            } else {
                                // 正常执行
                                if (actionName == "tap" || actionName == "click" || actionName == "press") {
                                    lastActionWasTap = true
                                    lastTapAction = currentAction
                                } else {
                                    lastActionWasTap = false
                                    lastTapAction = null
                                }
                        actionExecutor.execute(currentAction, service, uiDump, screenW, screenH, onLog)
                            }
                        } catch (e: TakeOverException) {
                            val msg = e.message.orEmpty().ifBlank { "需要用户接管" }
                            return AgentResult(false, msg, step)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                    onLog("[Step $step] 动作执行异常：${e.message.orEmpty().take(config.logStepTruncateLength)}")
                            false
                        }

                if (execOk) break

                // 动作执行失败，尝试修复
                if (repairAttempt >= config.maxActionRepairs) {
                    return AgentResult(false, "动作执行失败：${currentAction.raw.take(config.logStepTruncateLength)}", step)
                }

                repairAttempt++
                onLog("[Step $step] 动作执行失败，尝试让模型修复（$repairAttempt/${config.maxActionRepairs})…")

                AutomationOverlay.updateProgress(
                        step = step,
                        phaseInStep = 0.62f,
                        maxSteps = config.maxSteps,
                        subtitle = "动作失败，修复中"
                )

                // 构建修复消息
                val failMsg = PromptTemplates.buildActionRepairPrompt(currentAction.raw)
                history += ChatRequestMessage(role = "user", content = failMsg)

                val fixResult = requestModelWithRetry(
                                apiKey = apiKey,
                                model = model,
                                messages = history,
                                step = step,
                                purpose = "动作修复",
                                onLog = onLog,
                        )
                
                val fixFinal = fixResult.getOrNull()?.trim().orEmpty()
                if (fixFinal.isBlank()) {
                    val err = fixResult.exceptionOrNull()
                    val msg = err?.message?.trim().orEmpty().ifBlank { "模型无回复或请求失败" }
                    return AgentResult(false, "动作修复失败：${msg.take(320)}", step)
                }

                AutomationOverlay.updateProgress(
                        step = step,
                        phaseInStep = 0.72f,
                        maxSteps = config.maxSteps,
                        subtitle = "解析修复动作"
                )

                val (fixThinking, fixAnswer) = actionParser.parseWithThinking(fixFinal)
                if (!fixThinking.isNullOrBlank()) {
                    onLog("[Step $step] 修复思考：${fixThinking.take(config.logThinkingTruncateLength)}")
                }
                onLog("[Step $step] 修复输出：${fixAnswer.take(config.logAnswerTruncateLength)}")
                history += ChatRequestMessage(role = "assistant", content = fixFinal)

                currentAction = parseActionWithRepair(
                                apiKey = apiKey,
                                model = model,
                                history = history,
                                step = step,
                                answerText = fixAnswer,
                                onLog = onLog,
                        )

                if (currentAction.metadata == "finish") {
                    val msg = currentAction.fields["message"].orEmpty().ifBlank { "已完成" }
                    return AgentResult(true, msg, step)
                }
                if (currentAction.metadata != "do") {
                    return AgentResult(false, "无法解析动作：${currentAction.raw.take(config.logStepTruncateLength)}", step)
                }
            }

            // 计算延迟
            val extraDelayMs = config.getActionDelayMs(currentAction.actionName ?: "")

            // 更新进度
            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0.92f,
                    maxSteps = config.maxSteps,
                    subtitle = "等待界面稳定"
            )

            // 更新历史中的用户消息
            if (observationUserIndex in history.indices) {
                val obs = history[observationUserIndex]
                if (obs.content is List<*>) {
                    history[observationUserIndex] = ChatRequestMessage(role = "user", content = userMsg)
                }
            }

            // 延迟等待
            delay((config.stepDelayMs + extraDelayMs).coerceAtLeast(0L))
        }

        return AgentResult(false, "达到最大步数限制（${config.maxSteps}）", config.maxSteps)
    }

    /**
     * 检测用户任务中是否包含需要打开的应用，如果包含则自动启动
     */
    private suspend fun trySmartAppLaunch(
        task: String,
        service: PhoneAgentAccessibilityService,
        onLog: (String) -> Unit,
    ): Boolean {
        val launchPatterns = listOf(
            Regex("""(?:打开|启动|进入|帮我打开|用|去|切换到|跳转到|回到)\s*([^\s，。,\.！!？?；;]+?)(?:\s|，|。|,|\.|！|!|？|\?|；|;|$)"""),
            Regex("""(?:open|launch|start|switch\s+to|go\s+to)\s*(\S+)""", RegexOption.IGNORE_CASE),
        )
        
        var appMatch: AppPackageMapping.Match? = null
        
        for (pattern in launchPatterns) {
            val matchResult = pattern.find(task)
            if (matchResult != null) {
                val potentialApp = matchResult.groupValues.getOrNull(1)?.trim()
                if (!potentialApp.isNullOrBlank()) {
                    val resolved = AppPackageMapping.resolve(potentialApp)
                    if (resolved != null) {
                        appMatch = AppPackageMapping.Match(
                            appLabel = potentialApp,
                            packageName = resolved,
                            start = matchResult.range.first,
                            end = matchResult.range.last
                        )
                        break
                    }
                }
            }
        }
        
        if (appMatch == null) {
            appMatch = AppPackageMapping.bestMatchInText(task)
        }
        
        if (appMatch == null) {
            return false
        }
        
        val currentApp = service.currentAppPackage()
        if (currentApp == appMatch.packageName) {
            onLog("[⚡快速启动] ${appMatch.appLabel} 已在前台，跳过启动（无需连接模型）")
            return true
        }
        
        val pm = service.packageManager
        val intent = pm.getLaunchIntentForPackage(appMatch.packageName)
        if (intent == null) {
            onLog("[⚡快速启动] 未找到 ${appMatch.appLabel}(${appMatch.packageName}) 的启动入口")
            return false
        }
        
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
            android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        
        try {
            val beforeTime = service.lastWindowEventTime()
            LaunchProxyActivity.launch(service, intent)
            onLog("[⚡快速启动] 后台启动 ${appMatch.appLabel}（无需连接模型，节省时间）")
            // 等待更长时间确保应用加载完成
            service.awaitWindowEvent(beforeTime, timeoutMs = config.appLaunchWaitTimeoutMs)
            delay(config.appLaunchExtraDelayMs)
            
            // 清理截图缓存，确保获取最新的应用界面截图
            screenshotManager?.clear()
            
            // 验证应用是否真的启动了
            val newApp = service.currentAppPackage()
            if (newApp != appMatch.packageName) {
                onLog("[⚡快速启动] ${appMatch.appLabel} 启动验证失败（当前：$newApp），将在后续步骤中处理")
            } else {
                onLog("[⚡快速启动] ${appMatch.appLabel} 启动成功，继续后续操作...")
            }
            return true
        } catch (e: Exception) {
            onLog("[⚡快速启动] 启动失败: ${e.message}")
            return false
        }
    }
    
    /**
     * 合并执行 Tap+Type 操作
     */
    private suspend fun executeTapAndTypeCombined(
        service: PhoneAgentAccessibilityService,
        tapAction: ParsedAgentAction,
        typeAction: ParsedAgentAction,
        uiDump: String,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit,
    ): Boolean {
        val inputText = typeAction.fields["text"].orEmpty()

        // 提取点击坐标
        val element = ActionUtils.parsePoint(tapAction.fields["element"])
            ?: ActionUtils.parsePoint(tapAction.fields["point"])
            ?: ActionUtils.parsePoint(tapAction.fields["pos"])
        
        if (element == null) {
            onLog("[合并执行] 无法获取点击坐标，回退到分别执行")
            return actionExecutor.execute(typeAction, service, uiDump, screenW, screenH, onLog)
        }
        
        val (x, y) = ActionUtils.parsePointToScreen(element, screenW, screenH)
        onLog("[合并执行] Tap(${element.first},${element.second}) + Type(${inputText.take(config.logCombineInputTextTruncateLength)})")

        // 隐藏悬浮窗
        AutomationOverlay.temporaryHide()
        delay(30)

        // 执行点击
        val clickOk = service.clickAwait(x, y)
        if (!clickOk) {
            AutomationOverlay.restoreVisibility()
            return false
        }

        // 等待键盘弹出
        delay(config.tapTypeCombineKeyboardWaitMs)

        // 执行输入
        var ok = service.setTextOnFocused(inputText)

        // 如果失败，尝试查找可编辑元素
        if (!ok) {
            onLog("[合并执行] 直接输入失败，尝试查找输入框...")
        val inputClicked = service.clickFirstEditableElement()
        if (inputClicked) {
                        delay(200)
                        ok = service.setTextOnFocused(inputText)
            }
        }

        AutomationOverlay.restoreVisibility()
        return ok
    }

    /**
     * 解析动作并修复
     */
    private suspend fun parseActionWithRepair(
        apiKey: String,
        model: String,
        history: MutableList<ChatRequestMessage>,
        step: Int,
        answerText: String,
            onLog: (String) -> Unit,
    ): ParsedAgentAction {
        var action = actionParser.parse(ActionUtils.extractFirstActionSnippet(answerText) ?: answerText)
        
        if (action.metadata == "do" || action.metadata == "finish") {
            return action
        }
        
        var attempt = 0
        while (attempt < config.maxParseRepairs && action.metadata != "do" && action.metadata != "finish") {
            attempt++
            onLog("[Step $step] 输出无法解析为动作，尝试修正（$attempt/${config.maxParseRepairs}）…")
            
            // 构建修复消息
            val repairHistory = mutableListOf<ChatRequestMessage>()
            history.firstOrNull { it.role == "system" }?.let { repairHistory.add(it) }
            history.lastOrNull { it.role == "user" }?.let { repairHistory.add(it) }
            repairHistory.add(ChatRequestMessage(role = "user", content = PromptTemplates.buildRepairPrompt()))
            
            val repairResult = requestModelWithRetry(
                apiKey = apiKey,
                model = model,
                messages = repairHistory,
                step = step,
                purpose = "修正输出",
                onLog = onLog,
            )
            
            val repairFinal = repairResult.getOrNull()?.trim().orEmpty()
            if (repairFinal.isBlank()) {
                val err = repairResult.exceptionOrNull()
                onLog("[Step $step] 修正输出失败：${err?.message.orEmpty().take(240)}")
                continue
            }
            
            val (_, repairAnswer) = actionParser.parseWithThinking(repairFinal)
            onLog("[Step $step] 修正输出：${repairAnswer.take(220)}")
            
            action = actionParser.parse(ActionUtils.extractFirstActionSnippet(repairAnswer) ?: repairAnswer)
        }
        
        return action
    }
    
    /**
     * 带重试的模型请求
     */
    private suspend fun requestModelWithRetry(
        apiKey: String,
        model: String,
        messages: List<ChatRequestMessage>,
        step: Int,
        purpose: String,
        onLog: (String) -> Unit,
    ): kotlin.Result<String> {
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
                    frequencyPenalty = config.frequencyPenalty,
                )
            }
            
            if (result.isSuccess) return result
            
            val err = result.exceptionOrNull()
            if (err is CancellationException) throw err
            lastErr = err
            
            val retryable = ActionUtils.isRetryableModelError(err)
            if (!retryable || attempt >= maxAttempts - 1) break
            
            val waitMs = ActionUtils.computeModelRetryDelayMs(attempt, config.modelRetryBaseDelayMs)
            onLog("[Step $step] $purpose 失败：${err?.message.orEmpty().take(240)}（${attempt + 1}/$maxAttempts），${waitMs}ms 后重试…")
            delay(waitMs)
        }
        
        return kotlin.Result.failure(lastErr ?: java.io.IOException("Unknown model error"))
    }
    
    /**
     * 等待暂停状态恢复
     */
    private suspend fun awaitIfPaused(control: Control) {
        while (control.isPaused()) {
            delay(config.pauseCheckIntervalMs)
        }
    }
    
    /**
     * 修剪历史消息，保持上下文在限制内
     */
    private fun trimHistory(history: MutableList<ChatRequestMessage>) {
        // 移除图片只保留文本
        for (i in history.indices) {
            val msg = history[i]
            if (msg.content is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val content = msg.content as List<Map<String, Any>>
                val textOnly = content.filter { it["type"] == "text" }
                if (textOnly.isNotEmpty()) {
                    history[i] = ChatRequestMessage(role = msg.role, content = textOnly)
                }
            }
        }

        // 按token数量修剪
        while (history.size > 2 && ActionUtils.estimateHistoryTokens(history) > config.maxContextTokens) {
            val removeIndex = history.indexOfFirst { it.role != "system" }
            if (removeIndex >= 0) {
                history.removeAt(removeIndex)
                if (removeIndex < history.size && history[removeIndex].role == "assistant") {
                    history.removeAt(removeIndex)
                }
            } else {
                break
            }
        }

        // 按对话轮数修剪
        var turns = 0
        var i = history.size - 1
        while (i >= 0 && turns < config.maxHistoryTurns * 2) {
            if (history[i].role != "system") turns++
            i--
        }
        val keepFrom = (i + 1).coerceAtLeast(if (history.any { it.role == "system" }) 1 else 0)
        while (history.size > keepFrom + turns) {
            val idx = history.indexOfFirst { it.role != "system" }
            if (idx < 0 || idx >= history.size - turns) break
            history.removeAt(idx)
        }
    }
}
