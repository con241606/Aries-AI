package com.ai.phoneagent.core.executor

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import com.ai.phoneagent.LaunchProxyActivity
import com.ai.phoneagent.PhoneAgentAccessibilityService
import com.ai.phoneagent.core.agent.ParsedAgentAction
import com.ai.phoneagent.core.config.AgentConfiguration
import com.ai.phoneagent.core.utils.ActionUtils
import kotlinx.coroutines.delay

/**
 * 动作执行器 - 单一职责
 * 
 * 负责执行所有类型的Agent动作
 * 原逻辑来自 UiAutomationAgent.kt 的 execute 方法
 */
class ActionExecutor(
    private val config: AgentConfiguration = AgentConfiguration.DEFAULT
) {
    // ActionUtils 是 object 单例，直接使用

    /**
     * 执行动作
     */
    suspend fun execute(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        uiDump: String,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        val rawName = action.actionName ?: return false
        val name = rawName.trim().trim('"', '\'', ' ').lowercase()
        val nameKey = name.replace(" ", "")

        return when (nameKey) {
            "launch", "open_app", "start_app" -> executeLaunch(action, service, onLog)
            "back" -> executeBack(service, onLog)
            "home" -> executeHome(service, onLog)
            "wait", "sleep" -> executeWait(action, onLog)
            "type", "input", "text" -> executeType(action, service, uiDump, screenW, screenH, onLog)
            "tap", "click", "press" -> executeTap(action, service, uiDump, screenW, screenH, onLog)
            "longpress", "long_press" -> executeLongPress(action, service, screenW, screenH, onLog)
            "doubletap", "double_tap" -> executeDoubleTap(action, service, screenW, screenH, onLog)
            "swipe", "scroll" -> executeSwipe(action, service, screenW, screenH, onLog)
            else -> false
        }
    }

    private suspend fun executeLaunch(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        onLog: (String) -> Unit
    ): Boolean {
        val rawTarget = action.fields["package"]
            ?: action.fields["package_name"]
            ?: action.fields["pkg"]
            ?: action.fields["app"]
            ?: ""
        val t = rawTarget.trim().trim('"', '\'', ' ')
        if (t.isBlank()) return false

        val pm = service.packageManager
        val beforeTime = service.lastWindowEventTime()

        // 检查应用是否已安装
        fun isInstalled(pkgName: String): Boolean {
            return runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, 0)
                true
            }.getOrDefault(false)
        }

        // 构建启动Intent
        fun buildLaunchIntent(pkgName: String): android.content.Intent? {
            val direct = pm.getLaunchIntentForPackage(pkgName)
            if (direct != null) return direct

            val query = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val ri = runCatching { pm.queryIntentActivities(query, 0) }
                .getOrNull()
                ?.firstOrNull { it.activityInfo?.packageName == pkgName }
                ?: return null

            val ai = ri.activityInfo ?: return null
            return android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setClassName(ai.packageName, ai.name)
        }

        // 构建候选包名列表
        val candidates = buildList {
            if (t.contains('.')) add(t)
            com.ai.phoneagent.AppPackageMapping.resolve(t)?.let { add(it) }
            com.ai.phoneagent.core.tools.AppPackageManager.resolvePackageByLabel(service, t)?.let { add(it) }
            if (!t.contains('.')) add(t)
        }.distinct()

        var pkgName = candidates.firstOrNull().orEmpty().ifBlank { t }
        var intent: android.content.Intent? = null

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
            return false
        }

        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
            android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        return try {
            LaunchProxyActivity.launch(service, intent)
            service.awaitWindowEvent(beforeTime, timeoutMs = config.launchAwaitWindowTimeoutMs)
            true
        } catch (e: Exception) {
            onLog("Launch 失败：${e.message.orEmpty()}")
            false
        }
    }

    private suspend fun executeBack(
        service: PhoneAgentAccessibilityService,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("执行：Back")
        val beforeTime = service.lastWindowEventTime()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        service.awaitWindowEvent(beforeTime, timeoutMs = config.backAwaitWindowTimeoutMs)
        return true
    }

    private suspend fun executeHome(
        service: PhoneAgentAccessibilityService,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("执行：Home")
        val beforeTime = service.lastWindowEventTime()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        service.awaitWindowEvent(beforeTime, timeoutMs = config.homeAwaitWindowTimeoutMs)
        return true
    }

    private suspend fun executeWait(
        action: ParsedAgentAction,
        onLog: (String) -> Unit
    ): Boolean {
        val raw = action.fields["duration"].orEmpty().trim()
        val d = when {
            raw.endsWith("ms", ignoreCase = true) -> raw.dropLast(2).trim().toLongOrNull()
            raw.endsWith("s", ignoreCase = true) -> raw.dropLast(1).trim().toLongOrNull()?.times(1000)
            raw.contains("second", ignoreCase = true) -> Regex("""(\d+)""")
                .find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.times(1000)
            else -> raw.toLongOrNull()
        } ?: 600L

        onLog("执行：Wait(${d}ms)")
        delay(d.coerceAtLeast(0L))
        return true
    }

    private suspend fun executeType(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        uiDump: String,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        // 敏感内容检查
        if (ActionUtils.looksSensitive(uiDump, config.sensitiveKeywords)) {
            onLog("检测到支付/验证界面关键词，停止并要求用户接管")
            return false
        }

        val inputText = action.fields["text"].orEmpty()
        val resourceId = action.fields["resourceId"] ?: action.fields["resource_id"]
        val contentDesc = action.fields["contentDesc"] ?: action.fields["content_desc"]
        val className = action.fields["className"] ?: action.fields["class_name"]
        val elementText = action.fields["elementText"]
            ?: action.fields["element_text"]
            ?: action.fields["targetText"]
            ?: action.fields["target_text"]
        val index = action.fields["index"]?.trim()?.toIntOrNull() ?: 0

        // 如果有坐标，先点击
        val element = ActionUtils.parsePoint(action.fields["element"])
            ?: ActionUtils.parsePoint(action.fields["point"])
        if (element != null) {
            val (x, y) = ActionUtils.parsePointToScreen(element, screenW, screenH)
            onLog("执行：先点击输入框(${element.first},${element.second})")
            service.clickAwait(x, y)
            delay(300)
        }

        onLog("执行：Type(${inputText.take(config.logInputTextTruncateLength)})")

        var ok = if (resourceId != null || contentDesc != null || className != null || elementText != null) {
            service.setTextOnElement(
                text = inputText,
                resourceId = resourceId,
                elementText = elementText,
                contentDesc = contentDesc,
                className = className,
                index = index
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

        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.typeAwaitWindowTimeoutMs)
        return ok
    }

    private suspend fun executeTap(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        uiDump: String,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        // 敏感内容检查
        if (ActionUtils.looksSensitive(uiDump, config.sensitiveKeywords)) {
            onLog("检测到支付/验证界面关键词，停止并要求用户接管")
            return false
        }

        val resourceId = action.fields["resourceId"] ?: action.fields["resource_id"]
        val contentDesc = action.fields["contentDesc"] ?: action.fields["content_desc"]
        val className = action.fields["className"] ?: action.fields["class_name"]
        val elementText = action.fields["elementText"]
            ?: action.fields["element_text"]
            ?: action.fields["label"]
        val index = action.fields["index"]?.trim()?.toIntOrNull() ?: 0

        // 优先使用 selector
        val selectorOk = if (resourceId != null || contentDesc != null || className != null || elementText != null) {
            onLog("执行：Tap(selector)")
            service.clickElement(
                resourceId = resourceId,
                text = elementText,
                contentDesc = contentDesc,
                className = className,
                index = index
            )
        } else {
            false
        }

        if (selectorOk) {
            service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.tapAwaitWindowTimeoutMs)
            return true
        }

        // 回退到坐标点击
        val element = ActionUtils.parsePoint(action.fields["element"])
            ?: ActionUtils.parsePoint(action.fields["point"])
            ?: ActionUtils.parsePoint(action.fields["pos"])
        val xRel = action.fields["x"]?.trim()?.toIntOrNull() ?: element?.first ?: return false
        val yRel = action.fields["y"]?.trim()?.toIntOrNull() ?: element?.second ?: return false

        val (x, y) = ActionUtils.parsePointToScreen(xRel to yRel, screenW, screenH)
        onLog("执行：Tap($xRel,$yRel)")
        service.clickAwait(x, y)
        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.tapAwaitWindowTimeoutMs)
        return true
    }

    private suspend fun executeLongPress(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        val element = ActionUtils.parsePoint(action.fields["element"]) ?: return false
        val (x, y) = ActionUtils.parsePointToScreen(element, screenW, screenH)

        onLog("执行：Long Press(${element.first},${element.second})")
        service.clickAwait(x, y, durationMs = config.longPressDurationMs)
        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.tapAwaitWindowTimeoutMs)
        return true
    }

    private suspend fun executeDoubleTap(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        val element = ActionUtils.parsePoint(action.fields["element"]) ?: return false
        val (x, y) = ActionUtils.parsePointToScreen(element, screenW, screenH)

        onLog("执行：Double Tap(${element.first},${element.second})")
        val ok1 = service.clickAwait(x, y, durationMs = config.clickDurationMs)
        delay(config.doubleTapIntervalMs)
        val ok2 = service.clickAwait(x, y, durationMs = config.clickDurationMs)
        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.tapAwaitWindowTimeoutMs)
        return ok1 && ok2
    }

    private suspend fun executeSwipe(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        val start = ActionUtils.parsePoint(action.fields["start"])
        val end = ActionUtils.parsePoint(action.fields["end"])

        val sxRel = action.fields["start_x"]?.trim()?.toIntOrNull() ?: start?.first ?: return false
        val syRel = action.fields["start_y"]?.trim()?.toIntOrNull() ?: start?.second ?: return false
        val exRel = action.fields["end_x"]?.trim()?.toIntOrNull() ?: end?.first ?: return false
        val eyRel = action.fields["end_y"]?.trim()?.toIntOrNull() ?: end?.second ?: return false

        val durRaw = action.fields["duration"].orEmpty().trim()
        val dur = when {
            durRaw.endsWith("ms", ignoreCase = true) -> durRaw.dropLast(2).trim().toLongOrNull()
            durRaw.endsWith("s", ignoreCase = true) -> durRaw.dropLast(1).trim().toLongOrNull()?.times(1000)
            else -> durRaw.toLongOrNull()
        } ?: config.scrollDurationMs

        val (sx, sy) = ActionUtils.parsePointToScreen(sxRel to syRel, screenW, screenH)
        val (ex, ey) = ActionUtils.parsePointToScreen(exRel to eyRel, screenW, screenH)

        onLog("执行：Swipe($sxRel,$syRel -> $exRel,$eyRel, ${dur}ms)")
        service.swipeAwait(sx, sy, ex, ey, dur)
        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.swipeAwaitWindowTimeoutMs)
        return true
    }
}

