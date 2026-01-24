package com.ai.phoneagent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import com.ai.phoneagent.net.AutoGlmClient
import com.ai.phoneagent.net.ChatRequestMessage
import com.ai.phoneagent.core.cache.ScreenshotCache
import com.ai.phoneagent.core.cache.ScreenshotThrottler
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class UiAutomationAgent(
        private val config: Config = Config(),
) {
    
    // 截图优化组件
    private val screenshotCache = ScreenshotCache(
        maxSize = 3,           // 最多缓墵3张截图
        ttlMs = 2000L          // 2秒TTL
    )
    private val screenshotThrottler = ScreenshotThrottler(
        minIntervalMs = 1100L  // 1.1秒最小间隔
    )

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

    data class Config(
            val maxSteps: Int = 100,
            val stepDelayMs: Long = 160L,
            val maxModelRetries: Int = 2,
            val modelRetryBaseDelayMs: Long = 700L,
            val maxParseRepairs: Int = 2,
            val maxActionRepairs: Int = 1,
            val temperature: Float? = 0.0f,
            val topP: Float? = 0.85f,
            val frequencyPenalty: Float? = 0.2f,
            val maxTokens: Int? = 3000,
            // 上下文管理参数
            val maxContextTokens: Int = 20000,  // 留足够余量给输出
            val maxUiTreeChars: Int = 3000,     // 限制UI树大小
            val maxHistoryTurns: Int = 6,       // 最多保留几轮对话
            // 性能优化参数
            val useStreamingWithEarlyStop: Boolean = true,  // 使用流式API并在获取完整动作后早停
            val parallelScreenshotAndUi: Boolean = true,    // 并行获取截图和UI树
            val postActionDelayMs: Long = 120L,             // 执行动作后等待时间（原160ms）
            // 截图优化参数
            val enableScreenshotCache: Boolean = true,      // 启用截图缓存
            val enableScreenshotThrottle: Boolean = true,   // 启用截图节流
            val screenshotCompressionQuality: Int = 85,     // JPEG压缩质量 (0-100)
            val screenshotMaxSizeKB: Int = 150,             // 最大截图大小 (KB)
    )

    data class Result(
            val success: Boolean,
            val message: String,
            val steps: Int,
    )

    private data class ParsedAgentAction(
            val metadata: String,
            val actionName: String?,
            val fields: Map<String, String>,
            val raw: String,
    )

    /**
     * 获取用于显示的动作名称（中文友好）
     */
    private fun getDisplayActionName(actionName: String, action: ParsedAgentAction): String {
        val normalizedName = actionName.replace(" ", "").lowercase()
        return when (normalizedName) {
            "launch", "open_app", "start_app" -> {
                val app = action.fields["app"] ?: action.fields["package"] ?: ""
                if (app.isNotBlank()) "启动 $app" else "启动应用"
            }
            "tap", "click", "press" -> "点击"
            "doubletap", "double_tap" -> "双击"
            "longpress", "long_press" -> "长按"
            "swipe", "scroll" -> "滑动"
            "type", "input", "text" -> {
                val text = action.fields["text"]?.take(10) ?: ""
                if (text.isNotBlank()) "输入 \"$text\"" else "输入文本"
            }
            "type_name" -> "输入姓名"
            "back" -> "返回"
            "home" -> "回到桌面"
            "wait" -> "等待加载"
            "take_over", "takeover" -> "需要接管"
            else -> actionName.ifBlank { "操作" }
        }
    }

    private fun getActionDelayMs(actionName: String): Long {
        val normalized = actionName.replace(" ", "").lowercase()
        return when (normalized) {
            "launch", "open_app", "start_app" -> 1050L
            "type", "input", "text", "type_name" -> 260L
            "tap", "click", "press", "doubletap", "double_tap", "longpress", "long_press" -> 320L
            "swipe", "scroll" -> 420L
            "back" -> 220L
            "home" -> 420L
            "wait" -> 650L
            else -> 240L
        }
    }

    private fun extractFirstActionSnippet(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("do") || trimmed.startsWith("finish")) return trimmed

        val m =
                Regex("""(do\s*\(.*?\))|(finish\s*\(.*?\))""", RegexOption.DOT_MATCHES_ALL)
                        .find(trimmed)
        return m?.value?.trim()
    }

    private fun isRetryableModelError(t: Throwable?): Boolean {
        if (t == null) return false
        if (t is CancellationException) return false
        if (t is AutoGlmClient.ApiException) {
            if (t.code == 429) return true
            if (t.code in 500..599) return true
            return false
        }
        return t is IOException
    }

    private fun computeModelRetryDelayMs(attempt: Int): Long {
        val base = config.modelRetryBaseDelayMs.coerceAtLeast(0L)
        val mult = 1L shl attempt.coerceIn(0, 6)
        return (base * mult).coerceAtMost(6000L)
    }

    /**
     * 优化的截图获取方法（集成缓存+节流+压缩）
     * 1. 检查节流器，防止频繁截图
     * 2. 检查缓存，避免重复截图
     * 3. 执行截图并压缩优化
     */
    private suspend fun getOptimizedScreenshot(
        service: PhoneAgentAccessibilityService
    ): PhoneAgentAccessibilityService.ScreenshotData? {
        // 检查节流器
        if (config.enableScreenshotThrottle && !screenshotThrottler.canTakeScreenshot()) {
            val remainingWait = screenshotThrottler.getRemainingWaitTime()
            if (remainingWait > 0) {
                // 尝试从缓存获取
                if (config.enableScreenshotCache) {
                    val currentApp = service.currentAppPackage()
                    val windowEventTime = service.lastWindowEventTime()
                    val cacheKey = screenshotCache.generateKey(currentApp, windowEventTime)
                    
                    val cachedScreenshot = screenshotCache.get(cacheKey)
                    if (cachedScreenshot is PhoneAgentAccessibilityService.ScreenshotData) {
                        return cachedScreenshot
                    }
                }
                
                // 无缓存且需要等待，返回null
                return null
            }
        }
        
        // 检查缓存
        if (config.enableScreenshotCache) {
            val currentApp = service.currentAppPackage()
            val windowEventTime = service.lastWindowEventTime()
            val cacheKey = screenshotCache.generateKey(currentApp, windowEventTime)
            
            val cachedScreenshot = screenshotCache.get(cacheKey)
            if (cachedScreenshot is PhoneAgentAccessibilityService.ScreenshotData) {
                return cachedScreenshot
            }
            
            // 缓存未命中，执行截图
            val screenshot = service.tryCaptureScreenshotBase64()
            if (screenshot != null) {
                // 存储到缓存
                screenshotCache.put(cacheKey, screenshot)
                
                // 清理过期缓存
                screenshotCache.evictExpired()
            }
            return screenshot
        } else {
            // 未启用缓存，直接截图
            return service.tryCaptureScreenshotBase64()
        }
    }
    
    /**
     * 清理截图缓存（在任务开始/结束时调用）
     */
    private fun clearScreenshotCache() {
        if (config.enableScreenshotCache) {
            screenshotCache.clear()
        }
        if (config.enableScreenshotThrottle) {
            screenshotThrottler.reset()
        }
    }

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

            val result =
                    withContext(Dispatchers.IO) {
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

            val retryable = isRetryableModelError(err)
            if (!retryable || attempt >= maxAttempts - 1) break

            val waitMs = computeModelRetryDelayMs(attempt)
            onLog(
                    "[Step $step] $purpose 失败：${err?.message.orEmpty().take(240)}（${attempt + 1}/$maxAttempts），${waitMs}ms 后重试…"
            )
            delay(waitMs)
        }

        return kotlin.Result.failure(lastErr ?: IOException("Unknown model error"))
    }

    private suspend fun parseActionWithRepair(
            apiKey: String,
            model: String,
            history: MutableList<ChatRequestMessage>,
            step: Int,
            answerText: String,
            onLog: (String) -> Unit,
    ): ParsedAgentAction {
        var action = parseAgentAction(extractFirstActionSnippet(answerText) ?: answerText)
        if (action.metadata == "do" || action.metadata == "finish") return action

        var attempt = 0
        while (attempt < config.maxParseRepairs && action.metadata != "do" && action.metadata != "finish") {
            attempt++
            onLog("[Step $step] 输出无法解析为动作，尝试修正（$attempt/${config.maxParseRepairs}）…")

            // 更直接的修复提示，强调只输出动作，不要思考
            val repairMsg = """你的上一条输出格式错误或被截断。请直接输出一个动作，不要输出任何思考内容：

do(action="Tap", element=[x,y])
或 do(action="Type", text="要输入的文字")
或 do(action="Swipe", start=[x1,y1], end=[x2,y2])
或 do(action="Back")
或 finish(message="完成原因")

只输出上述格式之一，不要输出其他任何文字。"""

                // 修复时回到简单模式：只用system + 最后一条user消息 + 修正指令
                val repairHistory = mutableListOf<ChatRequestMessage>()
                history.firstOrNull { it.role == "system" }?.let { repairHistory.add(it) }
                // 只保留最后一条用户消息
                history.lastOrNull { it.role == "user" }?.let { repairHistory.add(it) }
                repairHistory.add(ChatRequestMessage(role = "user", content = repairMsg))

            val repairResult =
                    requestModelWithRetry(
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

            val (_, repairAnswer) = splitThinkingAndAnswer(repairFinal)
            onLog("[Step $step] 修正输出：${repairAnswer.take(220)}")
            // 不再将修复消息加入历史，避免污染上下文
            action = parseAgentAction(extractFirstActionSnippet(repairAnswer) ?: repairAnswer)
        }

        return action
    }

    /**
     * 检测用户任务中是否包含需要打开的应用，如果包含则自动启动（智能应用启动）
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
            service.awaitWindowEvent(beforeTime, timeoutMs = 2200L)
            delay(500)
            return true
        } catch (e: Exception) {
            onLog("[⚡快速启动] 启动失败: ${e.message}")
            return false
        }
    }

    /**
     * 检测是否可以执行 Tap+Type 合并操作
     * 当当前动作是 Tap（点击输入框）且下一轮会执行 Type（输入文本）时，可以合并执行
     */
    private fun shouldCombineTapType(currentAction: ParsedAgentAction, nextAction: ParsedAgentAction?): Boolean {
        val currentName = currentAction.actionName?.trim()?.lowercase() ?: ""
        val nextName = nextAction?.actionName?.trim()?.lowercase() ?: ""
        
        // 判断当前动作是否是点击（可能是点击输入框）
        val isTap = currentName == "tap" || currentName == "click" || currentName == "press"
        
        // 判断下一个动作是否是输入
        val isType = nextName == "type" || nextName == "input" || nextName == "text"
        
        // 如果当前点击有坐标且下一个是输入，且当前点击没有 selector 参数（使用坐标点击）
        // 则可能是点击输入框后输入，可以合并
        if (isTap && isType) {
            val currentHasElement = currentAction.fields.containsKey("element") || 
                                   currentAction.fields.containsKey("x") ||
                                   currentAction.fields.containsKey("point")
            return currentHasElement
        }
        
        return false
    }

    /**
     * 执行合并的 Tap+Type 操作
     */
    private suspend fun executeTapAndType(
        service: PhoneAgentAccessibilityService,
        tapAction: ParsedAgentAction,
        typeAction: ParsedAgentAction,
        uiDump: String,
        screenW: Int,
        screenH: Int,
        control: Control,
        onLog: (String) -> Unit,
    ): Boolean {
        val inputText = typeAction.fields["text"].orEmpty()
        
        // 提取点击坐标
        val element = parsePoint(tapAction.fields["element"])
                ?: parsePoint(tapAction.fields["point"])
                ?: parsePoint(tapAction.fields["pos"])
        
        if (element == null) {
            onLog("[合并执行] 无法获取点击坐标，回退到分别执行")
            return false
        }
        
        val x = (element.first / 1000.0f) * screenW
        val y = (element.second / 1000.0f) * screenH
        
        onLog("[合并执行] Tap(${element.first},${element.second}) + Type(${inputText.take(20)})")
        
        // 隐藏悬浮窗
        AutomationOverlay.temporaryHide()
        delay(30)
        
        // 执行点击
        val clickOk = service.clickAwait(x, y)
        if (!clickOk) {
            AutomationOverlay.restoreVisibility()
            return false
        }
        
        // 等待输入框激活
        delay(200)
        
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

    suspend fun run(
            apiKey: String,
            model: String,
            task: String,
            service: PhoneAgentAccessibilityService,
            control: Control = NoopControl,
            onLog: (String) -> Unit,
    ): Result {
        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val smartLaunched = trySmartAppLaunch(task, service, onLog)
        if (smartLaunched) {
            onLog("✓ 应用已快速启动，继续后续操作...")
        }

        val history = mutableListOf<ChatRequestMessage>()
        history +=
                ChatRequestMessage(role = "system", content = buildSystemPrompt(screenW, screenH))

        clearScreenshotCache()

        // 重置 Tap+Type 合并执行状态
        lastActionWasTap = false
        lastTapAction = null

        var step = 0
        while (step < config.maxSteps) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()

            awaitIfPaused(control)

            step++
            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0f,
                    maxSteps = config.maxSteps,
                    subtitle = "读取界面"
            )
            
            // 并行获取截图和UI树
            val (screenshot, rawUiDump) = coroutineScope {
                val screenshotDeferred = async { getOptimizedScreenshot(service) }
                val uiDumpDeferred = async { service.dumpUiTreeWithRetry(maxNodes = 30) }
                Pair(screenshotDeferred.await(), uiDumpDeferred.await())
            }
            
            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0.15f,
                    maxSteps = config.maxSteps,
                    subtitle = "解析界面"
            )
            val uiDump = truncateUiTree(rawUiDump, config.maxUiTreeChars)

            val currentApp = service.currentAppPackage()
            val screenInfo = "{\"current_app\":\"${currentApp.replace("\"", "")}\"}"

            if (screenshot != null) {
                onLog("[Step $step] 截图：${screenshot.width}x${screenshot.height}")
            } else {
                onLog("[Step $step] 截图：不可用（将使用纯文本/无障碍树模式）")
            }

            val userMsg =
                    if (step == 1) {
                        "$task\n\n$screenInfo\n\nUI树：\n$uiDump"
                    } else {
                        "$screenInfo\n\nUI树：\n$uiDump"
                    }

            val userContent: Any =
                    if (screenshot != null) {
                        listOf(
                                mapOf(
                                        "type" to "image_url",
                                        "image_url" to
                                                mapOf(
                                                        "url" to
                                                                "data:image/jpeg;base64,${screenshot.base64Png}"
                                                )
                                ),
                                mapOf("type" to "text", "text" to userMsg)
                        )
                    } else {
                        userMsg
                    }
            
            trimHistory(history, config.maxContextTokens, config.maxHistoryTurns)
            
            history += ChatRequestMessage(role = "user", content = userContent)
            val observationUserIndex = history.lastIndex

            onLog("[Step $step] 请求模型…")
            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0.25f,
                    maxSteps = config.maxSteps,
                    subtitle = "请求模型"
            )
            
            AutomationOverlay.startThinking()
            
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
            
            if (finalReply.isBlank()) {
                val err = replyResult.exceptionOrNull()
                val msg = err?.message?.trim().orEmpty().ifBlank { "模型无回复或请求失败" }
                return Result(false, "模型请求失败：${msg.take(320)}", step)
            }

            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0.55f,
                    maxSteps = config.maxSteps,
                    subtitle = "解析模型输出"
            )

            val (thinking, answer) = splitThinkingAndAnswer(finalReply)
            if (!thinking.isNullOrBlank()) {
                onLog("[Step $step] 思考：${thinking.take(180)}")
                if (step == 1) {
                    val estimatedSteps = parseEstimatedSteps(thinking)
                    if (estimatedSteps > 0) {
                        AutomationOverlay.updateEstimatedSteps(estimatedSteps)
                        onLog("[Step $step] 预估总步骤数：$estimatedSteps")
                    }
                }
            }
            onLog("[Step $step] 输出：${answer.take(220)}")

            history += ChatRequestMessage(role = "assistant", content = finalReply)

            val action =
                    parseActionWithRepair(
                            apiKey = apiKey,
                            model = model,
                            history = history,
                            step = step,
                            answerText = answer,
                            onLog = onLog,
                    )
            if (action.metadata == "finish") {
                val msg = action.fields["message"].orEmpty().ifBlank { "已完成" }
                return Result(true, msg, step)
            }
            if (action.metadata != "do") {
                return Result(false, "无法解析动作：${action.raw.take(240)}", step)
            }

            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0.68f,
                    maxSteps = config.maxSteps,
                    subtitle = "准备执行"
            )

            var currentAction = action
            var actionName = ""
            var execOk = false
            var repairAttempt = 0

            while (true) {
                actionName =
                        currentAction.actionName
                                ?.trim()
                                ?.trim('"', '\'', ' ')
                                ?.lowercase()
                                .orEmpty()
                
                val displayActionName = getDisplayActionName(actionName, currentAction)
                AutomationOverlay.updateProgress(
                        step = step,
                        phaseInStep = 0.78f,
                        maxSteps = config.maxSteps,
                        subtitle = "执行 $displayActionName"
                )

                if (actionName == "take_over" || actionName == "takeover") {
                    val msg = currentAction.fields["message"].orEmpty().ifBlank { "需要用户接管" }
                    return Result(false, msg, step)
                }

                if (actionName == "note" || actionName == "call_api" || actionName == "interact") {
                    return Result(false, "需要用户交互/扩展能力：${currentAction.raw.take(180)}", step)
                }

                // 检测是否可以合并执行 Tap+Type
                // 当当前是 Type 且上一个动作是 Tap（点击输入框）时，尝试合并执行
                val isTypeAction = actionName == "type" || actionName == "input" || actionName == "text"
                val wasPreviousTap = lastActionWasTap

                execOk =
                        try {
                            if (isTypeAction && wasPreviousTap) {
                                // 合并执行：先点击再输入
                                // 记录之前的 Tap 动作信息
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
                                        control = control,
                                        onLog = onLog
                                    )
                                } else {
                                    // 没有记录到上一个 Tap，直接执行 Type
                                    execute(service, currentAction, uiDump, screenW, screenH, control, onLog)
                                }
                            } else {
                                // 正常执行
                                if (actionName == "tap" || actionName == "click" || actionName == "press") {
                                    // 记录这个 Tap，可能下一轮会 Type
                                    lastActionWasTap = true
                                    lastTapAction = currentAction
                                } else {
                                    lastActionWasTap = false
                                    lastTapAction = null
                                }
                                execute(service, currentAction, uiDump, screenW, screenH, control, onLog)
                            }
                        } catch (e: TakeOverException) {
                            val msg = e.message.orEmpty().ifBlank { "需要用户接管" }
                            return Result(false, msg, step)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onLog("[Step $step] 动作执行异常：${e.message.orEmpty().take(240)}")
                            false
                        }

                if (execOk) break

                if (repairAttempt >= config.maxActionRepairs) {
                    return Result(false, "动作执行失败：${currentAction.raw.take(240)}", step)
                }
                repairAttempt++

                onLog("[Step $step] 动作执行失败，尝试让模型修复（$repairAttempt/${config.maxActionRepairs})…")

                AutomationOverlay.updateProgress(
                        step = step,
                        phaseInStep = 0.62f,
                        maxSteps = config.maxSteps,
                        subtitle = "动作失败，修复中"
                )

                val failMsg =
                        "上一步动作执行失败：${currentAction.raw.take(320)}\n" +
                                "请根据上一条屏幕信息重新给出下一步动作，优先使用 selector（resourceId/elementText/contentDesc/className/index）。\n" +
                                "严格只输出：\n【思考开始】...【思考结束】\n【回答开始】do(...)/finish(...)【回答结束】。"
                history += ChatRequestMessage(role = "user", content = failMsg)

                val fixResult =
                        requestModelWithRetry(
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
                    return Result(false, "动作修复失败：${msg.take(320)}", step)
                }

                AutomationOverlay.updateProgress(
                        step = step,
                        phaseInStep = 0.72f,
                        maxSteps = config.maxSteps,
                        subtitle = "解析修复动作"
                )

                val (fixThinking, fixAnswer) = splitThinkingAndAnswer(fixFinal)
                if (!fixThinking.isNullOrBlank()) {
                    onLog("[Step $step] 修复思考：${fixThinking.take(180)}")
                }
                onLog("[Step $step] 修复输出：${fixAnswer.take(220)}")
                history += ChatRequestMessage(role = "assistant", content = fixFinal)

                currentAction =
                        parseActionWithRepair(
                                apiKey = apiKey,
                                model = model,
                                history = history,
                                step = step,
                                answerText = fixAnswer,
                                onLog = onLog,
                        )

                if (currentAction.metadata == "finish") {
                    val msg = currentAction.fields["message"].orEmpty().ifBlank { "已完成" }
                    return Result(true, msg, step)
                }
                if (currentAction.metadata != "do") {
                    return Result(false, "无法解析动作：${currentAction.raw.take(240)}", step)
                }
            }

            val extraDelayMs = getActionDelayMs(actionName)

            AutomationOverlay.updateProgress(
                    step = step,
                    phaseInStep = 0.92f,
                    maxSteps = config.maxSteps,
                    subtitle = "等待界面稳定"
            )

            if (observationUserIndex in history.indices) {
                val obs = history[observationUserIndex]
                if (obs.content is List<*>) {
                    history[observationUserIndex] =
                            ChatRequestMessage(role = "user", content = userMsg)
                }
            }

            delay((config.stepDelayMs + extraDelayMs).coerceAtLeast(0L))
        }

        return Result(false, "达到最大步数限制（${config.maxSteps}）", config.maxSteps)
    }

    private fun buildSystemPrompt(screenW: Int, screenH: Int): String {
        val today = LocalDate.now()
        val weekNames = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val formattedDate =
                today.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")) +
                        " " + weekNames[today.dayOfWeek.ordinal]

        return ("""
今天的日期是: $formattedDate
你是 Aries AI 手机自动化助手，基于安卓无障碍服务(AccessibilityService)控制手机执行任务。

【核心原则】
- 每次只执行一个动作，等待执行结果后再决定下一步
- 优先使用 selector（resourceId/text/contentDesc/className/index）定位元素，坐标仅作兜底
- 点击前确保目标元素可见；滚动查找时每次滑动后等待页面加载完成
- 输入文本前必须先点击激活输入框，确保输入框已获得焦点
- 敏感操作（支付/密码/验证码）必须使用 Take_over 让用户接管

【输出格式】（严格遵守，否则无法解析）
【思考开始】
{简洁的推理：为什么选择这个动作}
【思考结束】

【回答开始】
{具体动作指令：do(...) 或 finish(...)}
【回答结束】

【可用动作指令】
1. Launch(app="应用名/包名") - 启动应用（优先使用，比手动导航更快）
2. Tap(element=[x,y]) - 点击坐标（0-1000相对坐标）或使用selector定位
3. Type(text="文字内容") - 在已聚焦的输入框中输入文本
4. Swipe(start=[x1,y1], end=[x2,y2]) - 滑动（滚动/切页/下拉）
5. Back - 返回上一页
6. Home - 返回桌面
7. Wait(duration="x秒") - 等待页面加载
8. Take_over(message="接管原因") - 需要用户处理支付/验证等

【坐标系统】
- 相对坐标：0-1000，例如 element=[500,500] 表示屏幕中心
- 当前屏幕像素：${screenW}x${screenH}
- 优先使用 selector 定位，坐标仅当 selector 失败时作为兜底

【UI树格式说明】
UI树中的 bounds 格式：[left,top][right,bottom]
例如：[100,200][300,400] 表示从 (100,200) 到 (300,400) 的矩形区域
node_id 使用 bounds 字符串作为唯一标识

【重要规则】（必须严格遵守）
0. 检测到支付/密码/验证码/银行卡/CVV/安全码等敏感内容 → 立即 Take_over
1. 【强制先检查后操作】执行任何操作前（包括点击任何按钮、输入任何内容），必须先完成以下检查：
   - 读取并理解当前界面显示的所有关键信息（标题、筛选条件、当前选中项等）
   - 将当前状态与用户任务要求进行逐项对比
   - 确认当前状态是否已经满足需求
   - 只有在确认当前状态不满足需求后，才能执行修改操作
   
   ⚠️ 常见错误：不去读取当前界面显示的值，就直接修改重新填写
   ✅ 正确做法：先读取当前显示的出发地、目的地、日期等，判断是否需要修改

2. 【旅行APP首步检查】在12306、携程、去哪儿、飞猪、同程、马蜂窝等旅行APP中：
   - 进入APP后，优先读取界面顶部/搜索栏显示的：出发地、目的地、日期、舱位等筛选条件
   - 如果这些条件已经与用户需求匹配 → 直接进行下一步（选择车次/航班/酒店），不要点击修改
   - 如果条件不匹配 → 点击修改入口，只更新需要变更的项目
   - 绝对禁止：不去看当前显示的是什么，就直接修改
3. 页面加载超时 → 最多 Wait 3次，否则 Back 重新进入
4. 找不到目标 → 尝试 Swipe 滑动查找；搜索无结果 → 尝试简化关键词
5. 筛选条件放宽处理：价格/时间区间没有完全匹配的可以适当放宽
6. 点击前先检查元素是否可见；点击后等待界面响应（200-400ms）
7. 输入文本前必须确保输入框已获得焦点（观察是否有光标或键盘弹出）
8. 如果 Type 失败，先用 Tap 再次点击输入框确保聚焦，再重试输入
9. 任务完成前仔细检查：是否有遗漏步骤、是否错选、是否需要回退纠正
10. 连续失败3次或超时 → 考虑换一种方式或跳过该步骤
11. 每次只输出一个 do(...) 动作，等待执行结果后再继续
12. 购物车全选后再点击全选可以把状态设为全不选；购物车里已有商品时，先全选再取消后再操作目标商品
13. 在做外卖任务时，如果店铺购物车里已经有其他商品，先清空再购买用户指定的外卖
14. 点多个外卖时尽量在同一店铺下单，若未找到需说明未找到的商品
15. 严格遵循用户意图执行任务，可多次搜索或滑动查找
16. 选择日期时，如果滑动方向与预期相反，请向反方向滑动
17. 有多个可选择的项目栏时，逐个查找，不要在同一栏多次循环
18. 在做游戏任务时若在战斗页面有自动战斗须开启，历史状态相似需检查是否开启自动战斗
19. 若搜索结果不合适，可能页面不对，返回上一级重新搜索；三次仍无结果则 finish(message="原因")
20. 当 Launch 后发现是系统启动器界面，说明包名无效，此时在启动器中通过 Swipe 查找目标应用图标并点击

【输入操作要点】
- Type 前必须先 Tap 点击输入框，确保焦点
- 点击后等待 200-400ms 让键盘完全弹出
- 如果 setTextOnFocused 失败，尝试 clickFirstEditableElement 后再输入
- 系统会自动优化 Tap+Type 合并执行，减少等待时间
"""
                        .trimIndent()
        )
    }

    private fun splitThinkingAndAnswer(content: String): Pair<String?, String> {
        val full = content.trim()

        val (ariesThinking, ariesAnswer) = extractAriesThinkingAndAnswer(full)
        if (!ariesAnswer.isNullOrBlank()) {
            return ariesThinking to ariesAnswer
        }

        val thinkTag = extractTagContent(full, "think")
        val answerTag = extractTagContent(full, "answer")
        if (answerTag != null) {
            return thinkTag to answerTag
        }

        val finishMarker = "finish(message="
        val doMarker = "do(action="
        val finishIndex = full.indexOf(finishMarker)
        if (finishIndex >= 0) {
            val thinking = full.substring(0, finishIndex).trim().ifEmpty { null }
            val action = full.substring(finishIndex).trim()
            return thinking to action
        }
        val doIndex = full.indexOf(doMarker)
        if (doIndex >= 0) {
            val thinking = full.substring(0, doIndex).trim().ifEmpty { null }
            val action = full.substring(doIndex).trim()
            return thinking to action
        }
        return null to full
    }

    private fun extractAriesThinkingAndAnswer(text: String): Pair<String?, String?> {
        val thinkStartTag = "【思考开始】"
        val thinkEndTag = "【思考结束】"
        val answerStartTags = listOf("【回答开始】", "【回答】")
        val answerEndTag = "【回答结束】"

        val thinkStartIdx = text.indexOf(thinkStartTag)
        val thinkEndIdx = text.indexOf(thinkEndTag)

        val answerStartMatch =
                answerStartTags
                        .mapNotNull { tag ->
                            val idx = text.indexOf(tag)
                            if (idx >= 0) idx to tag else null
                        }
                        .minByOrNull { it.first }
        val answerEndIdx = text.indexOf(answerEndTag)

        val thinking: String? =
                if (thinkStartIdx >= 0) {
                    val start = thinkStartIdx + thinkStartTag.length
                    val end =
                            when {
                                thinkEndIdx >= start -> thinkEndIdx
                                answerStartMatch != null && answerStartMatch.first >= start ->
                                        answerStartMatch.first
                                else -> text.length
                            }
                    text.substring(start, end).trim().ifBlank { null }
                } else {
                    null
                }

        val answer: String? =
                if (answerStartMatch != null) {
                    val start = answerStartMatch.first + answerStartMatch.second.length
                    val end = if (answerEndIdx >= start) answerEndIdx else text.length
                    text.substring(start, end).trim()
                } else {
                    null
                }

        return thinking to answer
    }
    
    private fun parseEstimatedSteps(thinking: String): Int {
        val explicitPatterns = listOf(
            Regex("""(?:需要|大约|共|总共|预计)\s*(\d+)\s*(?:步|个步骤|个操作)"""),
            Regex("""(\d+)\s*(?:步|个步骤|个操作)(?:完成|即可|就能)"""),
        )
        for (pattern in explicitPatterns) {
            val match = pattern.find(thinking)
            if (match != null) {
                val num = match.groupValues.getOrNull(1)?.toIntOrNull()
                if (num != null && num in 2..20) return num
            }
        }
        
        val needPattern = Regex("""我需要[：:]\s*([\s\S]*?)(?:首先|然后|接下来|现在|$)""")
        val needMatch = needPattern.find(thinking)
        if (needMatch != null) {
            val needContent = needMatch.groupValues.getOrNull(1).orEmpty()
            val numbers = Regex("""(\d+)\s*[\.、）\)：:]""")
                .findAll(needContent)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toList()
            if (numbers.isNotEmpty()) {
                val maxStep = numbers.maxOrNull() ?: 0
                if (maxStep in 2..20) return maxStep
            }
        }
        
        val numberedSteps = Regex("""(?:^|\n|\s|，|。|；)(\d+)\s*[\.、）\)：:]|第(\d+)步""")
            .findAll(thinking)
            .mapNotNull { 
                it.groupValues.getOrNull(1)?.toIntOrNull() 
                    ?: it.groupValues.getOrNull(2)?.toIntOrNull() 
            }
            .toList()
        
        if (numberedSteps.isNotEmpty()) {
            val maxStep = numberedSteps.maxOrNull() ?: 0
            if (maxStep in 2..20) return maxStep
        }
        
        val actionKeywords = listOf("点击", "输入", "滑动", "打开", "选择", "返回", "等待", "启动", "查询", "修改")
        val actionCount = actionKeywords.sumOf { keyword -> 
            thinking.split(keyword).size - 1 
        }
        if (actionCount >= 2) {
            return actionCount.coerceIn(2, 15)
        }
        
        return 0
    }

    private fun extractTagContent(text: String, tag: String): String? {
        val pattern = Regex("""<$tag>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
        return pattern.find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parseAgentAction(raw: String): ParsedAgentAction {
        val original = raw.trim()
        
        val hasTruncationSign = original.contains("\uFFFD") ||
            original.endsWith("…") ||
            original.endsWith("...") ||
            (original.contains("我需要") && !original.contains("do(") && !original.contains("finish("))
        
        if (original.contains("text=\"") && original.count { it == '=' } > 10 && 
            !original.contains("do(") && !original.contains("finish(")) {
            return ParsedAgentAction("unknown", null, emptyMap(), original.take(200))
        }
        
        if (hasTruncationSign && !original.contains("do(") && !original.contains("finish(")) {
            return ParsedAgentAction("unknown", null, emptyMap(), "输出被截断，未包含动作")
        }
        
        val finishIndex = original.lastIndexOf("finish(")
        val doIndex = original.lastIndexOf("do(")
        val startIndex =
                when {
                    finishIndex >= 0 && doIndex >= 0 -> maxOf(finishIndex, doIndex)
                    finishIndex >= 0 -> finishIndex
                    doIndex >= 0 -> doIndex
                    else -> -1
                }
        val trimmed = if (startIndex >= 0) original.substring(startIndex).trim() else original

        if (trimmed.startsWith("finish")) {
            val messageRegex =
                    Regex(
                            """finish\s*\(\s*message\s*=\s*\"(.*)\"\s*\)""",
                            RegexOption.DOT_MATCHES_ALL
                    )
            val message = messageRegex.find(trimmed)?.groupValues?.getOrNull(1) ?: ""
            return ParsedAgentAction("finish", null, mapOf("message" to message), trimmed)
        }

        if (!trimmed.startsWith("do")) {
            return ParsedAgentAction("unknown", null, emptyMap(), trimmed.take(200))
        }

        val openParenIndex = trimmed.indexOf('(')
        if (openParenIndex < 0) {
            return ParsedAgentAction("unknown", null, emptyMap(), "do命令不完整")
        }
        
        var parenCount = 0
        var closeParenIndex = -1
        for (i in openParenIndex until trimmed.length) {
            when (trimmed[i]) {
                '(' -> parenCount++
                ')' -> {
                    parenCount--
                    if (parenCount == 0) {
                        closeParenIndex = i
                        break
                    }
                }
            }
        }
        
        val inner = if (closeParenIndex > openParenIndex) {
            trimmed.substring(openParenIndex + 1, closeParenIndex).trim()
        } else {
            trimmed.substring(openParenIndex + 1).trim().trimEnd(')', ',', ' ')
        }
        
        val fields = mutableMapOf<String, String>()
        val regex = Regex("""(\w+)\s*=\s*(?:\[(.*?)\]|\"(.*?)\"|'([^']*)'|([^,)]+))""")
        regex.findAll(inner).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues.drop(2).firstOrNull { it.isNotEmpty() } ?: ""
            fields[key] = value
        }
        
        if (fields.containsKey("action")) {
            return ParsedAgentAction("do", fields["action"], fields, trimmed)
        }

        return ParsedAgentAction("unknown", null, emptyMap(), trimmed.take(200))
    }

    /**
     * 合并执行 Tap + Type 操作
     * 先点击输入框，然后立即输入文本，减少等待时间
     * 优化策略：点击 → 等待 → 查找可编辑元素 → 聚焦 → 输入
     */
    private suspend fun executeTapAndTypeCombined(
        service: PhoneAgentAccessibilityService,
        tapAction: ParsedAgentAction,
        typeAction: ParsedAgentAction,
        uiDump: String,
        screenW: Int,
        screenH: Int,
        control: Control,
        onLog: (String) -> Unit,
    ): Boolean {
        val inputText = typeAction.fields["text"].orEmpty()

        // 提取点击坐标
        val element = parsePoint(tapAction.fields["element"])
                ?: parsePoint(tapAction.fields["point"])
                ?: parsePoint(tapAction.fields["pos"])
                ?: return false

        val x = (element.first / 1000.0f) * screenW
        val y = (element.second / 1000.0f) * screenH

        onLog("[合并执行] Tap(${element.first},${element.second}) + Type(${inputText.take(20)})")

        // 隐藏悬浮窗
        AutomationOverlay.temporaryHide()
        delay(30)

        // 执行点击
        val clickOk = service.clickAwait(x, y)
        onLog("[合并执行] 点击结果: $clickOk")
        if (!clickOk) {
            AutomationOverlay.restoreVisibility()
            return false
        }

        // 等待键盘弹出和输入框激活
        delay(400)

        // 策略1：尝试直接setTextOnFocused
        var ok = service.setTextOnFocused(inputText)
        if (ok) {
            onLog("[合并执行] setTextOnFocused 成功")
            AutomationOverlay.restoreVisibility()
            return true
        }

        // 策略2：查找并点击第一个可编辑元素
        onLog("[合并执行] setTextOnFocused 失败，尝试查找可编辑元素...")
        val inputClicked = service.clickFirstEditableElement()
        if (inputClicked) {
            onLog("[合并执行] 点击可编辑元素成功")
            delay(250)
            ok = service.setTextOnFocused(inputText)
            if (ok) {
                onLog("[合并执行] 第二次 setTextOnFocused 成功")
                AutomationOverlay.restoreVisibility()
                return true
            }
        }

        // 策略3：直接尝试在任意可编辑元素输入（更激进的策略）
        onLog("[合并执行] 尝试直接查找UI树中的可编辑元素...")
        val root = service.rootInActiveWindow
        if (root != null) {
            val editable = findFirstEditableElement(root)
            if (editable != null) {
                val bounds = android.graphics.Rect()
                editable.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    onLog("[合并执行] 找到可编辑元素，点击中心: ${bounds.centerX()},${bounds.centerY()}")
                    delay(100)
                    val edClickOk = service.clickAwait(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    if (edClickOk) {
                        delay(200)
                        ok = service.setTextOnFocused(inputText)
                        if (ok) {
                            onLog("[合并执行] 策略3成功")
                            AutomationOverlay.restoreVisibility()
                            editable.recycle()
                            root.recycle()
                            return true
                        }
                    }
                }
                editable.recycle()
            }
            root.recycle()
        }

        AutomationOverlay.restoreVisibility()
        return ok
    }

    /**
     * 查找第一个可编辑元素（从AccessibilityService复制）
     */
    private fun findFirstEditableElement(root: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        val q = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        q.add(root)
        var guard = 0
        while (q.isNotEmpty() && guard < 600) {
            guard++
            val n = q.removeFirst()
            if (n.isEditable) {
                return n
            }
            // 也检查 EditText 类名
            val className = n.className?.toString().orEmpty()
            if (className.contains("EditText", ignoreCase = true)) {
                return n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { q.add(it) }
            }
        }
        return null
    }

    private suspend fun execute(
            service: PhoneAgentAccessibilityService,
            action: ParsedAgentAction,
            uiDump: String,
            screenW: Int,
            screenH: Int,
            control: Control,
            onLog: (String) -> Unit,
    ): Boolean {
        awaitIfPaused(control)

        val beforeWindowEventTime = service.lastWindowEventTime()

        val rawName = action.actionName ?: return false
        val name = rawName.trim().trim('"', '\'', ' ').lowercase()
        val nameKey = name.replace(" ", "")
        return when (nameKey) {
            "launch", "open_app", "start_app" -> {
                val rawTarget =
                        action.fields["package"]
                                ?: action.fields["package_name"] ?: action.fields["pkg"]
                                        ?: action.fields["app"] ?: ""
                val t = rawTarget.trim().trim('"', '\'', ' ')
                if (t.isBlank()) return false
                val pm = service.packageManager

                fun isInstalled(pkgName: String): Boolean {
                    return runCatching {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkgName, 0)
                        true
                    }.getOrDefault(false)
                }

                fun buildLaunchIntent(pkgName: String): Intent? {
                    val direct = pm.getLaunchIntentForPackage(pkgName)
                    if (direct != null) return direct
                    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val ri =
                            runCatching { pm.queryIntentActivities(query, 0) }
                                    .getOrNull()
                                    ?.firstOrNull { it.activityInfo?.packageName == pkgName }
                                    ?: return null
                    val ai = ri.activityInfo ?: return null
                    return Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(ai.packageName, ai.name)
                }

                val candidates =
                        buildList {
                                    if (t.contains('.')) add(t)
                                    AppPackageMapping.resolve(t)?.let { add(it) }
                                    resolvePackageByLabel(service, t)?.let { add(it) }
                                    if (!t.contains('.')) add(t)
                                }
                                .distinct()

                var pkgName = candidates.firstOrNull().orEmpty().ifBlank { t }
                var intent: Intent? = null
                for (candidate in candidates) {
                    if (candidate.contains('.') && !isInstalled(candidate)) continue
                    val i = buildLaunchIntent(candidate)
                    if (i != null) {
                        pkgName = candidate
                        intent = i
                        break
                    }
                }

                onLog("执行：Launch($pkgName)")
                if (intent == null) {
                    onLog("Launch 失败：未找到可启动入口：$pkgName（candidates=${candidates.joinToString()}）")
                    throw TakeOverException("暂未在手机中找到$t 应用")
                }
                intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                return try {
                    LaunchProxyActivity.launch(service, intent)
                    service.awaitWindowEvent(beforeWindowEventTime, timeoutMs = 2200L)
                    true
                } catch (e: Exception) {
                    onLog("Launch 失败：${e.message.orEmpty()}")
                    false
                }
            }
            "back" -> {
                onLog("执行：Back")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                service.awaitWindowEvent(beforeWindowEventTime, timeoutMs = 1400L)
                true
            }
            "home" -> {
                onLog("执行：Home")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                service.awaitWindowEvent(beforeWindowEventTime, timeoutMs = 1800L)
                true
            }
            "wait", "sleep" -> {
                val raw = action.fields["duration"].orEmpty().trim()
                val d =
                        when {
                            raw.endsWith("ms", ignoreCase = true) ->
                                    raw.dropLast(2).trim().toLongOrNull()
                            raw.endsWith("s", ignoreCase = true) ->
                                    raw.dropLast(1).trim().toLongOrNull()?.times(1000)
                            raw.contains("second", ignoreCase = true) ->
                                    Regex("""(\d+)""")
                                            .find(raw)
                                            ?.groupValues
                                            ?.getOrNull(1)
                                            ?.toLongOrNull()
                                            ?.times(1000)
                            else -> raw.toLongOrNull()
                        }
                                ?: 600L
                onLog("执行：Wait(${d}ms)")
                delay(d.coerceAtLeast(0L))
                true
            }
            "type", "input", "text" -> {
                if (looksSensitive(uiDump)) {
                    onLog("检测到支付/验证界面关键词，停止并要求用户接管")
                    throw TakeOverException("检测到支付/验证界面，需要用户接管")
                }

                val inputText = action.fields["text"].orEmpty()
                val resourceId = action.fields["resourceId"] ?: action.fields["resource_id"]
                val contentDesc = action.fields["contentDesc"] ?: action.fields["content_desc"]
                val className = action.fields["className"] ?: action.fields["class_name"]
                val elementText =
                        action.fields["elementText"]
                                ?: action.fields["element_text"]
                                        ?: action.fields["targetText"] ?: action.fields["target_text"]
                val index = action.fields["index"]?.trim()?.toIntOrNull() ?: 0
                
                val element = parsePoint(action.fields["element"])
                        ?: parsePoint(action.fields["point"])
                if (element != null) {
                    val x = (element.first / 1000.0f) * screenW
                    val y = (element.second / 1000.0f) * screenH
                    onLog("执行：先点击输入框(${element.first},${element.second})")
                    AutomationOverlay.temporaryHide()
                    delay(50)
                    service.clickAwait(x, y)
                    AutomationOverlay.restoreVisibility()
                    delay(300)
                }

                onLog("执行：Type(${inputText.take(40)})")

                var ok =
                        if (resourceId != null || contentDesc != null || className != null || elementText != null) {
                            service.setTextOnElement(
                                    text = inputText,
                                    resourceId = resourceId,
                                    elementText = elementText,
                                    contentDesc = contentDesc,
                                    className = className,
                                    index = index,
                            )
                        } else {
                            service.setTextOnFocused(inputText)
                        }
                
                if (!ok) {
                    onLog("输入失败，尝试查找并激活输入框…")
                    val inputClicked = service.clickFirstEditableElement()
                    if (inputClicked) {
                        delay(300)
                        ok = service.setTextOnFocused(inputText)
                    }
                }

                service.awaitWindowEvent(beforeWindowEventTime, timeoutMs = 1200L)
                ok
            }
            "tap", "click", "press" -> {
                if (looksSensitive(uiDump)) {
                    onLog("检测到支付/验证界面关键词，停止并要求用户接管")
                    throw TakeOverException("检测到支付/验证界面，需要用户接管")
                }

                val confirmMsg = action.fields["message"].orEmpty().trim()
                if (confirmMsg.isNotBlank()) {
                    onLog("敏感操作需要确认：$confirmMsg")
                    val ok = control.confirm(confirmMsg)
                    if (!ok) {
                        throw TakeOverException(confirmMsg)
                    }
                }

                val resourceId = action.fields["resourceId"] ?: action.fields["resource_id"]
                val contentDesc = action.fields["contentDesc"] ?: action.fields["content_desc"]
                val className = action.fields["className"] ?: action.fields["class_name"]
                val elementText =
                        action.fields["elementText"]
                                ?: action.fields["element_text"]
                                        ?: action.fields["label"]
                val index = action.fields["index"]?.trim()?.toIntOrNull() ?: 0

                val selectorOk =
                        if (resourceId != null || contentDesc != null || className != null || elementText != null) {
                            onLog("执行：Tap(selector)")
                            service.clickElement(
                                    resourceId = resourceId,
                                    text = elementText,
                                    contentDesc = contentDesc,
                                    className = className,
                                    index = index,
                            )
                        } else {
                            false
                        }

                if (selectorOk) {
                    service.awaitWindowEvent(beforeWindowEventTime, timeoutMs = 1400L)
                    true
                } else {
                    val element =
                            parsePoint(action.fields["element"])
                                    ?: parsePoint(action.fields["point"])
                                            ?: parsePoint(action.fields["pos"])
                    val xRel =
                            action.fields["x"]?.trim()?.toIntOrNull() ?: element?.first ?: return false
                    val yRel =
                            action.fields["y"]?.trim()?.toIntOrNull() ?: element?.second ?: return false
                    val x = (xRel / 1000.0f) * screenW
                    val y = (yRel / 1000.0f) * screenH
                    onLog("执行：Tap($xRel,$yRel)")
                    AutomationOverlay.temporaryHide()
                    delay(50)
                    val ok = service.clickAwait(x, y)
                    AutomationOverlay.restoreVisibility()
                    service.awaitWindowEvent(beforeWindowEventTime, timeoutMs = 1400L)
                    ok
                }
            }
            "longpress", "long_press" -> {
                if (looksSensitive(uiDump)) {
                    onLog("检测到支付/验证界面关键词，停止并要求用户接管")
                    return false
                }
                val element = parsePoint(action.fields["element"]) ?: return false
                val x = (element.first / 1000.0f) * screenW
                val y = (element.second / 1000.0f) * screenH
                onLog("执行：Long Press(${element.first},${element.second})")
                AutomationOverlay.temporaryHide()
                delay(50)
                val ok = service.clickAwait(x, y, durationMs = 520L)
                AutomationOverlay.restoreVisibility()
                service.awaitWindowEvent(beforeWindowEventTime, timeoutMs = 1400L)
                ok
            }
            "doubletap", "double_tap" -> {
                if (looksSensitive(uiDump)) {
                    onLog("检测到支付/验证界面关键词，停止并要求用户接管")
                    return false
                }
                val element = parsePoint(action.fields["element"]) ?: return false
                val x = (element.first / 1000.0f) * screenW
                val y = (element.second / 1000.0f) * screenH
                onLog("执行：Double Tap(${element.first},${element.second})")
                AutomationOverlay.temporaryHide()
                delay(50)
                val ok1 = service.clickAwait(x, y, durationMs = 60L)
                delay(90L)
                val ok2 = service.clickAwait(x, y, durationMs = 60L)
                AutomationOverlay.restoreVisibility()
                service.awaitWindowEvent(beforeWindowEventTime, timeoutMs = 1400L)
                ok1 && ok2
            }
            "swipe", "scroll" -> {
                val start = parsePoint(action.fields["start"])
                val end = parsePoint(action.fields["end"])
                val sxRel =
                        action.fields["start_x"]?.trim()?.toIntOrNull()
                                ?: start?.first ?: return false
                val syRel =
                        action.fields["start_y"]?.trim()?.toIntOrNull()
                                ?: start?.second ?: return false
                val exRel =
                        action.fields["end_x"]?.trim()?.toIntOrNull() ?: end?.first ?: return false
                val eyRel =
                        action.fields["end_y"]?.trim()?.toIntOrNull() ?: end?.second ?: return false

                val durRaw = action.fields["duration"].orEmpty().trim()
                val dur =
                        when {
                            durRaw.endsWith("ms", ignoreCase = true) ->
                                    durRaw.dropLast(2).trim().toLongOrNull()
                            durRaw.endsWith("s", ignoreCase = true) ->
                                    durRaw.dropLast(1).trim().toLongOrNull()?.times(1000)
                            else -> durRaw.toLongOrNull()
                        }
                                ?: 320L
                val sx = (sxRel / 1000.0f) * screenW
                val sy = (syRel / 1000.0f) * screenH
                val ex = (exRel / 1000.0f) * screenW
                val ey = (eyRel / 1000.0f) * screenH
                onLog("执行：Swipe($sxRel,$syRel -> $exRel,$eyRel, ${dur}ms)")
                AutomationOverlay.temporaryHide()
                delay(50)
                val ok = service.swipeAwait(sx, sy, ex, ey, dur)
                AutomationOverlay.restoreVisibility()
                service.awaitWindowEvent(beforeWindowEventTime, timeoutMs = 1600L)
                ok
            }
            else -> false
        }
    }

    private suspend fun awaitIfPaused(control: Control) {
        while (control.isPaused()) {
            delay(220L)
        }
    }

    private fun parsePoint(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val v = raw.trim().removeSurrounding("[", "]")
        val parts = v.split(',').map { it.trim() }
        if (parts.size < 2) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        return x to y
    }

    private fun resolvePackageByLabel(
            service: PhoneAgentAccessibilityService,
            appName: String
    ): String? {
        val target = appName.trim()
        if (target.isBlank()) return null
        val pm = service.packageManager

        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ris = runCatching { pm.queryIntentActivities(query, 0) }.getOrNull().orEmpty()

        fun labelOf(ri: android.content.pm.ResolveInfo): String {
            return runCatching { ri.loadLabel(pm).toString() }.getOrDefault("")
        }

        val exact = ris.firstOrNull { labelOf(it).equals(target, ignoreCase = true) }
        if (exact?.activityInfo != null) return exact.activityInfo.packageName

        val contains = ris.firstOrNull { labelOf(it).contains(target, ignoreCase = true) }
        if (contains?.activityInfo != null) return contains.activityInfo.packageName

        val pkgContains =
                ris.firstOrNull {
                    it.activityInfo?.packageName?.contains(target, ignoreCase = true) == true
                }
        return pkgContains?.activityInfo?.packageName
    }

        private fun looksSensitive(uiDump: String): Boolean {
        val highRisk = listOf(
            "支付密码", "银行卡", "信用卡", "卡号", "cvv", "安全码",
            "验证码", "短信验证码", "otp", "一次性密码", "动态口令",
            "输入密码", "请输入密码", "确认支付", "确认付款"
        )
        return highRisk.any { uiDump.contains(it, ignoreCase = true) }
        }

    private fun estimateTokens(text: String): Int {
        var count = 0
        for (c in text) {
            count += if (c.code > 127) 1 else 1
        }
        return (count * 0.6).toInt().coerceAtLeast(1)
    }

    private fun estimateHistoryTokens(history: List<ChatRequestMessage>): Int {
        var total = 0
        for (msg in history) {
            val content = msg.content
            when (content) {
                is String -> total += estimateTokens(content)
                is List<*> -> {
                    for (item in content) {
                        if (item is Map<*, *>) {
                            val type = item["type"]
                            if (type == "text") {
                                val text = item["text"] as? String ?: ""
                                total += estimateTokens(text)
                            } else if (type == "image_url") {
                                total += 1500
                            }
                        }
                    }
                }
            }
        }
        return total
    }

    private fun trimHistory(history: MutableList<ChatRequestMessage>, maxTokens: Int, maxTurns: Int) {
        if (history.isEmpty()) return
        val systemMsg = history.firstOrNull { it.role == "system" }
        
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

        while (history.size > 2 && estimateHistoryTokens(history) > maxTokens) {
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

        var turns = 0
        var i = history.size - 1
        while (i >= 0 && turns < maxTurns * 2) {
            if (history[i].role != "system") turns++
            i--
        }
        val keepFrom = (i + 1).coerceAtLeast(if (systemMsg != null) 1 else 0)
        while (history.size > keepFrom + turns) {
            val idx = history.indexOfFirst { it.role != "system" }
            if (idx < 0 || idx >= history.size - turns) break
            history.removeAt(idx)
        }
    }

    private fun truncateUiTree(uiDump: String, maxChars: Int): String {
        if (uiDump.length <= maxChars) return uiDump
        
        val headSize = (maxChars * 0.6).toInt()
        val tailSize = maxChars - headSize - 50
        
        return uiDump.take(headSize) + 
               "\n... [UI树已截断，共${uiDump.length}字符] ...\n" + 
               uiDump.takeLast(tailSize.coerceAtLeast(100))
    }
}
