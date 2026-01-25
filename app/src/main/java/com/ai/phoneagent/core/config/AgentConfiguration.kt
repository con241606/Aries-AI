package com.ai.phoneagent.core.config

/**
 * Agent配置 - 统一的配置管理中心
 * 
 * 整合了原有分散的Config类，提供完整的配置管理
 * 所有参数都有合理的默认值，可按需覆盖
 */
data class AgentConfiguration(
    // ========== 执行参数 ==========
    /** 最大执行步数 */
    val maxSteps: Int = 100,
    
    /** 每步之间的基础延迟 (ms) */
    val stepDelayMs: Long = 160L,
    
    /** 执行动作后的额外等待时间 (ms) */
    val postActionDelayMs: Long = 120L,
    
    // ========== 模型调用参数 ==========
    /** 模型调用最大重试次数 */
    val maxModelRetries: Int = 3,
    
    /** 重试基础延迟 (ms) */
    val modelRetryBaseDelayMs: Long = 700L,
    
    /** 解析修复最大次数 */
    val maxParseRepairs: Int = 2,
    
    /** 动作执行修复最大次数 */
    val maxActionRepairs: Int = 1,
    
    // ========== 模型参数 ==========
    /** 温度参数 */
    val temperature: Float? = 0.0f,
    
    /** top_p参数 */
    val topP: Float? = 0.85f,
    
    /** 频率惩罚 */
    val frequencyPenalty: Float? = 0.2f,
    
    /** 最大token数 */
    val maxTokens: Int? = 3000,
    
    // ========== 上下文管理参数 ==========
    /** 最大上下文token数 */
    val maxContextTokens: Int = 20000,
    
    /** UI树最大字符数 */
    val maxUiTreeChars: Int = 3000,
    
    /** 最多保留对话轮数 */
    val maxHistoryTurns: Int = 6,
    
    // ========== 性能优化参数 ==========
    val useStreamingWithEarlyStop: Boolean = true,
    val parallelScreenshotAndUi: Boolean = true,
    
    // ========== 截图优化参数 ==========
    val enableScreenshotCache: Boolean = true,
    val enableScreenshotThrottle: Boolean = true,
    val screenshotCompressionQuality: Int = 85,
    val screenshotMaxSizeKB: Int = 150,
    val screenshotCacheMaxSize: Int = 3,
    val screenshotCacheTtlMs: Long = 2000L,
    val screenshotThrottleMinIntervalMs: Long = 1100L,
    
    // ========== 动作延迟参数 ==========
    val launchActionDelayMs: Long = 1050L,
    val typeActionDelayMs: Long = 260L,
    val tapActionDelayMs: Long = 320L,
    val swipeActionDelayMs: Long = 420L,
    val backActionDelayMs: Long = 220L,
    val homeActionDelayMs: Long = 420L,
    val waitActionDelayMs: Long = 650L,
    val defaultActionDelayMs: Long = 240L,
    
    // ========== 窗口事件等待参数 ==========
    val launchAwaitWindowTimeoutMs: Long = 2200L,
    val backAwaitWindowTimeoutMs: Long = 1400L,
    val homeAwaitWindowTimeoutMs: Long = 1800L,
    val tapAwaitWindowTimeoutMs: Long = 1400L,
    val swipeAwaitWindowTimeoutMs: Long = 1600L,
    val typeAwaitWindowTimeoutMs: Long = 1200L,
    val defaultAwaitWindowTimeoutMs: Long = 1500L,
    
    // ========== UI树参数 ==========
    val uiTreeMaxNodes: Int = 30,
    
    // ========== Tap+Type合并执行参数 ==========
    val tapTypeCombineKeyboardWaitMs: Long = 400L,
    val tapTypeCombineSecondSetTextWaitMs: Long = 250L,
    val tapTypeCombineStrategy3WaitMs: Long = 200L,
    
    // ========== UI树截断参数 ==========
    val uiTreeTruncateHeadRatio: Float = 0.6f,
    val uiTreeTruncateMinTailLength: Int = 100,
    
    // ========== 敏感内容检测 ==========
    val sensitiveKeywords: List<String> = listOf(
        "支付密码", "银行卡", "信用卡", "卡号", "cvv", "安全码",
        "验证码", "短信验证码", "otp", "一次性密码", "动态口令",
        "输入密码", "请输入密码", "确认支付", "确认付款"
    ),
    
    // ========== 应用启动参数 ==========
    val appLaunchWaitTimeoutMs: Long = 2200L,
    val appLaunchExtraDelayMs: Long = 500L,
    
    // ========== 流式思考参数 ==========
    val thinkingDisplayKeywords: List<String> = listOf(
        "需要", "应该", "点击", "输入", "滚动", "等待", "打开", "找到", "看到",
        "验证", "检查", "分析", "确认", "选择", "搜索", "返回", "关闭", "长按"
    ),
    val thinkingExtractMaxLength: Int = 30,
    
    // ========== 预估步骤计算参数 ==========
    val estimatedStepsBaseSteps: Int = 15,
    val estimatedStepsFastGrowthThreshold: Float = 0.3f,
    val estimatedStepsMediumGrowthThreshold: Float = 0.6f,
    
    // ========== Token估算参数 ==========
    val imageTokenEstimate: Int = 1500,
    val tokenEstimateMultiplier: Float = 0.6f,
    
    // ========== 日志截断参数 ==========
    val logStepTruncateLength: Int = 240,
    val logThinkingTruncateLength: Int = 180,
    val logAnswerTruncateLength: Int = 220,
    val subtitleMaxLength: Int = 34,
    val logInputTextTruncateLength: Int = 40,
    val logCombineInputTextTruncateLength: Int = 20,
    val confirmMessageMaxLength: Int = 320,
    
    // ========== 元素查找参数 ==========
    val elementFindMaxNodes: Int = 2000,
    val elementFindGuardValue: Int = 600,
    
    // ========== UI树属性最大长度 ==========
    val uiTreeAttrMaxLenMinimal: Int = 40,
    val uiTreeAttrMaxLenSummary: Int = 80,
    val uiTreeAttrMaxLenFull: Int = 140,
    val uiTreeAttrMaxLenDefault: Int = 120,
    
    // ========== 滚动参数 ==========
    val scrollDistanceRatio: Float = 0.3f,
    val scrollDurationMs: Long = 400L,
    val scrollWaitDelayMs: Long = 500L,
    val defaultScrollDirection: String = "down",
    
    // ========== 点击参数 ==========
    val clickDurationMs: Long = 60L,
    val longPressDurationMs: Long = 520L,
    val doubleTapIntervalMs: Long = 90L,
    
    // ========== 等待参数 ==========
    val pauseCheckIntervalMs: Long = 220L,
    val windowEventPollIntervalMs: Long = 60L,
    
    // ========== 截图参数 ==========
    val screenshotScalePercent: Int = 75,
    val screenshotQuality: Int = 85,
    val screenshotOverlayHideDelayMs: Long = 80L,
    
    // ========== 危险操作关键词 ==========
    val dangerousOperationKeywords: List<String> = listOf(
        "支付", "密码", "银行卡", "信用卡", "cvv", "安全码",
        "验证码", "确认支付", "确认付款"
    )
) {
    companion object {
        val DEFAULT = AgentConfiguration()
        val TEST = AgentConfiguration(
            maxSteps = 10,
            stepDelayMs = 50L,
            maxModelRetries = 1,
            maxParseRepairs = 1,
            screenshotThrottleMinIntervalMs = 500L,
            screenshotCacheTtlMs = 1000L,
        )
    }
    
    fun getActionDelayMs(actionName: String): Long {
        val normalized = actionName.replace(" ", "").lowercase()
        return when (normalized) {
            "launch", "open_app", "start_app" -> launchActionDelayMs
            "type", "input", "text", "type_name" -> typeActionDelayMs
            "tap", "click", "press", "doubletap", "double_tap", "longpress", "long_press" -> tapActionDelayMs
            "swipe", "scroll" -> swipeActionDelayMs
            "back" -> backActionDelayMs
            "home" -> homeActionDelayMs
            "wait" -> waitActionDelayMs
            else -> defaultActionDelayMs
        }
    }
    
    fun getAwaitWindowTimeoutMs(actionName: String): Long {
        val normalized = actionName.replace(" ", "").lowercase()
        return when (normalized) {
            "launch", "open_app", "start_app" -> launchAwaitWindowTimeoutMs
            "back" -> backAwaitWindowTimeoutMs
            "home" -> homeAwaitWindowTimeoutMs
            "tap", "click", "press", "longpress", "long_press", "doubletap", "double_tap" -> tapAwaitWindowTimeoutMs
            "swipe", "scroll" -> swipeAwaitWindowTimeoutMs
            "type", "input", "text" -> typeAwaitWindowTimeoutMs
            else -> defaultAwaitWindowTimeoutMs
        }
    }
    
    fun isDangerousKeyword(text: String): Boolean {
        return dangerousOperationKeywords.any { text.contains(it, ignoreCase = true) }
    }
}

