package com.ai.phoneagent.helper

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.ai.phoneagent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 渲染助手，用于管理 AI 回复的流式动画、状态切换和视图绑定。
 * 对应 DeepSeek 风格的混合渲染方案。
 */
object StreamRenderHelper {

    data class ViewHolder(
        val thinkingLayout: LinearLayout,
        val thinkingHeader: LinearLayout,
        val thinkingText: TextView,
        val thinkingIndicator: TextView,
        val thinkingContentArea: View,
        val messageContent: TextView,
        val authorName: TextView,
        val actionArea: View,
        val retryButton: View?,
        val copyButton: View?
    )

    // 用于跟踪每个 TextView 当前正在运行的打字机 Job，以便取消旧的
    private val animJobs = ConcurrentHashMap<Int, Job>()

    // 缓存完整的文本内容，防止动画过程中丢失
    private val fullTextCache = ConcurrentHashMap<Int, StringBuilder>()

    /**
     * 绑定布局视图
     */
    fun bindViews(aiView: View): ViewHolder {
        return ViewHolder(
            thinkingLayout = aiView.findViewById(R.id.thinking_layout),
            thinkingHeader = aiView.findViewById(R.id.thinking_header),
            thinkingText = aiView.findViewById(R.id.thinking_text),
            thinkingIndicator = aiView.findViewById(R.id.thinking_indicator_text),
            thinkingContentArea = aiView.findViewById(R.id.thinking_content_area),
            messageContent = aiView.findViewById(R.id.message_content),
            authorName = aiView.findViewById(R.id.ai_author_name),
            actionArea = aiView.findViewById(R.id.action_area),
            retryButton = aiView.findViewById(R.id.btn_retry),
            copyButton = aiView.findViewById(R.id.btn_copy)
        )
    }

    /**
     * 初始化状态
     */
    fun initThinkingState(vh: ViewHolder) {
        vh.authorName.visibility = View.VISIBLE
        vh.thinkingLayout.visibility = View.VISIBLE
        vh.actionArea.visibility = View.GONE

        val headerTitle = vh.thinkingHeader.getChildAt(0) as? TextView
        headerTitle?.text = "思考中"
        
        vh.thinkingText.text = ""
        vh.thinkingText.visibility = View.VISIBLE
        vh.thinkingContentArea.visibility = View.VISIBLE
        vh.thinkingIndicator.text = " ⌄"
        
        // 重置消息内容
        vh.messageContent.text = ""
        
        // 绑定折叠逻辑
        var expanded = true
        vh.thinkingHeader.setOnClickListener {
            expanded = !expanded
            if (expanded) {
                vh.thinkingText.visibility = View.VISIBLE
                vh.thinkingContentArea.visibility = View.VISIBLE
                vh.thinkingIndicator.text = " ⌄"
            } else {
                vh.thinkingText.visibility = View.GONE
                vh.thinkingContentArea.visibility = View.GONE
                vh.thinkingIndicator.text = " ›"
            }
        }
        
        // 清理旧缓存
        animJobs.remove(vh.thinkingText.hashCode())?.cancel()
        animJobs.remove(vh.messageContent.hashCode())?.cancel()
        fullTextCache.remove(vh.thinkingText.hashCode())
        fullTextCache.remove(vh.messageContent.hashCode())
    }

    /**
     * 打字机效果追加文本
     * @param textView 目标 TextView
     * @param delta 新增的文本片段
     * @param coroutineScope 协程作用域 (通常是 lifecycleScope)
     * @param onScroll 滚动回调
     */
    fun animateAppend(
        textView: TextView,
        delta: String,
        coroutineScope: CoroutineScope,
        onScroll: () -> Unit
    ) {
        val viewId = textView.hashCode()
        
        // 更新全量文本缓存
        val sb = fullTextCache.getOrPut(viewId) { StringBuilder(textView.text) }
        sb.append(delta)
        val targetFullText = sb.toString()

        // 停止之前的动画 Job，如果有的话
        animJobs[viewId]?.cancel()

        animJobs[viewId] = coroutineScope.launch {
            val currentText = textView.text.toString()
            var index = currentText.length
            
            // 如果差异太大（比如重新显示），直接从头开始也行，但这里假设是追加
            if (index > targetFullText.length) {
                textView.text = targetFullText
                index = targetFullText.length
            }

            // 动态调整速度：剩余字数越多，速度越快
            val baseDelay = 30L
            
            while (index < targetFullText.length && isActive) {
                // 每次追加 1-N 个字符，避免刷新过快
                val remaining = targetFullText.length - index
                val step = when {
                    remaining > 50 -> 5
                    remaining > 20 -> 2
                    else -> 1
                }
                
                val nextIndex = (index + step).coerceAtMost(targetFullText.length)
                val substr = targetFullText.substring(0, nextIndex)
                
                textView.text = substr
                
                // 触发滚动
                onScroll()
                
                delay(baseDelay)
                index = nextIndex
            }
            // 确保最终文本一致
            textView.text = targetFullText
            onScroll()
        }
    }
    
    /**
     * 设置完整文本（无动画，用于历史记录或重置）
     */
    fun setFullText(textView: TextView, text: String) {
        val viewId = textView.hashCode()
        animJobs[viewId]?.cancel()
        fullTextCache[viewId] = StringBuilder(text)
        textView.text = text
    }

    /**
     * 从思考阶段过渡到回答阶段
     */
    fun transitionToAnswer(vh: ViewHolder) {
        // 思考区域变淡
        vh.thinkingLayout.animate()
            .alpha(0.6f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val headerTitle = vh.thinkingHeader.getChildAt(0) as? TextView
        headerTitle?.text = "已思考"
        
        // 默认不折叠，保持展开，或者根据设计需求折叠
        // vh.thinkingText.visibility = View.VISIBLE
        // vh.thinkingContentArea.visibility = View.VISIBLE
        // vh.thinkingIndicator.text = " ⌄"
    }

    /**
     * 标记完成
     */
    fun markCompleted(vh: ViewHolder, timeCostSec: Long) {
        val headerTitle = vh.thinkingHeader.getChildAt(0) as? TextView
        headerTitle?.text = "已思考 (用时 ${timeCostSec} 秒)"
        
        vh.actionArea.visibility = View.VISIBLE
        vh.actionArea.alpha = 0f
        vh.actionArea.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
            
        // 清理资源
        cancelAllAnimations(vh)
    }
    
    fun cancelAllAnimations(vh: ViewHolder) {
        animJobs.remove(vh.thinkingText.hashCode())?.cancel()
        animJobs.remove(vh.messageContent.hashCode())?.cancel()
    }
}
