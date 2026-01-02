package com.ai.phoneagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ai.phoneagent.net.AutoGlmClient
import com.ai.phoneagent.net.ChatRequestMessage
import kotlinx.coroutines.*

/**
 * 悬浮聊天窗口服务
 * 参考 Operit 的实现，提供小窗模式的聊天界面
 */
class FloatingChatService : Service() {

    companion object {
        private const val TAG = "FloatingChatService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "floating_chat_channel"
        
        @Volatile
        private var instance: FloatingChatService? = null
        
        fun getInstance(): FloatingChatService? = instance
        
        fun isRunning(): Boolean = instance != null
        
        /**
         * 检查是否有悬浮窗权限
         */
        fun hasOverlayPermission(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }
        
        /**
         * 启动悬浮窗服务
         */
        fun start(
            context: Context, 
            messages: ArrayList<String>? = null,
            fromX: Float = 100f,
            fromY: Float = 200f,
            fromWidth: Float = 320f,
            fromHeight: Float = 400f
        ) {
            if (!hasOverlayPermission(context)) return
            val intent = Intent(context, FloatingChatService::class.java).apply {
                messages?.let { putStringArrayListExtra("messages", it) }
                putExtra("from_x", fromX)
                putExtra("from_y", fromY)
                putExtra("from_width", fromWidth)
                putExtra("from_height", fromHeight)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * 停止悬浮窗服务
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingChatService::class.java))
        }
        
        /**
         * 消息同步监听器接口
         */
        interface MessageSyncListener {
            fun onMessageAdded(message: String, isUser: Boolean)
            fun onMessagesCleared()
        }
        
        // 消息同步监听器（用于与 MainActivity 同步）
        private var messageSyncListener: MessageSyncListener? = null
        
        // 待同步消息队列（当主界面不在线时暂存）
        private val pendingSyncMessages = mutableListOf<Pair<String, Boolean>>()
        
        fun setMessageSyncListener(listener: MessageSyncListener?) {
            messageSyncListener = listener
            // 当设置新的监听器时，发送所有待同步的消息
            if (listener != null && pendingSyncMessages.isNotEmpty()) {
                pendingSyncMessages.forEach { (message, isUser) ->
                    listener.onMessageAdded(message, isUser)
                }
                pendingSyncMessages.clear()
            }
        }
        
