package com.ai.phoneagent.core.platform

import com.ai.phoneagent.PhoneAgentAccessibilityService

/**
 * 设备控制器接口 - 平台抽象层
 * 
 * 解耦 Android AccessibilityService 依赖，提高可测试性
 */
interface DeviceController {
    
    /**
     * 获取当前应用包名
     */
    fun getCurrentAppPackage(): String
    
    /**
     * 尝试截取截图
     */
    suspend fun tryCaptureScreenshot(): PhoneAgentAccessibilityService.ScreenshotData?
    
    /**
     * 获取UI树
     */
    fun dumpUiTree(maxNodes: Int = 30, detail: String = "summary"): String
    
    /**
     * 点击坐标
     */
    suspend fun click(x: Float, y: Float, durationMs: Long = 60L): Boolean
    
    /**
     * 滑动
     */
    suspend fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300L
    ): Boolean
    
    /**
     * 设置输入框文本
     */
    fun setTextOnFocused(text: String): Boolean
    
    /**
     * 在指定元素上设置文本
     */
    suspend fun setTextOnElement(
        text: String,
        resourceId: String? = null,
        elementText: String? = null,
        contentDesc: String? = null,
        className: String? = null,
        index: Int = 0
    ): Boolean
    
    /**
     * 点击元素
     */
    suspend fun clickElement(
        resourceId: String? = null,
        text: String? = null,
        contentDesc: String? = null,
        className: String? = null,
        index: Int = 0
    ): Boolean
    
    /**
     * 查找并点击第一个可编辑元素
     */
    suspend fun clickFirstEditableElement(): Boolean
    
    /**
     * 执行全局返回
     */
    fun performGlobalBack(): Boolean
    
    /**
     * 执行全局Home
     */
    fun performGlobalHome(): Boolean
    
    /**
     * 获取包管理器
     */
    fun getPackageManager(): android.content.pm.PackageManager
    
    /**
     * 获取最后一次窗口事件时间
     */
    fun getLastWindowEventTime(): Long
    
    /**
     * 等待窗口事件
     */
    suspend fun awaitWindowEvent(afterTimeMs: Long, timeoutMs: Long = 1500L): Boolean
}

/**
 * 设备控制器实现 - Android AccessibilityService 适配器
 */
class AccessibilityDeviceController(
    private val service: PhoneAgentAccessibilityService
) : DeviceController {
    
    override fun getCurrentAppPackage(): String = service.currentAppPackage()
    
    override suspend fun tryCaptureScreenshot(): PhoneAgentAccessibilityService.ScreenshotData? = 
        service.tryCaptureScreenshotBase64()
    
    override fun dumpUiTree(maxNodes: Int, detail: String): String =
        service.dumpUiTree(maxNodes)
    
    override suspend fun click(x: Float, y: Float, durationMs: Long): Boolean = 
        service.clickAwait(x, y, durationMs)
    
    override suspend fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ): Boolean = service.swipeAwait(startX, startY, endX, endY, durationMs)
    
    override fun setTextOnFocused(text: String): Boolean = 
        service.setTextOnFocused(text)
    
    override suspend fun setTextOnElement(
        text: String,
        resourceId: String?,
        elementText: String?,
        contentDesc: String?,
        className: String?,
        index: Int
    ): Boolean = service.setTextOnElement(
        text = text,
        resourceId = resourceId,
        elementText = elementText,
        contentDesc = contentDesc,
        className = className,
        index = index
    )
    
    override suspend fun clickElement(
        resourceId: String?,
        text: String?,
        contentDesc: String?,
        className: String?,
        index: Int
    ): Boolean = service.clickElement(
        resourceId = resourceId,
        text = text,
        contentDesc = contentDesc,
        className = className,
        index = index
    )
    
    override suspend fun clickFirstEditableElement(): Boolean = 
        service.clickFirstEditableElement()
    
    override fun performGlobalBack(): Boolean = service.performGlobalBack()
    
    override fun performGlobalHome(): Boolean = service.performGlobalHome()
    
    override fun getPackageManager(): android.content.pm.PackageManager = 
        service.packageManager
    
    override fun getLastWindowEventTime(): Long = service.lastWindowEventTime()
    
    override suspend fun awaitWindowEvent(afterTimeMs: Long, timeoutMs: Long): Boolean = 
        service.awaitWindowEvent(afterTimeMs, timeoutMs)
}

