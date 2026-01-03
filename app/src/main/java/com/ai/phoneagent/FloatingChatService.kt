package com.ai.phoneagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
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
        private const val OPEN_APP_PI_REQUEST_CODE = 1002
        private const val LAUNCH_PROXY_EXTRA_TARGET_INTENT = "target_intent"

        const val ACTION_FLOATING_RETURNED = "com.ai.phoneagent.action.FLOATING_RETURNED"
        
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
            fromHeight: Float = 400f,
            showDelayMs: Long = 80L,
        ) {
            if (!hasOverlayPermission(context)) return
            val intent = Intent(context, FloatingChatService::class.java).apply {
                messages?.let { putStringArrayListExtra("messages", it) }
                putExtra("from_x", fromX)
                putExtra("from_y", fromY)
                putExtra("from_width", fromWidth)
                putExtra("from_height", fromHeight)
                putExtra("show_delay_ms", showDelayMs)
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

    private var targetX: Int = 100
    private var targetY: Int = 200
    private var showDelayMs: Long = 0L
    
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

    private var awaitingReturnAck: Boolean = false

    private var lastOpenAppAttemptAt: Long = 0L

    private var overlayHiddenForReturn: Boolean = false

    private var overlayTouchBlockedForReturn: Boolean = false

    private val returnAckReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_FLOATING_RETURNED) return
                if (!awaitingReturnAck) return
                awaitingReturnAck = false
                stopSelf()
            }
        }
    
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

        val filter = IntentFilter(ACTION_FLOATING_RETURNED)
        ContextCompat.registerReceiver(
            this,
            returnAckReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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

            targetX = it.getFloatExtra("from_x", 100f).toInt()
            targetY = it.getFloatExtra("from_y", 200f).toInt()
            windowWidth = (it.getFloatExtra("from_width", 320f * density) / density).toInt()
            windowHeight = (it.getFloatExtra("from_height", 400f * density) / density).toInt()

            showDelayMs = it.getLongExtra("show_delay_ms", 0L)
        }
        
        // 显示悬浮窗
        if (showDelayMs > 0) {
            mainHandler.postDelayed({ showFloatingWindow() }, showDelayMs)
        } else {
            showFloatingWindow()
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        runCatching { unregisterReceiver(returnAckReceiver) }
        serviceScope.cancel()  // 取消协程
        hideFloatingWindow()
        saveWindowState()
        saveMessagesToPrefs()  // 保存消息到本地存储
    }

    private fun scheduleStopAfterReturnTimeout() {
        val self = this
        mainHandler.postDelayed(
            {
                if (instance == self && awaitingReturnAck) {
                    awaitingReturnAck = false
                    restoreOverlayAfterFailedReturn()
                }
            },
            6000L
        )
    }

    private fun scheduleRetryOpenAppWhileWaitingAck() {
        val self = this
        val delays = longArrayOf(900L, 1700L, 2600L)
        for (d in delays) {
            mainHandler.postDelayed(
                {
                    if (instance == self && awaitingReturnAck) {
                        requestOpenApp(allowProxy = true)
                    }
                },
                d
            )
        }
    }

    private fun requestOpenApp(allowProxy: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastOpenAppAttemptAt < 700L) return
        lastOpenAppAttemptAt = now
        openAppFromFloating(allowProxy)
    }

    private fun fadeOutOverlayForReturn() {
        fadeOutOverlayForReturn(null)
    }

    private fun fadeOutOverlayForReturn(onEnd: (() -> Unit)?) {
        val view = floatingView ?: return
        if (overlayHiddenForReturn) return
        overlayHiddenForReturn = true
        view.animate().cancel()
        val exitInterpolator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PathInterpolator(0.3f, 0f, 0.2f, 1f)
            } else {
                DecelerateInterpolator()
            }
        view.animate()
            .alpha(0f)
            .scaleX(0.2f)
            .scaleY(0.2f)
            .setDuration(180)
            .setInterpolator(exitInterpolator)
            .withEndAction {
                view.visibility = View.GONE
                setTouchable(true)
                onEnd?.invoke()
            }
            .start()
    }

    private fun prepareOverlayForReturn() {
        val view = floatingView ?: return
        if (overlayTouchBlockedForReturn) return
        overlayTouchBlockedForReturn = true
        setTouchable(false)
        setFocusable(false)
    }

    private fun restoreOverlayAfterFailedReturn() {
        val view = floatingView ?: return
        overlayHiddenForReturn = false
        overlayTouchBlockedForReturn = false
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        setTouchable(true)
        setFocusable(false)
    }

    private fun setTouchable(touchable: Boolean) {
        val lp = layoutParams ?: return
        lp.flags =
            if (touchable) {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        if (isViewAdded && floatingView != null) {
            runCatching { windowManager.updateViewLayout(floatingView, lp) }
        }
    }

    private fun noAnimOptions(): android.os.Bundle? {
        return runCatching {
                ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            }
            .getOrNull()
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
        val targetIntent =
            Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                .putExtra("from_floating", true)
        val proxyIntent =
            Intent(this, LaunchProxyActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                .putExtra(LAUNCH_PROXY_EXTRA_TARGET_INTENT, targetIntent)

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                proxyIntent,
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

        val displayMetrics = resources.displayMetrics
        val startX = ((displayMetrics.widthPixels - widthPx) / 2f).toInt()
        val startY = ((displayMetrics.heightPixels - heightPx) / 2f).toInt()
        val endX = targetX
        val endY = targetY

        layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        windowX = startX
        windowY = startY

        setupFloatingView()

        windowManager.addView(floatingView, layoutParams)
        isViewAdded = true

        val view = floatingView ?: return
        view.alpha = 0f
        view.scaleX = 0.2f
        view.scaleY = 0.2f

        val enterInterpolator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PathInterpolator(0.22f, 1f, 0.36f, 1f)
            } else {
                OvershootInterpolator(0.8f)
            }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360
            interpolator = enterInterpolator
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val lp = layoutParams ?: return@addUpdateListener

                lp.x = (startX + (endX - startX) * t).toInt()
                lp.y = (startY + (endY - startY) * t).toInt()
                runCatching { windowManager.updateViewLayout(view, lp) }

                view.alpha = t
                val s = 0.2f + 0.8f * t
                view.scaleX = s
                view.scaleY = s

                windowX = lp.x
                windowY = lp.y
            }
            start()
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
            setFocusable(false)
            expandToFullScreen()
        }

        // 返回主页按钮
        val btnHome = view.findViewById<ImageButton>(R.id.btnHome)
        btnHome.setOnClickListener {
            setFocusable(false)
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
        inputMessage.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // 立即切换为可聚焦模式
                setFocusable(true)
            }
            false // 继续传递事件
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

    private fun openAppFromFloating(allowProxy: Boolean) {
        val baseIntent =
            Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                .putExtra("from_floating", true)

        val targetIntentForService =
            Intent(baseIntent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        val movedToFront =
            runCatching {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val task =
                        am.appTasks.firstOrNull { t ->
                            val info = runCatching { t.taskInfo }.getOrNull() ?: return@firstOrNull false
                            val mainName = MainActivity::class.java.name
                            info.baseActivity?.className == mainName || info.topActivity?.className == mainName
                        } ?: am.appTasks.firstOrNull() ?: return@runCatching false
                    runCatching { task.moveToFront() }
                    task.startActivity(this, baseIntent, noAnimOptions())
                    true
                }
                .getOrDefault(false)
        if (movedToFront) return

        val directOk = runCatching { startActivity(targetIntentForService, noAnimOptions()) }.isSuccess
        if (directOk && !allowProxy) return

        val proxyIntent =
            Intent(this, LaunchProxyActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                .putExtra(LAUNCH_PROXY_EXTRA_TARGET_INTENT, baseIntent)

        val pi =
            PendingIntent.getActivity(
                this,
                OPEN_APP_PI_REQUEST_CODE,
                proxyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val piSent =
            runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val options =
                            ActivityOptions.makeBasic().apply {
                                setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                                )
                            }
                        pi.send(this, 0, null, null, null, null, options.toBundle())
                    } else {
                        pi.send()
                    }
                }
                .isSuccess
        if (piSent) return

        runCatching { startActivity(proxyIntent, noAnimOptions()) }
            .recoverCatching { startActivity(targetIntentForService, noAnimOptions()) }
    }

    private fun animateDismissAndMaybeOpenApp(openApp: Boolean) {
        val view = floatingView

        if (view == null) {
            if (openApp) {
                awaitingReturnAck = true
                scheduleRetryOpenAppWhileWaitingAck()
                scheduleStopAfterReturnTimeout()
                requestOpenApp(allowProxy = false)
                return
            }
            stopSelf()
            return
        }

        var startDelayMs = 0L

        if (openApp) {
            awaitingReturnAck = true
            scheduleRetryOpenAppWhileWaitingAck()
            scheduleStopAfterReturnTimeout()
            requestOpenApp(allowProxy = false)
            startDelayMs = 120L
            setTouchable(false)
        }

        val exitInterpolator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PathInterpolator(0.3f, 0f, 0.2f, 1f)
            } else {
                DecelerateInterpolator()
            }

        view.animate()
            .alpha(0f)
            .scaleX(0.2f)
            .scaleY(0.2f)
            .setStartDelay(startDelayMs)
            .setDuration(220)
            .setInterpolator(exitInterpolator)
            .withEndAction {
                if (openApp) {
                    overlayHiddenForReturn = true
                    view.visibility = View.GONE
                    return@withEndAction
                }
                hideFloatingWindow()
                stopSelf()
            }
            .start()
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
        setFocusable(false)
        onExpandToFullScreen?.invoke()
        animateDismissAndMaybeOpenApp(openApp = true)
    }
    
    /**
     * 关闭悬浮窗
     */
    fun closeWindow() {
        onClose?.invoke()
        animateDismissAndMaybeOpenApp(openApp = false)
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