        fun getMessageSyncListener(): MessageSyncListener? = messageSyncListener
    }

    private val binder = LocalBinder()
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var floatingView: View? = null
    private var isViewAdded = false
    
    // 协程作用域
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 窗口状态
    private var windowX = 100
    private var windowY = 200
    private var windowWidth = 280 // dp - 更小的尺寸
    private var windowHeight = 360 // dp - 更小的尺寸
    
    // 窗口参数
    private var layoutParams: WindowManager.LayoutParams? = null
    
    // 消息列表
    private val messages = mutableListOf<String>()
    private val chatHistory = mutableListOf<ChatRequestMessage>()  // 用于 AI 对话上下文
    
    // 回调
    var onExpandToFullScreen: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    
    // API Key
    private var apiKey: String = ""
    
    inner class LocalBinder : Binder() {
        fun getService(): FloatingChatService = this@FloatingChatService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("floating_chat_prefs", Context.MODE_PRIVATE)
        
        // 获取 API Key（从主应用的 SharedPreferences）
        val appPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        apiKey = appPrefs.getString("api_key", "") ?: ""
        
        restoreWindowState()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 获取传入的消息
        intent?.getStringArrayListExtra("messages")?.let {
            messages.clear()
            messages.addAll(it)
            // 从消息中恢复聊天历史
            restoreChatHistory(it)
        } ?: run {
            // 如果没有传入消息，从本地存储恢复
            restoreMessagesFromPrefs()
            if (messages.isNotEmpty()) {
                restoreChatHistory(messages)
            }
        }
        
        // 获取初始位置和尺寸
        intent?.let {
            val density = resources.displayMetrics.density
            windowX = it.getFloatExtra("from_x", 100f).toInt()
            windowY = it.getFloatExtra("from_y", 200f).toInt()
            windowWidth = (it.getFloatExtra("from_width", 320f * density) / density).toInt()
            windowHeight = (it.getFloatExtra("from_height", 400f * density) / density).toInt()
        }
        
        // 显示悬浮窗
        showFloatingWindow()
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()  // 取消协程
        hideFloatingWindow()
        saveWindowState()
        saveMessagesToPrefs()  // 保存消息到本地存储
    }
    
    /**
     * 从消息列表恢复聊天历史
     */
    private fun restoreChatHistory(messageList: List<String>) {
        chatHistory.clear()
        for (msg in messageList) {
            // 支持"我:"和"我: "两种格式
            val isUser = msg.startsWith("我:") || msg.startsWith("我: ")
            val content = msg.removePrefix("我: ").removePrefix("我:").removePrefix("AI: ").removePrefix("AI:").trim()
            if (content.isNotBlank()) {
                chatHistory.add(ChatRequestMessage(
                    role = if (isUser) "user" else "assistant",
                    content = content
                ))
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮聊天窗口",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Phone Agent 悬浮窗口服务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Agent")
            .setContentText("小窗模式运行中")
            .setSmallIcon(R.drawable.ic_floating_window_24)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun showFloatingWindow() {
        if (isViewAdded) return
        if (!Settings.canDrawOverlays(this)) return
        
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.floating_chat_window, null)
        
        val density = resources.displayMetrics.density
        val widthPx = (windowWidth * density).toInt()
        val heightPx = (windowHeight * density).toInt()
        
        layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = windowX
            y = windowY
            // 移除 FLAG_LAYOUT_NO_LIMITS 避免阴影溢出问题
        }
        
        setupFloatingView()
        
        windowManager.addView(floatingView, layoutParams)
        isViewAdded = true
        
        // 入场动画
        floatingView?.apply {
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()
        }
    }
    
    private fun setupFloatingView() {
        val view = floatingView ?: return
        
        // 标题栏拖动
        val titleBar = view.findViewById<View>(R.id.floatingTitleBar)
        setupDragBehavior(titleBar)
        
        // 全屏按钮
        val btnFullscreen = view.findViewById<ImageButton>(R.id.btnFullscreen)
        btnFullscreen.setOnClickListener {
            expandToFullScreen()
        }
        
        // 返回主页按钮
        val btnHome = view.findViewById<ImageButton>(R.id.btnHome)
        btnHome.setOnClickListener {
            expandToFullScreen()
        }
        
        // 关闭按钮
        val btnClose = view.findViewById<ImageButton>(R.id.btnClose)
        btnClose.setOnClickListener {
            closeWindow()
        }
        
        // 输入框 - 优化键盘呼出响应速度
        val inputMessage = view.findViewById<EditText>(R.id.inputMessage)
        
        // 预先设置为可聚焦模式，避免延迟
        inputMessage.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // 立即切换为可聚焦模式
                setFocusable(true)
            }
            false  // 继续传递事件
        }
        
        inputMessage.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // 确保键盘弹出
                mainHandler.postDelayed({
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(inputMessage, InputMethodManager.SHOW_IMPLICIT)
                }, 50)
            }
        }
        
        // 发送按钮
        val btnSend = view.findViewById<ImageButton>(R.id.btnSend)
        btnSend.setOnClickListener {
            val text = inputMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendUserMessage(text, inputMessage)
            }
        }
        
        // 监听回车键发送
        inputMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                val text = inputMessage.text.toString().trim()
                if (text.isNotEmpty()) {
                    sendUserMessage(text, inputMessage)
                }
                true
            } else false
        }
        
        // 更新消息列表
        updateMessagesUI()
    }
    
    /**
     * 发送用户消息并获取 AI 回复
     */
    private fun sendUserMessage(text: String, inputMessage: EditText) {
        // 添加用户消息
        addMessage("我: $text", isUser = true)
        inputMessage.setText("")
        
        // 隐藏键盘
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputMessage.windowToken, 0)
        
        // 延迟恢复不可聚焦状态（给键盘隐藏一些时间）
        mainHandler.postDelayed({
            setFocusable(false)
        }, 100)
        
        // 显示"思考中..."
        addMessage("AI: 思考中...", isUser = false, isThinking = true)
        
        // 发送 AI 请求
        requestAIResponse(text)
    }
    
    /**
     * 请求 AI 回复
     */
    private fun requestAIResponse(userText: String) {
        if (apiKey.isBlank()) {
            // 移除思考中消息，显示错误
            removeThinkingMessage()
            addMessage("AI: 请在主界面配置 API Key", isUser = false)
            return
        }
        
        // 添加到聊天历史
        chatHistory.add(ChatRequestMessage(role = "user", content = userText))
        
        serviceScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    AutoGlmClient.sendChat(
                        apiKey = apiKey,
                        messages = chatHistory
                    )
                }
                
                // 移除思考中消息
                removeThinkingMessage()
                
                if (response != null) {
                    // 添加 AI 回复
                    addMessage("AI: $response", isUser = false)
                    chatHistory.add(ChatRequestMessage(role = "assistant", content = response))
                } else {
                    addMessage("AI: 抱歉，我无法回复您的消息", isUser = false)
                }
            } catch (e: Exception) {
                removeThinkingMessage()
                addMessage("AI: 请求失败: ${e.message?.take(50)}", isUser = false)
            }
        }
    }
    
    /**
     * 移除"思考中..."消息
     */
    private fun removeThinkingMessage() {
        val iterator = messages.iterator()
        while (iterator.hasNext()) {
            val msg = iterator.next()
            if (msg == "AI: 思考中...") {
                iterator.remove()
                break
            }
        }
    }
    
    /**
     * 设置悬浮窗是否可聚焦（用于键盘输入）
     */
    private fun setFocusable(focusable: Boolean) {
        val params = layoutParams ?: return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        if (isViewAdded) {
            windowManager.updateViewLayout(floatingView, params)
        }
    }
    
    private fun setupDragBehavior(dragHandle: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        
        dragHandle.setOnTouchListener { _, event ->
            val params = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    windowX = params.x
                    windowY = params.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveWindowState()
                    true
                }
                else -> false
            }
        }
    }
    
    private fun updateMessagesUI() {
        val container = floatingView?.findViewById<LinearLayout>(R.id.messagesContainer) ?: return
        container.removeAllViews()
        
        for (msg in messages) {
            // 支持“我:”和“我: ”两种格式
            val isUser = msg.startsWith("我:") || msg.startsWith("我: ")
            val isThinking = msg == "AI: 思考中..." || msg == "AI:思考中..."
            
            val textView = TextView(this).apply {
                text = msg
                textSize = 14f
                setTextColor(if (isThinking) 0xFF999999.toInt() else 0xFF333333.toInt())
                setPadding(16, 8, 16, 8)
                if (isThinking) {
                    setTypeface(null, android.graphics.Typeface.ITALIC)
                }
            }
            container.addView(textView)
        }
        
        // 滚动到底部
        floatingView?.findViewById<ScrollView>(R.id.scrollArea)?.post {
            floatingView?.findViewById<ScrollView>(R.id.scrollArea)?.fullScroll(View.FOCUS_DOWN)
        }
    }
    
    /**
     * 添加消息到悬浮窗（带同步功能）
     * @param message 消息内容
     * @param isUser 是否是用户消息
     * @param isThinking 是否是"思考中"消息（不同步）
     */
    fun addMessage(message: String, isUser: Boolean = false, isThinking: Boolean = false) {
        messages.add(message)
        updateMessagesUI()
        
        // 持久化消息到 SharedPreferences
        saveMessagesToPrefs()
        
        // 同步消息到主界面（不同步"思考中"消息）
        if (!isThinking) {
            val listener = messageSyncListener
            if (listener != null) {
                // 监听器存在时直接同步
                listener.onMessageAdded(message, isUser)
            } else {
                // 监听器不存在时加入待同步队列
                pendingSyncMessages.add(Pair(message, isUser))
            }
        }
    }
    
    /**
     * 保存消息到本地存储
     */
    private fun saveMessagesToPrefs() {
        try {
            val json = com.google.gson.Gson().toJson(messages)
            prefs.edit()
                .putString("floating_messages", json)
                .putLong("floating_messages_updated_at", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            // ignore
        }
    }
    
    /**
     * 从本地存储恢复消息
     */
    private fun restoreMessagesFromPrefs() {
        try {
            val json = prefs.getString("floating_messages", null) ?: return
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            val list: List<String> = com.google.gson.Gson().fromJson(json, type) ?: emptyList()
            messages.clear()
            messages.addAll(list)
        } catch (e: Exception) {
            // ignore
        }
    }
    
    /**
     * 获取当前所有消息（用于展开到全屏时同步）
     */
    fun getMessages(): List<String> = messages.toList()
    
    /**
     * 获取聊天历史（用于 AI 对话上下文）
     */
    fun getChatHistory(): List<ChatRequestMessage> = chatHistory.toList()
    
    /**
     * 展开到全屏（返回主 Activity）
     */
    fun expandToFullScreen() {
        val density = resources.displayMetrics.density
        val currentX = windowX
        val currentY = windowY
        val currentWidth = (windowWidth * density).toInt()
        val currentHeight = (windowHeight * density).toInt()
        
        // 退出动画
        floatingView?.animate()
            ?.alpha(0f)
            ?.scaleX(1.5f)
            ?.scaleY(1.5f)
            ?.setDuration(250)
            ?.setInterpolator(DecelerateInterpolator())
            ?.withEndAction {
                onExpandToFullScreen?.invoke()
                // 启动 MainActivity，传递小窗位置用于展开动画
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("from_floating", true)
                    putExtra("from_x", currentX)
                    putExtra("from_y", currentY)
                    putExtra("from_width", currentWidth)
                    putExtra("from_height", currentHeight)
                }
                startActivity(intent)
                stopSelf()
            }
            ?.start()
    }
    
    /**
     * 关闭悬浮窗
     */
    fun closeWindow() {
        // 关闭动画
        floatingView?.animate()
            ?.alpha(0f)
            ?.scaleX(0.3f)
            ?.scaleY(0.3f)
            ?.setDuration(200)
            ?.setInterpolator(DecelerateInterpolator())
            ?.withEndAction {
                onClose?.invoke()
                stopSelf()
            }
            ?.start()
    }
    
    private fun hideFloatingWindow() {
        if (isViewAdded && floatingView != null) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                // ignore
            }
            floatingView = null
            isViewAdded = false
        }
    }
    
    private fun saveWindowState() {
        prefs.edit()
            .putInt("window_x", windowX)
            .putInt("window_y", windowY)
            .putInt("window_width", windowWidth)
            .putInt("window_height", windowHeight)
            .apply()
    }
    
    private fun restoreWindowState() {
        windowX = prefs.getInt("window_x", 100)
        windowY = prefs.getInt("window_y", 200)
        windowWidth = prefs.getInt("window_width", 320)
        windowHeight = prefs.getInt("window_height", 400)
    }
}
