package com.ai.phoneagent.core.cache

import com.ai.phoneagent.PhoneAgentAccessibilityService
import com.ai.phoneagent.core.config.AgentConfiguration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 截图优化管理器 - 整合缓存+节流+压缩
 * 
 * 提供统一的截图获取接口，集成缓存和节流功能
 * 保持原有性能优化逻辑
 */
class ScreenshotManager(
    private val config: AgentConfiguration = AgentConfiguration.DEFAULT
) {
    private val cache = ScreenshotCache(
        maxSize = config.screenshotCacheMaxSize,
        ttlMs = config.screenshotCacheTtlMs
    )
    private val throttler = ScreenshotThrottler(
        minIntervalMs = config.screenshotThrottleMinIntervalMs
    )
    private val mutex = Mutex()
    
    /**
     * 优化的截图获取方法
     * 1. 检查节流器，防止频繁截图
     * 2. 检查缓存，避免重复截图
     * 3. 执行截图并压缩优化
     */
    suspend fun getOptimizedScreenshot(
        service: PhoneAgentAccessibilityService
    ): PhoneAgentAccessibilityService.ScreenshotData? {
        // 检查节流器
        if (!throttler.canTakeScreenshot()) {
            val remainingWait = throttler.getRemainingWaitTime()
            if (remainingWait > 0 && config.enableScreenshotCache) {
                // 尝试从缓存获取
                val cached = getFromCache(service)
                if (cached != null) return cached
            }
            return null
        }
        
        // 检查缓存
        if (config.enableScreenshotCache) {
            val cached = getFromCache(service)
            if (cached != null) {
                throttler.reset() // 使用缓存时也重置节流时间
                return cached
            }
        }
        
        // 执行截图
        val screenshot = service.tryCaptureScreenshotBase64()
        if (screenshot != null && config.enableScreenshotCache) {
            putToCache(service, screenshot)
            cache.evictExpired()
        }
        
        return screenshot
    }
    
    /**
     * 从缓存获取截图
     */
    private fun getFromCache(service: PhoneAgentAccessibilityService): PhoneAgentAccessibilityService.ScreenshotData? {
        if (!config.enableScreenshotCache) return null
        
        val currentApp = service.currentAppPackage()
        val windowEventTime = service.lastWindowEventTime()
        val cacheKey = cache.generateKey(currentApp, windowEventTime)
        
        @Suppress("UNCHECKED_CAST")
        return cache.get(cacheKey) as? PhoneAgentAccessibilityService.ScreenshotData
    }
    
    /**
     * 存储截图到缓存
     */
    private fun putToCache(
        service: PhoneAgentAccessibilityService,
        screenshot: PhoneAgentAccessibilityService.ScreenshotData
    ) {
        if (!config.enableScreenshotCache) return
        
        val currentApp = service.currentAppPackage()
        val windowEventTime = service.lastWindowEventTime()
        val cacheKey = cache.generateKey(currentApp, windowEventTime)
        
        cache.put(cacheKey, screenshot)
    }
    
    /**
     * 清理截图缓存（在任务开始/结束时调用）
     */
    suspend fun clear() {
        mutex.withLock {
            if (config.enableScreenshotCache) {
                cache.clear()
            }
            if (config.enableScreenshotThrottle) {
                throttler.reset()
            }
        }
    }
    
    /**
     * 获取缓存状态
     */
    fun getCacheStatus(): Map<String, Any> {
        return mapOf(
            "cacheStats" to cache.getStats(),
            "throttleStatus" to throttler.getStatus(),
            "config" to mapOf(
                "cacheEnabled" to config.enableScreenshotCache,
                "throttleEnabled" to config.enableScreenshotThrottle
            )
        )
    }
}

