package com.phoneai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

class FloatingBubbleService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "phoneai_bubble"
        const val ACTION_UPDATE_SIZE = "com.phoneai.app.UPDATE_SIZE"
        const val ACTION_STOP_SERVICE = "com.phoneai.app.STOP_SERVICE"
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var chatView: View? = null
    private var isChatVisible = false

    // Voice recording
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    // Conversation memory (role → content pairs)
    private val messages = mutableListOf<Pair<String, String>>()

    // Coroutine scope tied to service lifecycle
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── CONFIGURATION RECEIVER ─────────────────────────────────────
    
    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_SIZE -> updateBubbleSize()
                ACTION_STOP_SERVICE -> stopSelf()
            }
        }
    }

    // ── LIFECYCLE ──────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val filter = IntentFilter().apply {
                addAction(ACTION_UPDATE_SIZE)
                addAction(ACTION_STOP_SERVICE)
            }
            registerReceiver(configReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            val filter = IntentFilter().apply {
                addAction(ACTION_UPDATE_SIZE)
                addAction(ACTION_STOP_SERVICE)
            }
            registerReceiver(configReceiver, filter)
        }
        
        showBubble()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(configReceiver)
        scope.cancel()
        removeBubble()
        removeChat()
        stopRecorder()
    }

    // ── FLOATING BUBBLE ────────────────────────────────────────────

    private fun startBreathingAnimation(view: View) {
        // Animation removed
    }

    private fun showBubble() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.floating_bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 300
        }

        windowManager.addView(bubbleView, params)
        makeDraggable(bubbleView!!, params)
        updateBubbleSize()
        startBreathingAnimation(bubbleView!!)
    }

    private fun updateBubbleSize() {
        val prefs = getSharedPreferences("phoneai_prefs", Context.MODE_PRIVATE)
        val bubbleSizeDp = prefs.getInt("bubble_size", 66)
        val sizePx = dpToPx(bubbleSizeDp)

        bubbleView?.findViewById<View>(R.id.bubbleRoot)?.layoutParams?.let {
            it.width = sizePx
            it.height = sizePx
            bubbleView?.findViewById<View>(R.id.bubbleRoot)?.layoutParams = it
        }
        bubbleView?.findViewById<View>(R.id.bubbleIcon)?.layoutParams?.let {
            it.width = sizePx
            it.height = sizePx
            bubbleView?.findViewById<View>(R.id.bubbleIcon)?.layoutParams = it
        }

        bubbleView?.let { windowManager.updateViewLayout(it, it.layoutParams) }
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0
        var rawStartX = 0f; var rawStartY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    rawStartX = event.rawX; rawStartY = event.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - rawStartX).toInt()
                    val dy = (event.rawY - rawStartY).toInt()
                    if (abs(dx) > 5 || abs(dy) > 5) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleChat()
                    true
                }
                else -> false
            }
        }
    }

    private fun removeBubble() {
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            bubbleView = null
        }
    }

    // ── CHAT PANEL ─────────────────────────────────────────────────

    private fun toggleChat() {
        if (isChatVisible) removeChat() else showChat()
    }

    private fun showChat() {
        if (isChatVisible) return
        isChatVisible = true

        chatView = LayoutInflater.from(this).inflate(R.layout.floating_chat, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(480),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        windowManager.addView(chatView, params)
        setupChatUI()

        // Re-render existing messages (chat was re-opened)
        messages.forEach { (role, content) -> addMessageBubble(role, content) }
        scrollToBottom()
    }

    private fun setupChatUI() {
        val view = chatView ?: return

        view.findViewById<ImageButton>(R.id.btnQuitApp).setOnClickListener { stopSelf() }
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { removeChat() }

        view.findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
            val input = view.findViewById<EditText>(R.id.etInput)
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.text.clear()
                handleUserMessage(text)
            }
        }

        // Mic: hold to record, release to transcribe + send
        val micBtn = view.findViewById<ImageButton>(R.id.btnMic)
        val statusTv = view.findViewById<TextView>(R.id.tvStatus)

        micBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isRecording) {
                        startRecording()
                        micBtn.alpha = 0.5f
                        statusTv.text = "🔴 Recording... Release to send"
                        statusTv.visibility = View.VISIBLE
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        micBtn.alpha = 1f
                        statusTv.text = "⏳ Transcribing with Whisper..."
                        scope.launch {
                            val text = stopRecordingAndTranscribe()
                            statusTv.visibility = View.GONE
                            if (text.isNotEmpty()) {
                                handleUserMessage(text)
                            } else {
                                statusTv.text = "❌ Didn't catch that"
                                statusTv.visibility = View.VISIBLE
                                delay(2000)
                                statusTv.visibility = View.GONE
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun handleUserMessage(text: String) {
        messages.add(Pair("user", text))
        addMessageBubble("user", text)
        scrollToBottom()

        val statusTv = chatView?.findViewById<TextView>(R.id.tvStatus)
        statusTv?.text = "🤖 Baymax is thinking..."
        statusTv?.visibility = View.VISIBLE

        scope.launch {
            val reply = GroqApi.chat(messages)
            messages.add(Pair("assistant", reply))
            statusTv?.visibility = View.GONE
            addMessageBubble("assistant", reply)
            scrollToBottom()
        }
    }

    private fun addMessageBubble(role: String, content: String) {
        val container = chatView?.findViewById<LinearLayout>(R.id.messagesContainer) ?: return
        val isUser = role == "user"

        val tv = TextView(this).apply {
            text = content.replace(Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.IGNORE_CASE)), "").trim()
            textSize = 14f
            setTextColor(0xFFF1F0FF.toInt())
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            setBackgroundResource(if (isUser) R.drawable.user_msg_bg else R.drawable.ai_msg_bg)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            topMargin = dpToPx(6)
            marginStart = dpToPx(8)
            marginEnd = dpToPx(8)
        }
        tv.layoutParams = lp
        container.addView(tv)
    }

    private fun scrollToBottom() {
        val scroll = chatView?.findViewById<ScrollView>(R.id.scrollView)
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun removeChat() {
        isChatVisible = false
        chatView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            chatView = null
        }
    }

    // ── VOICE RECORDING ────────────────────────────────────────────

    private fun startRecording() {
        isRecording = true
        audioFile = File(cacheDir, "phoneai_${System.currentTimeMillis()}.m4a")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder!!.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(128000)
            setOutputFile(audioFile!!.absolutePath)
            try {
                prepare()
                start()
            } catch (e: Exception) {
                isRecording = false
                e.printStackTrace()
            }
        }
    }

    private suspend fun stopRecordingAndTranscribe(): String {
        isRecording = false
        stopRecorder()
        val file = audioFile ?: return ""
        val result = GroqApi.transcribe(file)
        file.delete()
        audioFile = null
        return result
    }

    private fun stopRecorder() {
        try {
            mediaRecorder?.apply { stop(); release() }
        } catch (_: Exception) {}
        mediaRecorder = null
    }

    // ── NOTIFICATION ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PhoneAI Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PhoneAI floating assistant is active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE
        )

        val updateIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).apply { action = "ACTION_FORCE_UPDATE" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val replyIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = "ACTION_REPLY"
        }
        val replyPendingIntent = PendingIntent.getService(
            this, 3, replyIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val remoteInput = androidx.core.app.RemoteInput.Builder("message")
            .setLabel("Message Baymax...")
            .build()

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_dialog_email, "Message", replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneAI Active")
            .setContentText("Tap to open. Reply directly or check updates.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent)
            .addAction(replyAction)
            .addAction(android.R.drawable.ic_popup_sync, "Update", updateIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_REPLY") {
            val results = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
            val text = results?.getCharSequence("message")?.toString()
            if (!text.isNullOrBlank()) {
                if (!isChatVisible) showChat()
                val input = chatView?.findViewById<EditText>(R.id.etInput)
                input?.setText("")
                handleUserMessage(text)
            }
            // Dismiss the exact reply state and update notification
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // ── UTILS ──────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
