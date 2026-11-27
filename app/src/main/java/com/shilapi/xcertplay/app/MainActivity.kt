package com.shilapi.xcertplay.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * DiPlay API 19 无线专用壳：纯 View 界面 + shared 协议模块。
 *
 * 画面方案：单 Activity + 全屏 SurfaceView 直接承载 MediaCodec 硬件输出，
 * 不切换 Activity——避免 Surface 销毁重建的空窗期丢首个 IDR 关键帧；
 * attachSurfaceToSink 从 surfaceCreated/surfaceChanged/onResume/windowFocus/
 * 状态变化 五个时机幂等重试接上解码器（E43 上 surfaceCreated 不保证回调）。
 * 会话激活后隐藏控制面板，触摸直接在 SurfaceView 上归一化后回传 iPhone。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private var displayedStatusText: String? = null
    private lateinit var ssidInput: EditText
    private lateinit var passphraseInput: EditText
    private lateinit var channelInput: EditText
    private lateinit var wirelessButton: Button
    private lateinit var videoView: SurfaceView
    private lateinit var controlPanel: View
    private lateinit var cornerOverlay: CornerOverlayView
    private lateinit var cardHotspot: View
    private lateinit var cardSamenet: View
    private lateinit var cardIphoneHotspot: View

    /** 三种组网方式：车机热点（默认）/ 同一 WiFi / 当前网络上的 iPhone 热点。 */
    enum class ConnectionMode(val label: String) {
        CAR_HOTSPOT("车机热点"),
        SAME_NETWORK("同一 WiFi"),
        IPHONE_HOTSPOT("iPhone 热点"),
    }

    @Volatile
    private var connectionMode: ConnectionMode = ConnectionMode.CAR_HOTSPOT

    /** 视频模式看门狗：会话激活后若迟迟无帧（半途夭折的会话），自动退回控制面板。 */
    private var videoWatchdogRunnable: Runnable? = null

    /** 收到首帧时由 sink 诊断回调置位，看门狗据此判断画面已真正起来。 */
    @Volatile
    private var videoFrameArrived = false

    /** 当前连接的 AirPlay 会话：触摸回传用；无会话时触摸不转发。 */
    @Volatile
    private var activeSession: AirPlaySession? = null

    @Volatile private var videoSurface: Surface? = null

    /** 已接上的 (surface, sink) 组合：未变化时不重复接，避免刷新自循环。 */
    private var attachedSurface: Surface? = null
    private var attachedSink: AndroidMediaSink? = null

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var autoStartAttempted = false
    private var audioHandoffPreparing = false
    private data class PendingTouchMove(
        val session: AirPlaySession,
        val contacts: List<AirPlayContact>,
        val inputEventNs: Long,
        val generation: Long,
    )
    private val pendingTouchMove = AtomicReference<PendingTouchMove?>()
    private val touchMoveDrainScheduled = AtomicBoolean()
    private val touchGeneration = java.util.concurrent.atomic.AtomicLong()
    private val touchMapper = CarPlayTouchMapper.Session()

    /** 设置弹窗里的日志视图：弹窗打开期间由 500ms 合帧刷新；关闭即置空。 */
    private var dialogLogText: TextView? = null

    /** 触摸回传专用单线程队列：HID 报文发送不再占用 UI 线程（LAN 阻塞写不卡界面）。 */
    private val touchExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
    ) { runnable ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            runnable.run()
        }, "touch-send").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        // 主页面默认不拉键盘：只有在用户点击输入框时才弹（布局根已设 focusableInTouchMode 拦截初始焦点）
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status)
        ssidInput = findViewById(R.id.ssid)
        passphraseInput = findViewById(R.id.passphrase)
        channelInput = findViewById(R.id.channel)
        wirelessButton = findViewById(R.id.btn_wireless)
        cardHotspot = findViewById(R.id.card_hotspot)
        cardSamenet = findViewById(R.id.card_samenet)
        cardIphoneHotspot = findViewById(R.id.card_iphone_hotspot)

        // 先恢复组网方式，再读取手动拓扑参数；iPhone 热点模式只使用实时 Wi-Fi 状态。
        connectionMode = loadConnectionMode()
        val credentials = if (connectionMode == ConnectionMode.IPHONE_HOTSPOT) {
            "" to ""
        } else {
            AirPlayPersistence.loadConnectionCredentials(this, connectionMode.name) ?: ("" to "")
        }
        ssidInput.setText(credentials.first)
        passphraseInput.setText(credentials.second)
        val savedChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        if (connectionMode == ConnectionMode.CAR_HOTSPOT && savedChannel > 0) {
            channelInput.setText(savedChannel.toString())
        }
        val autostart = findViewById<Switch>(R.id.autostart)
        autostart.isChecked = AirPlayPersistence.loadAutoStartOnBoot(this)
        autostart.setOnCheckedChangeListener { _, checked ->
            AirPlayPersistence.saveAutoStartOnBoot(this, checked)
            log(if (checked) "开机自启已开启：开机后自动进入并连接无线" else "开机自启已关闭")
        }

        // 三种组网方式选择卡：点击切换，选中卡高亮紫色，持久化
        refreshModeCards()
        cardHotspot.setOnClickListener { selectMode(ConnectionMode.CAR_HOTSPOT) }
        cardSamenet.setOnClickListener { selectMode(ConnectionMode.SAME_NETWORK) }
        cardIphoneHotspot.setOnClickListener { selectMode(ConnectionMode.IPHONE_HOTSPOT) }
        // 初始模式若不是车机热点，且热点开着 → 按用户规则关掉热点恢复车机 WiFi
        maybeDisableHotspotForMode(connectionMode)
        // 初始即为同一 WiFi 模式时回填当前网络名，密码由用户填
        if (connectionMode == ConnectionMode.SAME_NETWORK && ssidInput.text.isNullOrBlank()) {
            refreshCurrentWifiHint()
        }

        findViewById<Button>(R.id.btn_wireless).setOnClickListener { onWirelessStart() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener(::onStop)
        findViewById<Button>(R.id.btn_settings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btn_pair).setOnClickListener { showPhoneSelectorDialog() }

        // SurfaceView 让 MediaCodec 直接交给 SurfaceFlinger 合成，避免纹理中间层。
        controlPanel = findViewById(R.id.waiting_panel)
        videoView = findViewById(R.id.video)
        // 圆角黑边层：代码动态插入到 SurfaceView 之上、等待面板之下。
        // XML 声明在 R8 + shrinkResources 组合下 inflate 不稳定（类保留/ id 一致仍拿不到），
        // 动态创建彻底绕开 XML 反射链路，还顺便控制 z 序：必须在 waiting_panel 之前插入。
        cornerOverlay = CornerOverlayView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        (videoView.parent as? android.view.ViewGroup)?.addView(
            cornerOverlay,
            (videoView.parent as? android.view.ViewGroup)!!.indexOfChild(videoView) + 1,
        )
        videoView.setOnTouchListener { _, event -> sendVideoTouch(event) }
        videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val surface = holder.surface
                videoSurface = surface.takeIf { it.isValid }
                sink?.surfaceCreated(SCREEN_MAIN)
                log("SurfaceView 已创建 ${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}")
                attachSurfaceToSink("surfaceCreated")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val surface = holder.surface
                if (surface.isValid) videoSurface = surface
                log("SurfaceView 已调整 ${width}x${height} format=$format")
                attachSurfaceToSink("surfaceChanged", force = true)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val current = videoSurface
                sink?.surfaceDestroyed(SCREEN_MAIN)
                if (current != null) runCatching { sink?.clearSurface(SCREEN_MAIN, current) }
                videoSurface = null
                attachedSurface = null
                attachedSink = null
                log("SurfaceView 已销毁")
            }
        })

        // 进程级会话延续：Activity 重建（系统回收后重开）时直接领回仍在运行的会话，
        // 免握手直接回 CarPlay 画面；SurfaceView 的 holder 回调会把画面重新接上。
        if (liveController != null) {
            controller = liveController
            sink = liveSink
            activeSession = liveSession
            if (liveSession != null) {
                // Only switch to the video surface when AirPlay has actually attached. During
                // the Bluetooth/Wi-Fi handshake the controller may already be live, but showing
                // a full-screen waiting state makes returning from the desktop look stuck.
                showVideoMode()
                log("会话仍在运行：直接回到 CarPlay（无需重新握手）")
            } else {
                log("连接仍在建立：保留当前握手流程，返回后继续等待")
            }
        }

        installOfflineMfiFromAssets()
        val (screenW, screenH) = displaySize()
        setStatus("就绪 · Android API ${android.os.Build.VERSION.SDK_INT} · 屏幕 ${screenW}x${screenH}")
        log("JSplay 启动（DiPlay API19 引擎）· CarPlay 名称: ${AirPlayPersistence.loadDeviceName(this)}")
        log("身份: ${deviceId(AirPlayPersistence.loadIdentity(this))}")
        log("蓝牙引导: 自动探测 HSAE 工厂 Anw 服务")
        ensureRuntimePermissions()
        // adb/自启调试入口：
        //   --ez autoconnect true             直接触发一键连接
        //   --ez samenet true                 先切同网模式（不发 WiFi 指令、不断 ADB）再自动连接
        val samenetFromIntent = intent.getBooleanExtra("samenet", false)
        if (samenetFromIntent && connectionMode != ConnectionMode.SAME_NETWORK) {
            selectMode(ConnectionMode.SAME_NETWORK, silent = true)
            log("samenet=true：已切换同一 WiFi 模式（本次连接不动 WiFi 状态）")
        }
        if (samenetFromIntent && ssidInput.text.toString().isNotBlank()) {
            // 旧热点 SSID 留在输入框里会被同网模式当成“预期网络”校验，直接清空=用当前网络。
            // 密码不清空：系统读不到已保存 WiFi 的明文密码，UI 里的值是唯一来源。
            ssidInput.setText("")
            log("samenet=true：已清空预期 SSID，自动使用车机当前连接的 WiFi")
        }
        if (intent.getBooleanExtra("autoconnect", false)) {
            log("autoconnect=true，1.5s 后自动开始连接")
            mainHandler.postDelayed({ if (controller == null && !isFinishing) onWirelessStart() }, 1500)
        }

        // 蓝牙配对调试入口：am start ... --ei pairmode 1 让车机进入配对模式（iPhone 蓝牙里搜到车机配对）
        val pairMode = intent.getIntExtra("pairmode", -1)
        if (pairMode >= 0) {
            log("pairmode=$pairMode：切换车机蓝牙配对模式…")
            setBluetoothPairMode(pairMode)
        }
    }

    /**
     * 手机部署（当虚拟车机）需要 Android 12+ 的蓝牙运行时权限；
     * 车机（API 19/28 target）上安装时自动授予，本方法直接跳过。
     */
    private fun ensureRuntimePermissions() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        val needed = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            needed += android.Manifest.permission.BLUETOOTH_CONNECT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            needed += android.Manifest.permission.BLUETOOTH_SCAN
        }
        if (needed.isEmpty()) return
        log("需要蓝牙运行时权限（${needed.size} 项），正在请求…")
        requestPermissions(needed.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) return
        val denied = permissions.filterIndexed { i, _ ->
            grantResults.getOrNull(i) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            log("蓝牙权限已授予，可以连接")
        } else {
            log("蓝牙权限被拒绝：$denied")
            log("请到系统设置 → 应用 → JSplay → 权限里手动开启“附近的设备”")
        }
    }

    override fun onResume() {
        super.onResume()
        // E43 上从后台回来时 holder 回调不一定重发，但 Surface 可能仍有效——
        // 强制重试接 Surface，解码器才能渲到当前活 Surface。
        attachSurfaceToSink("onResume", force = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        dialogLogText = null
        runCatching { touchExecutor.shutdownNow() }
        // 会话/controller/sink 移交进程级 keeper 延续：重开 App 直接回 CarPlay（用户要求：
        // 已连接不重新握手）。彻底停止由「停止」按钮或会话结束处理。
        // SurfaceView 的 Surface 由 Holder 管理；Activity 只解除 sink 引用，不释放 Holder 对象。
        videoSurface?.let { current ->
            runCatching { sink?.clearSurface(SCREEN_MAIN, current) }
        }
        videoSurface = null
        attachedSurface = null
        attachedSink = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 兜底：部分系统上从其他 Activity 返回时 surfaceCreated 不再回调，
        // 但窗口焦点变化时 Surface 已就绪——强制重试（Surface 可能已换）。
        if (hasFocus) attachSurfaceToSink("windowFocus", force = true)
        // 勾选了开机自启 → 进入界面 1.5 秒后自动开始无线连接（等待热点界面回填完成）
        if (hasFocus && !autoStartAttempted && AirPlayPersistence.loadAutoStartOnBoot(this)) {
            autoStartAttempted = true
            mainHandler.postDelayed({ if (controller == null && !isFinishing) onWirelessStart() }, 1500)
        }
    }

    /** 一键入口：同网模式直接用当前 WiFi；热点模式自动开热点并填参后再启动。 */
    private fun onWirelessStart() {
        if (controller != null) {
            log("会话已在运行，先停止")
            return
        }
        // 手机部署时 Android 12+ 的蓝牙权限没批下来就直接拦截，避免会话跑一半才失败
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            log("缺少“附近的设备”权限（BLUETOOTH_CONNECT），无法进行蓝牙握手")
            log("请在弹窗中允许，或到 系统设置→应用→JSplay→权限 里开启后重试")
            ensureRuntimePermissions()
            setStatus("等待蓝牙权限")
            return
        }
        if (connectionMode == ConnectionMode.SAME_NETWORK) {
            setStatus("同一 WiFi 模式：读取当前网络…")
            log("同一 WiFi 模式：不开热点、不断网，直接使用车机当前连接的 WiFi")
            startWirelessSession()
            return
        }
        if (connectionMode == ConnectionMode.IPHONE_HOTSPOT) {
            setStatus("读取车机当前 Wi-Fi…")
            Thread({
                val current = runCatching { currentWifiConnection() }.getOrNull()
                post {
                    if (current == null) {
                        log("iPhone 热点模式需要车机先在系统 Wi-Fi 设置中连接 iPhone 热点；DiPlay 不会切换网络")
                        setStatus("请先在系统 Wi-Fi 连接 iPhone 热点")
                    } else {
                        ssidInput.setText(current.first)
                        log("iPhone 热点模式：复用当前 Wi-Fi「${current.first}」，不切换网络；密码优先从系统读取，可在密码框提供备用值")
                        setStatus("使用当前 Wi-Fi：${current.first}")
                        startWirelessSession()
                    }
                }
            }, "read-current-phone-hotspot").apply { isDaemon = true }.start()
            return
        }
        // 车机热点模式：热点未开启时弹确认框——开热点会断车机 WiFi，用户明确同意才执行
        Thread({
            val apEnabled = runCatching { ApHotspot.isApEnabled(this) }.getOrDefault(false)
            if (apEnabled) {
                post { beginHotspotConnect() }
            } else {
                post { showEnableHotspotDialog() }
            }
        }, "check-ap").apply { isDaemon = true }.start()
    }

    // ---------- 银色主题弹窗（与主界面同风格，替代系统 Holo 白底 AlertDialog） ----------

    /** 构建银色弹窗骨架：深银圆角面板 + 标题/正文/自定义内容区；按钮由调用方追加。 */
    private fun buildSilverDialog(
        title: String,
        message: String? = null,
        content: View? = null,
        showTopCloseButton: Boolean = false,
        onCancel: (() -> Unit)? = null,
    ): Pair<Dialog, LinearLayout> {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (density * value).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_purple_dark)
            setPadding(dp(24), dp(18), dp(24), dp(16))
        }
        val dialog = Dialog(this)
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFE8EBEF.toInt())
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (showTopCloseButton) {
            titleRow.addView(Button(this).apply {
                text = "X"
                contentDescription = "关闭设置"
                isAllCaps = false
                minWidth = dp(48)
                minHeight = dp(42)
                setPadding(0, 0, 0, 0)
                setBackgroundResource(R.drawable.button_purple_outline)
                setTextColor(0xFFE8EBEF.toInt())
                setOnClickListener { dialog.dismiss() }
            })
        }
        root.addView(titleRow)
        message?.let { msg ->
            root.addView(TextView(this).apply {
                text = msg
                setTextColor(0xFFA6ACB5.toInt())
                textSize = 14f
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, dp(12), 0, 0)
            })
        }
        if (content != null) {
            root.addView(
                content,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(12) },
            )
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCancelable(true)
        if (onCancel != null) dialog.setOnCancelListener { onCancel() }
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        return dialog to root
    }

    /** 弹窗按钮：主按钮银渐变/次按钮深银描边；[dismissAfter]=false 时点击后不关闭。 */
    private fun silverDialogButton(
        dialog: Dialog,
        text: String,
        primary: Boolean,
        dismissAfter: Boolean = true,
        onClick: () -> Unit = {},
    ): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setBackgroundResource(if (primary) R.drawable.button_purple else R.drawable.button_purple_outline)
            setTextColor(if (primary) 0xFF2A2E36.toInt() else 0xFFE8EBEF.toInt())
            textSize = if (primary) 15f else 13f
            minHeight = (density * 46).toInt()
            setOnClickListener {
                onClick()
                if (dismissAfter) dialog.dismiss()
            }
        }
    }

    /** 按钮行（等宽铺开）追加到弹窗根部。 */
    private fun addDialogButtonRow(root: LinearLayout, vararg buttons: View) {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (density * 18).toInt(), 0, 0)
        }
        buttons.forEach { button ->
            if (row.childCount > 0) {
                row.addView(Space(this), LinearLayout.LayoutParams((density * 10).toInt(), 1))
            }
            row.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        root.addView(row)
    }

    /** 显示弹窗并按屏幕比例限宽（车机横屏）。 */
    private fun showSilverDialog(dialog: Dialog, widthFraction: Float = 0.72f, maxDp: Int = 720) {
        dialog.show()
        val density = resources.displayMetrics.density
        val width = minOf(
            (resources.displayMetrics.widthPixels * widthFraction).toInt(),
            (density * maxDp).toInt(),
        )
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 热点未开启时的确认弹窗（银色主题）：开热点将断开车机 WiFi，需用户明确确认。 */
    private fun showEnableHotspotDialog() {
        val (dialog, root) = buildSilverDialog(
            title = "当前热点未开启",
            message = "开启车机热点后，车机的 WiFi 将无法使用（需手动关闭热点才能恢复）。\n\n确认开启车机热点？",
            onCancel = {
                log("已取消：车机热点未开启，本次不连接")
                setStatus("热点未开启")
            },
        )
        addDialogButtonRow(
            root,
            silverDialogButton(dialog, "取消", primary = false) {
                log("已取消：车机热点未开启，本次不连接")
                setStatus("热点未开启")
            },
            silverDialogButton(dialog, "确认开启车机热点", primary = true) {
                beginHotspotConnect()
            },
        )
        showSilverDialog(dialog)
    }

    /** 确认后（或热点已开着）：走原有自动开热点/读配置/回填/启动链路。 */
    private fun beginHotspotConnect() {
        setStatus("正在准备车机热点…")
        Thread({
            try {
                val config = ensureHotspot()
                post {
                    ssidInput.setText(config.ssid)
                    passphraseInput.setText(config.passphrase)
                    if (channelInput.text.toString().trim().isEmpty()) {
                        channelInput.setText(DEFAULT_CHANNEL.toString())
                        log("API 19 读不到热点实际信道，已预填 $DEFAULT_CHANNEL（2.4G 常见值；iPhone 加入失败可改试 1 或 11）")
                    }
                    startWirelessSession()
                }
            } catch (error: Exception) {
                post {
                    log("自动开启热点失败: ${error.message}")
                    log("请在车机系统设置里手动开启热点并填入参数后重试")
                    setStatus("热点未开启")
                }
            }
        }, "auto-hotspot").apply { isDaemon = true }.start()
    }

    /**
     * 后台线程执行：确保车机自带热点处于开启状态，返回实际使用的 SSID/密码。
     *
     * AOSP 4.4 的 WifiService 对 setWifiApEnabled 只校验 CHANGE_WIFI_STATE（普通权限），
     * 因此可通过反射直接开关热点；个别厂商固件额外加锁时会失败并抛错，由调用方回落手动流程。
     */
    private fun ensureHotspot(): ApHotspot.ApConfig {
        if (ApHotspot.isApEnabled(this)) {
            val running = ApHotspot.readConfig(this)
            if (running != null) {
                log("车机热点已开启：${running.ssid}")
                val passphrase = running.passphrase.ifEmpty { passphraseInput.text.toString() }
                if (passphrase.isEmpty()) {
                    throw IOException(
                        "系统已隐藏车机热点密码；请在本页填写当前热点密码，避免把错误密码发送给 iPhone",
                    )
                }
                return running.copy(passphrase = passphrase)
            }
            val uiSsid = ssidInput.text.toString().trim()
            if (uiSsid.isNotEmpty()) {
                return ApHotspot.ApConfig(uiSsid, passphraseInput.text.toString())
            }
            throw IOException("热点已开启但读不到其配置，请手动填写 SSID/密码")
        }
        val uiSsid = ssidInput.text.toString().trim()
        val uiPass = passphraseInput.text.toString()
        val configured = ApHotspot.readConfig(this)
        val desired = when {
            uiSsid.isNotEmpty() && uiPass.isNotEmpty() -> ApHotspot.ApConfig(uiSsid, uiPass)
            uiSsid.isEmpty() && uiPass.isNotEmpty() && configured != null ->
                configured.copy(passphrase = uiPass)
            configured != null && configured.passphrase.isNotEmpty() &&
                (uiSsid.isEmpty() || uiSsid == configured.ssid) -> configured
            uiSsid.isEmpty() && uiPass.isEmpty() && configured == null ->
                ApHotspot.ApConfig(defaultSsid(), DEFAULT_PASSPHRASE)
            else -> throw IOException(
                "无法确认车机热点的 SSID/密码；请填写热点名称和密码，或在车机系统 Wi-Fi 设置中检查热点配置",
            )
        }
        if (uiSsid != desired.ssid || uiPass != desired.passphrase) {
            val written = ApHotspot.writeConfig(this, desired.ssid, desired.passphrase)
            if (!written) {
                // 写不进配置时：若系统已有配置就沿用；否则只能让用户手动配
                val fallback = ApHotspot.readConfig(this)
                    ?: throw IOException("无法写入热点配置（固件限制），请在系统设置里手动配置热点")
                log("热点配置写入失败，沿用系统现有配置：${fallback.ssid}")
                return enableAndReturn(fallback)
            }
            log("已写入热点配置：${desired.ssid}")
        }
        return enableAndReturn(desired)
    }

    private fun enableAndReturn(config: ApHotspot.ApConfig): ApHotspot.ApConfig {
        // E43 单射频：STA 连网时开 AP 会被拒。setApEnabled 内部已按 launcher 验证过的
        // 顺序处理（关 STA → 180ms → 写配置 → 双签名开启 → 兜底），这里只负责判定结果。
        log("正在通过系统接口开启车机热点…")
        if (!ApHotspot.setApEnabled(this, true)) {
            throw IOException("系统拒绝开启热点（固件权限限制），请在车机系统设置里手动开启")
        }
        if (!ApHotspot.awaitState(this, ApHotspot.STATE_ENABLED, AWAIT_AP_TIMEOUT_MILLIS)) {
            throw IOException("热点开启超时（state=${ApHotspot.apState(this)}），请手动开启后重试")
        }
        log("车机热点已开启：${config.ssid}")
        return config
    }

    @SuppressLint("SetTextI18n")
    private fun startWirelessSession(audioHandoffPrepared: Boolean = false) {
        if (controller != null) {
            log("会话已在运行，先停止")
            return
        }
        if (audioHandoffPreparing) {
            log("正在切换 CarPlay 音频路由，请稍候")
            return
        }
        // Complete A2DP teardown before starting iAP2/AirPlay negotiation. Starting both at
        // once lets the phone see A2DP as available and commit the session to Bluetooth audio.
        if (!audioHandoffPrepared &&
            AirPlayPersistence.loadAudioChannel(this) == AirPlayPersistence.AUDIO_CHANNEL_CARPLAY
        ) {
            audioHandoffPreparing = true
            setStatus("准备 CarPlay Wi-Fi 音频…")
            log("等待断开蓝牙 A2DP 后再开始 CarPlay 握手")
            Thread({
                val result = HsaeBluetoothAudio.disconnectA2dp(this)
                try {
                    Thread.sleep(A2DP_ROUTE_SETTLE_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                post {
                    audioHandoffPreparing = false
                    if (result.ok) {
                        log("蓝牙 A2DP 断开操作已完成并等待路由稳定；现在开始 CarPlay 握手")
                    } else {
                        log("警告：断开 A2DP 未确认（${result.detail}），继续连接；Wi-Fi 音频协商可能失败")
                    }
                    startWirelessSession(audioHandoffPrepared = true)
                }
            }, "prepare-carplay-audio").apply { isDaemon = true }.start()
            return
        }
        val sameNetwork = connectionMode != ConnectionMode.CAR_HOTSPOT
        val identity = AirPlayPersistence.loadIdentity(this)
        val manualSsid = ssidInput.text.toString().trim()
        if (!sameNetwork && manualSsid.isEmpty()) {
            log("热点 SSID 为空（将由自动开热点流程创建默认热点）")
        }
        val channel = channelInput.text.toString().trim().toIntOrNull() ?: 0
        if (!sameNetwork && channel == 0) {
            log("提示：未填信道，将上报 channel=0，部分 iPhone 会拒绝")
        }
        if (sameNetwork && manualSsid.isEmpty() && connectionMode == ConnectionMode.SAME_NETWORK) {
            log("同一 WiFi 模式：SSID 留空，将自动使用车机当前连接的 WiFi")
        }
        // iPhone hotspot mode is live-network-only; never persist an SSID/password target there.
        if (connectionMode != ConnectionMode.IPHONE_HOTSPOT) {
            AirPlayPersistence.saveConnectionCredentials(
                this,
                connectionMode.name,
                manualSsid,
                passphraseInput.text.toString(),
            )
        }
        if (connectionMode == ConnectionMode.CAR_HOTSPOT) {
            AirPlayPersistence.saveManualHotspotChannel(this, channel)
        }
        val displayName = AirPlayPersistence.loadDeviceName(this)
        val id = Iap2IdentificationConfig(
            name = displayName,
            modelIdentifier = "JSplay19",
            manufacturer = "xcertplay",
            serialNumber = "JSPLAY-" + deviceId(identity).replace(":", ""),
            firmwareVersion = "0.1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
        )
        val (screenW, screenH) = displaySize()
        val requestedFps = AirPlayPersistence.loadFps(this)
        val refreshRateHz = windowManager.defaultDisplay.refreshRate
        val fps = AirPlayDisplaySettings.effectiveFps(requestedFps, refreshRateHz)
        val videoW = screenW
        val videoH = screenH
        // 上次连成功的那台 iPhone：优先选它，多台 iPhone 配对也不再歧义（换机场景）。
        val lastIphone = AirPlayPersistence.loadLastWirelessIphoneAddress(this)
        if (lastIphone != null) {
            log("优先尝试上次连接的 iPhone（蓝牙 $lastIphone）；若它已不在车里且连接失败，会自动遗忘并回到自动选择")
        }
        val runtime = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            identification = id,
            transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = if (sameNetwork) {
                WirelessHotspotMode.SAME_NETWORK
            } else {
                WirelessHotspotMode.MANUAL
            },
            manualHotspotSsid = manualSsid.ifEmpty { null },
            manualHotspotPassphrase = passphraseInput.text.toString().ifEmpty { null },
            manualHotspotBand = ManualHotspotBand.AUTO,
            manualHotspotChannel = channel,
            manualHotspotSecurity = ManualHotspotSecurity.WPA2,
            wirelessBluetoothDeviceAddress = lastIphone,
            onWirelessTargetChosen = { address ->
                // 选定目标即记住：下次哪怕多台 iPhone 配对，也优先这台。
                AirPlayPersistence.saveLastWirelessIphoneAddress(this, address)
                log("已记住本台 iPhone（蓝牙 $address），下次连接自动优先")
            },
            hostName = "jsplay",
            label = displayName,
        )
        // CarPlay 主屏车机品牌图标槽位：解码选中图标（≤192 方形 PNG），失败则不带 oemIcons（空槽兜底）
        val carButtonIcon = loadCarButtonIcon()
        val airPlayIcon = buildAirPlayIcon(carButtonIcon)
        if (airPlayIcon != null) {
            log("车机图标「${carButtonIcon.displayName}」已随 /info 下发（${airPlayIcon.widthPixels}x${airPlayIcon.heightPixels}）")
        } else {
            log("警告：车机图标解码失败，本次连接 iPhone 侧显示空槽")
        }
        val config = AirPlayConfig(
            deviceName = displayName,
            deviceId = deviceId(identity),
            btMac = deviceId(identity),
            sourceVersion = "950.7.1",
            main = AirPlayDisplayConfig(
                widthPixels = videoW,
                heightPixels = videoH,
                fps = fps,
                // Keep the v0.19.33 AirPlay feature negotiation while validating the
                // session-start regression. The rounded mask is applied locally by the overlay.
                useIphoneCornerMask = false,
            ),
            hevc = false,
            manufacturer = "xcertplay",
            model = "JSplay19",
            // CarPlay 主屏车机品牌图标槽位：随 /info 下发（iPhone 连接时读取，重连生效）
            oemLabel = CAR_BUTTON_ICON_LABEL,
            icons = airPlayIcon?.let { listOf(it) } ?: emptyList(),
        )
        val pairings = AirPlayPersistence.loadPairings(this) { identifier, longTermPublicKey ->
            AirPlayPersistence.savePairing(this, identifier, longTermPublicKey)
        }
        val mediaSink = AndroidMediaSink(
            surface = null,
            videoWidth = videoW,
            videoHeight = videoH,
            videoFps = fps,
            context = this,
            // 音频链路事件直通 App 日志：格式协商结果、解码器选择（FDK/MediaCodec/Opus 不可用）、
            // 5 秒统计（收包率/欠载）——实测时「到底走没走音频通道」一眼可见。
            onAudioDiagnostic = { message -> log(message) },
        )
        sink = mediaSink
        liveSink = mediaSink
        // 画面 Surface 由 SurfaceHolder 回调/窗口焦点/状态变化幂等接上。
        val media = CarPlayMediaEngine(sink = mediaSink)
        val modeLabel = when (connectionMode) {
            ConnectionMode.CAR_HOTSPOT -> "car-hotspot"
            ConnectionMode.SAME_NETWORK -> "same-network"
            ConnectionMode.IPHONE_HOTSPOT -> "iphone-hotspot"
        }
        log(
            "启动无线 CarPlay mode=$modeLabel name=$displayName " +
                "mfi=LOCAL display=${videoW}x${videoH}@${fps}fps " +
                "requestedFps=$requestedFps refreshRateHz=${"%.1f".format(Locale.US, refreshRateHz)} " +
                "H.264 (panel=${screenW}x${screenH}) " +
                (if (sameNetwork) {
                    "ssid=${manualSsid.ifEmpty { "<当前网络>"} } channel=${if (channel > 0) "$channel" else "auto(扫描)"}"
                } else {
                    "channel=${if (channel > 0) "$channel" else "auto"}"
                }),
        )
        val next = CarPlayController(
            context = this,
            config = runtime,
            airPlayConfig = config,
            identity = identity,
            pairings = pairings,
            listener = sessionListener,
            media = media,
            reportStatus = { status -> handleStatus(status) },
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
        )
        controller = next
        liveController = next
        liveSession = null
        next.start()
    }

    /** 屏幕分辨率自适应；H.264 编码要求宽高为偶数。 */
    private fun displaySize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val w = (metrics.widthPixels / 2 * 2).coerceAtLeast(320)
        val h = (metrics.heightPixels / 2 * 2).coerceAtLeast(240)
        return w to h
    }

    private fun onStop(@Suppress("UNUSED_PARAMETER") view: View) {
        log("停止会话")
        cancelVideoWatchdog()
        activeSession = null
        resetTouchState()
        hideVideoMode()
        runCatching { controller?.close() }
        controller = null
        liveController = null
        liveSession = null
        runCatching { sink?.close() }
        sink = null
        liveSink = null
        setStatus("已停止")
    }

    // sessionListener 已提升到 companion（进程级）：Activity 重建后回调仍指向当前实例。

    /** 连接成功 → 隐藏控制面板，全屏显示画面（主题已是真全屏，无需动态切窗口标志）。 */
    private fun showVideoMode() {
        controlPanel.visibility = View.GONE
        // 收起输入法：避免键盘残留抢触摸/挡画面
        runCatching {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(videoView.windowToken, 0)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log("已进入全屏画面模式（点按画面即回传触摸）")
        scheduleVideoWatchdog()
    }

    /**
     * 会话半途夭折兑底：onSessionActive 已触发（控制面板隐藏）但视频流从未建立
     * （iPhone 端握手后未发 SETUP，或连接重试中途断链）。10 秒无帧则退回控制面板，
     * 否则用户回桌面再进来只能看到纯黑屏（黑底板+无帧），UI 卡死无出路。
     */
    private fun scheduleVideoWatchdog() {
        cancelVideoWatchdog()
        videoFrameArrived = false
        val runnable = Runnable {
            videoWatchdogRunnable = null
            if (!videoFrameArrived && !isFinishing) {
                val session = activeSession ?: liveSession
                if (session != null) {
                    // AirPlay can finish its video SETUP after the control session becomes
                    // active, especially after returning from the car home screen. Never tear
                    // down a live session merely because its first frame is late.
                    videoFrameArrived = true
                    log("看门狗：5 秒未收到首帧，但 AirPlay 会话仍存活，保留连接并重绑画面")
                    setStatus("CarPlay 会话运行中")
                    attachSurfaceToSink("videoWatchdog", force = true)
                } else {
                    log("看门狗：会话激活后 5 秒无视频帧，退回控制面板（半途夭折兑底）")
                    setStatus("未收到画面，已退回控制面板")
                    hideVideoMode()
                    // No AirPlay session exists, so this is a genuinely abandoned handshake.
                    runCatching { controller?.close() }
                    controller = null
                    liveController = null
                    liveSession = null
                    activeSession = null
                    resetTouchState()
                }
            }
        }
        videoWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, VIDEO_WATCHDOG_MS)
    }

    private fun cancelVideoWatchdog() {
        videoWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        videoWatchdogRunnable = null
    }

    /** 首帧到达（sink 诊断回调）→ 看门狗任务解除，视频模式坐实。 */
    private fun onFirstVideoFrame() {
        if (!videoFrameArrived) {
            videoFrameArrived = true
            cancelVideoWatchdog()
        }
    }

    private fun hideVideoMode() {
        controlPanel.visibility = View.VISIBLE
    }

    /** 幂等接画面：surface 有效且 sink 存在时接到 SCREEN_MAIN(110) 解码器；未变化不重复接。
     *
     * [force] 用于后台回前台等场景：E43 上 SurfaceHolder 回调可能不重发，
     * 但 Holder 仍有有效 Surface——此时无条件重试 setSurface。 */
    private fun attachSurfaceToSink(source: String, force: Boolean = false) {
        val holderSurface = videoView.holder.surface
        if (videoSurface == null || !videoSurface!!.isValid) {
            videoSurface = holderSurface.takeIf { it.isValid }
        }
        val surface = videoSurface
        val currentSink = sink
        if (surface != null && surface.isValid && currentSink != null) {
            if (force || surface !== attachedSurface || currentSink !== attachedSink) {
                attachedSurface = surface
                attachedSink = currentSink
                currentSink.setSurface(SCREEN_MAIN, surface)
                log("画面已接上解码器（$source）")
            }
        } else if (attachedSurface != null || attachedSink != null) {
            attachedSurface = null
            attachedSink = null
            log("画面已断开（$source surface=${surface != null && surface.isValid} sink=${currentSink != null}）")
        }
    }

    /** Touch coordinates go to the iPhone over the encrypted AirPlay HID event channel. */
    @SuppressLint("ClickableViewAccessibility")
    private fun sendVideoTouch(event: MotionEvent): Boolean {
        val session = activeSession ?: return false
        val inputEventNs = System.nanoTime()
        val width = videoView.width.coerceAtLeast(1)
        val height = videoView.height.coerceAtLeast(1)
        val frame = touchMapper.map(
            event,
            CarPlayVideoLayout(0f, 0f, width.toFloat(), height.toFloat()),
        ) ?: return true
        if (!frame.changed) return true
        if (frame.edge) {
            enqueueTouchEdge(session, frame.contacts, inputEventNs)
        } else {
            pendingTouchMove.set(
                PendingTouchMove(
                    session = session,
                    contacts = frame.contacts,
                    inputEventNs = inputEventNs,
                    generation = touchGeneration.get(),
                ),
            )
            scheduleTouchMoveDrain()
        }
        return true
    }

    private fun enqueueTouchEdge(session: AirPlaySession, contacts: List<AirPlayContact>, inputEventNs: Long) {
        val generation = touchGeneration.incrementAndGet()
        val pending = pendingTouchMove.getAndSet(null)
        runCatching {
            touchExecutor.execute {
                val executorStartNs = System.nanoTime()
                if (pending != null && pending.generation < generation &&
                    activeSession === pending.session && touchGeneration.get() == generation
                ) {
                    pending.session.sendTouch(pending.contacts, pending.inputEventNs, executorStartNs)
                }
                if (activeSession === session && touchGeneration.get() == generation) {
                    session.sendTouch(contacts, inputEventNs, executorStartNs)
                }
            }
        }
    }

    private fun scheduleTouchMoveDrain() {
        if (!touchMoveDrainScheduled.compareAndSet(false, true)) return
        try {
            touchExecutor.execute {
                try {
                    pendingTouchMove.getAndSet(null)?.let { move ->
                        if (activeSession === move.session && touchGeneration.get() == move.generation) {
                            move.session.sendTouch(move.contacts, move.inputEventNs, System.nanoTime())
                        }
                    }
                } finally {
                    touchMoveDrainScheduled.set(false)
                    if (pendingTouchMove.get() != null) scheduleTouchMoveDrain()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            touchMoveDrainScheduled.set(false)
        }
    }

    private fun resetTouchState() {
        touchGeneration.incrementAndGet()
        pendingTouchMove.set(null)
        touchMapper.reset()
    }

    /** 设置弹窗（银色主题）：设备名称（iPhone CarPlay 列表显示）+ 运行日志查看。 */
    @SuppressLint("SetTextI18n")
    private fun showSettingsDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (density * value).toInt()

        val nameInput = EditText(this).apply {
            hint = "设备名称（iPhone CarPlay 列表里显示）"
            setText(AirPlayPersistence.loadDeviceName(this@MainActivity))
            setSingleLine()
            textSize = 14f
            setTextColor(0xFFE8EBEF.toInt())
            setHintTextColor(0xFF7E858E.toInt())
            setBackgroundResource(R.drawable.input_purple)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFA6ACB5.toInt())
            textSize = 10f
            setLineSpacing(dp(2).toFloat(), 1f)
            text = logLines.joinToString("\n").ifEmpty { "（暂无日志）" }
        }
        val logScroll = ScrollView(this).apply {
            addView(logView)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(0xFFD5D9DF.toInt())
            textSize = 12f
        }

        // 车机图标选择行：三个大卡片（图标预览 + 名称），点选即保存并高亮
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val iconCards = linkedMapOf<CarButtonIcon, LinearLayout>()
        val iconLabels = linkedMapOf<CarButtonIcon, TextView>()
        fun updateIconSelection(selectedIcon: CarButtonIcon) {
            iconCards.forEach { (choice, card) ->
                val selected = choice == selectedIcon
                card.setBackgroundResource(
                    if (selected) R.drawable.card_selected else R.drawable.panel_purple_dark,
                )
                iconLabels[choice]?.setTextColor(
                    if (selected) 0xFF2A2E36.toInt() else 0xFFD5D9DF.toInt(),
                )
            }
        }
        CarButtonIcon.entries.forEach { choice ->
            if (iconRow.childCount > 0) {
                iconRow.addView(Space(this), LinearLayout.LayoutParams(dp(10), 1))
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener {
                    if (loadCarButtonIcon() == choice) return@setOnClickListener
                    AirPlayPersistence.saveOemIcon(this@MainActivity, choice.key)
                    updateIconSelection(choice)
                    log("车机图标已选「${choice.displayName}」：下次连接起 iPhone CarPlay 槽位显示它（需断开重连生效）")
                }
            }
            val preview = runCatching {
                resources.openRawResource(choice.rawRes).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (preview != null) {
                card.addView(
                    ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(preview)
                    },
                    LinearLayout.LayoutParams(dp(96), dp(96)),
                )
            }
            val title = TextView(this).apply {
                text = choice.displayName
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
                gravity = android.view.Gravity.CENTER
            }
            iconCards[choice] = card
            iconLabels[choice] = title
            card.addView(title)
            iconRow.addView(
                card,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        updateIconSelection(loadCarButtonIcon())

        // 音频通道选择行：蓝牙 A2DP（默认）/ 强制 CarPlay WiFi 音频，点选即保存并高亮
        val audioRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun renderAudioChoices() {
            audioRow.removeAllViews()
            val current = AirPlayPersistence.loadAudioChannel(this)
            val choices = listOf(
                AirPlayPersistence.AUDIO_CHANNEL_BLUETOOTH to "蓝牙音频（默认）",
                AirPlayPersistence.AUDIO_CHANNEL_CARPLAY to "强制 CarPlay 音频",
            )
            choices.forEach { (channel, title) ->
                if (audioRow.childCount > 0) {
                    audioRow.addView(Space(this), LinearLayout.LayoutParams(dp(10), 1))
                }
                val selected = channel == current
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setBackgroundResource(if (selected) R.drawable.card_selected else R.drawable.panel_purple_dark)
                    setOnClickListener {
                        AirPlayPersistence.saveAudioChannel(this@MainActivity, channel)
                        if (channel == AirPlayPersistence.AUDIO_CHANNEL_CARPLAY) {
                            log("音频通道已选「强制 CarPlay」：下次连接起自动断开蓝牙媒体音乐，音频走 WiFi 高音质流；电话/Siri 仍走蓝牙免提")
                        } else {
                            log("音频通道已选「蓝牙」：音乐走蓝牙 A2DP（如需恢复，在系统蓝牙里重连媒体音频即可）")
                        }
                        renderAudioChoices()
                    }
                }
                card.addView(TextView(this).apply {
                    text = title
                    setTextColor(if (selected) 0xFF2A2E36.toInt() else 0xFFD5D9DF.toInt())
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                })
                card.addView(TextView(this).apply {
                    text = if (channel == AirPlayPersistence.AUDIO_CHANNEL_CARPLAY) {
                        "48kHz 高音质 · WiFi"
                    } else {
                        "SBC 普通音质 · 蓝牙"
                    }
                    setTextColor(if (selected) 0xFF3A4048.toInt() else 0xFF8E959E.toInt())
                    textSize = 11f
                    setPadding(0, dp(4), 0, 0)
                    gravity = android.view.Gravity.CENTER
                })
                audioRow.addView(
                    card,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        }
        renderAudioChoices()

        val fpsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun renderFpsChoices() {
            fpsRow.removeAllViews()
            val current = AirPlayPersistence.loadFps(this)
            listOf(30, 60).forEach { value ->
                if (fpsRow.childCount > 0) {
                    fpsRow.addView(Space(this), LinearLayout.LayoutParams(dp(10), 1))
                }
                val selected = value == current
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setBackgroundResource(if (selected) R.drawable.card_selected else R.drawable.panel_purple_dark)
                    setOnClickListener {
                        if (AirPlayPersistence.loadFps(this@MainActivity) == value) return@setOnClickListener
                        AirPlayPersistence.saveFps(this@MainActivity, value)
                        if (controller != null) {
                            log("视频档位已保存为 ${value}FPS；当前 CarPlay 会话保持不变，停止后重新连接才会协商生效")
                        } else {
                            log("视频档位已保存为 ${value}FPS；下次 CarPlay 连接将使用该档位")
                        }
                        renderFpsChoices()
                    }
                }
                card.addView(TextView(this).apply {
                    text = if (value == 60) "60FPS 高帧率" else "30FPS 兼容"
                    setTextColor(if (selected) 0xFF2A2E36.toInt() else 0xFFD5D9DF.toInt())
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                })
                card.addView(TextView(this).apply {
                    text = if (value == 60) "默认 · 需要重新连接" else "低负载 · 需要重新连接"
                    setTextColor(if (selected) 0xFF3A4048.toInt() else 0xFF8E959E.toInt())
                    textSize = 11f
                    setPadding(0, dp(4), 0, 0)
                    gravity = android.view.Gravity.CENTER
                })
                fpsRow.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        renderFpsChoices()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("设备名称（改名后下次连接生效）"))
            addView(
                nameInput,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) },
            )
            addView(label("车机图标（iPhone CarPlay 主屏槽位，选完断开重连生效）").apply { setPadding(0, dp(12), 0, 0) })
            addView(
                iconRow,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) },
            )
            addView(label("音频通道（音乐从哪出声；电话/Siri 始终走蓝牙免提不受影响）").apply { setPadding(0, dp(12), 0, 0) })
            addView(
                audioRow,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) },
            )
            addView(label("视频帧率档位（切换后需停止并重新连接 CarPlay）").apply { setPadding(0, dp(12), 0, 0) })
            addView(
                fpsRow,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) },
            )
            addView(label("运行日志").apply { setPadding(0, dp(12), 0, 0) })
            addView(
                logScroll,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)),
            )
        }

        val maxSettingsContentHeight = minOf(
            (resources.displayMetrics.heightPixels * 0.56f).toInt(),
            dp(520),
        )
        val settingsContent = ScrollView(this).apply {
            isFillViewport = false
            addView(
                content,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val (dialog, root) = buildSilverDialog(
            title = "设置",
            content = settingsContent,
            showTopCloseButton = true,
        )
        settingsContent.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxSettingsContentHeight,
        ).apply { topMargin = dp(8) }
        dialogLogText = logView
        dialog.setOnDismissListener { dialogLogText = null }
        addDialogButtonRow(
            root,
            silverDialogButton(dialog, "保存名称", primary = true) {
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    AirPlayPersistence.saveDeviceName(this, name)
                    log("设备名称已保存为「$name」：下次连接起 iPhone 显示该名称")
                }
            },
            silverDialogButton(dialog, "清空日志", primary = false, dismissAfter = false) {
                logLines.clear()
                logView.text = "（暂无日志）"
            },
        )
        showSilverDialog(dialog, widthFraction = 0.85f, maxDp = 1000)
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * 选择连接手机弹窗：列出车机蓝牙（Anw 工厂栈）已配对设备，点选哪台就连哪台。
     *
     * 换机场景的主力入口：选定后立即保存为首选 MAC 并触发一键连接（iAP2 蓝牙握手
     * → AirPlay → CarPlay 画面）；未换手机时无需打开——上次的记忆自动生效。
     * 「进入配对模式」保留给首次与新手机配对；「清除记住」回到自动选择。
     */
    private fun showPhoneSelectorDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (density * value).toInt()

        val listHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listHolder.addView(TextView(this).apply {
            text = "正在读取车机蓝牙配对列表…"
            setTextColor(0xFFA6ACB5.toInt())
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        })
        val scroll = ScrollView(this).apply { addView(listHolder) }

        val (dialog, root) = buildSilverDialog(
            title = "选择连接的手机",
            message = "点选要用蓝牙握手的 iPhone：选定后立即开始连接，之后会一直记住这台。",
            content = scroll,
        )
        addDialogButtonRow(
            root,
            silverDialogButton(dialog, "进入配对模式", primary = false) {
                requestPairMode()
            },
            silverDialogButton(dialog, "清除记住", primary = false, dismissAfter = false) {
                AirPlayPersistence.clearLastWirelessIphoneAddress(this)
                log("已清除记住的手机：下次连接自动选择（多台时请删掉不用的配对）")
                renderPairedPhones(dialog, listHolder, cachedPairedPhones)
            },
            silverDialogButton(dialog, "关闭", primary = false) {},
        )
        showSilverDialog(dialog, widthFraction = 0.85f, maxDp = 900)

        // binder 调用不能占主线程：后台读配对列表，回主线程渲染。
        Thread({
            val link = runCatching {
                com.shilapi.xcertplay.transport.anw.AnwPhoneLinkClient.bind(this)
            }.getOrNull()
            val paired: List<com.shilapi.xcertplay.transport.anw.AnwPhoneLinkClient.PairedDevice>? =
                if (link != null) {
                    runCatching { link.getPairedList() }.getOrNull()
                } else {
                    null
                }
            runCatching { link?.close() }
            cachedPairedPhones = paired
            post {
                if (dialog.isShowing) renderPairedPhones(dialog, listHolder, paired)
            }
        }, "anw-phone-list").apply { isDaemon = true }.start()
    }

    /** 渲染配对设备列表：当前记住的那台亮银高亮，其余深银。点选即保存并连接。 */
    private fun renderPairedPhones(
        dialog: Dialog,
        holder: LinearLayout,
        paired: List<com.shilapi.xcertplay.transport.anw.AnwPhoneLinkClient.PairedDevice>?,
    ) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (density * value).toInt()
        holder.removeAllViews()

        fun note(text: String) {
            holder.addView(TextView(this).apply {
                this.text = text
                setTextColor(0xFFA6ACB5.toInt())
                textSize = 13f
                setPadding(0, dp(8), 0, dp(8))
            })
        }

        if (paired == null) {
            note("读不到车机蓝牙配对列表（Anw 工厂蓝牙服务未响应）。可先用下方「进入配对模式」配对后重试。")
            return
        }
        if (paired.isEmpty()) {
            note("车机蓝牙还没有任何配对设备：点下方「进入配对模式」，到 iPhone 蓝牙设置里完成配对后再回来选择。")
            return
        }
        val last = AirPlayPersistence.loadLastWirelessIphoneAddress(this)
        paired.forEach { device ->
            val selected = last != null && device.address.equals(last, ignoreCase = true)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(if (selected) R.drawable.card_selected else R.drawable.panel_purple_dark)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    AirPlayPersistence.saveLastWirelessIphoneAddress(this@MainActivity, device.address)
                    log("已选定连接手机：${device.name ?: device.address}（蓝牙 ${device.address}）——立即开始无线连接")
                    dialog.dismiss()
                    if (controller != null) {
                        log("停止当前会话，切换到刚选定的手机")
                        onStop(videoView)
                    }
                    onWirelessStart()
                }
            }
            row.addView(TextView(this).apply {
                text = device.name ?: "未命名设备"
                setTextColor(if (selected) 0xFF2A2E36.toInt() else 0xFFD5D9DF.toInt())
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                text = device.address + if (selected) " · 已记住（当前首选）" else ""
                setTextColor(if (selected) 0xFF3A4048.toInt() else 0xFF8E959E.toInt())
                textSize = 12f
            })
            holder.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) },
            )
        }
    }

    private fun onStatusInternal(status: CarPlayStatus) {
        val text = status.describeForShell()
        setStatus(text)
        log("状态: $text")
        if (status is CarPlayStatus.Failed &&
            AirPlayPersistence.loadLastWirelessIphoneAddress(this) != null
        ) {
            // 无线建立失败：遗忘“上次 iPhone”，避免记住的手机不在场时反复撞同一台。
            AirPlayPersistence.clearLastWirelessIphoneAddress(this)
            log("无线建立失败：已遗忘上次 iPhone 记录，下次连接重新自动选择（多台时请在车机蓝牙删除不用的配对）")
        }
        // 状态变化时幂等重试接画面（会话就绪但 surface 回调没发的情形）
        attachSurfaceToSink("statusChanged")
    }

    private fun CarPlayStatus.describeForShell(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> "发现 MFi"
        CarPlayStatus.WaitingForMfi -> "等待 MFi"
        CarPlayStatus.RequestingMfiPermission -> "请求 MFi 权限"
        CarPlayStatus.MfiReady -> "MFi 就绪"
        CarPlayStatus.StartingHotspot -> "启动热点"
        is CarPlayStatus.HotspotReady ->
            "${if (backend == "Same network") "网络就绪" else "热点就绪"} " +
                "$ssid $band ch$channel ($backend)"
        CarPlayStatus.WaitingForPairedIphone -> "等待已配对 iPhone"
        CarPlayStatus.ConnectingBluetooth -> "连接蓝牙（Anw 工厂栈）"
        CarPlayStatus.RunningWireless -> "无线建立中"
        CarPlayStatus.WirelessActive -> "无线已激活"
        CarPlayStatus.DiscoveringIphone -> "发现 iPhone"
        CarPlayStatus.WaitingForIphone -> "等待 iPhone"
        CarPlayStatus.RequestingIphonePermission -> "请求 USB 权限"
        CarPlayStatus.WaitingForReenumeration -> "等待重新枚举"
        CarPlayStatus.SelectingConfiguration -> "选择配置"
        CarPlayStatus.OpeningDataPaths -> "打开数据通道"
        CarPlayStatus.Pairing -> "配对中"
        CarPlayStatus.ConnectingControl -> "连接控制通道"
        CarPlayStatus.AttachingNetwork -> "挂接网络"
        CarPlayStatus.RunningControl -> "控制通道运行"
        CarPlayStatus.ControlEnded -> "控制通道结束"
        is CarPlayStatus.Failed -> "失败: $message${if (wifiResetRequired) "（需重置Wi-Fi）" else ""}"
    }

    // ---------- 基础设施 ----------

    private fun setStatus(text: String) {
        post {
            if (displayedStatusText != text) {
                displayedStatusText = text
                statusText.text = text
            }
        }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /**
     * 减负版日志：主面板不再常驻日志区（已移入设置弹窗）；弹窗打开期间 500ms 合帧刷新。
     * E43 单核上每条日志的 setText 全量重排版足以 ANR 主线程（实测堆栈卡在
     * StaticLayout.generate）。缓冲保留最近 120 行，进程级存活（Activity 重建不丢）。
     */
    private fun log(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendLogLine(message)
        } else {
            mainHandler.post { appendLogLine(message) }
        }
    }

    private fun appendLogLine(message: String) {
        logLines.addLast("${logTime.format(Date())}  $message")
        while (logLines.size > LOG_BUFFER_LINES) logLines.removeFirst()
        scheduleLogRefresh()
    }

    private fun scheduleLogRefresh() {
        if (dialogLogText == null || logRefreshScheduled) return
        logRefreshScheduled = true
        mainHandler.postDelayed({
            logRefreshScheduled = false
            refreshDialogLog()
        }, LOG_REFRESH_INTERVAL_MS)
    }

    /** 已配对手机列表缓存（选择弹窗用）：「清除记住」后重渲染免二次读取。 */
    private var cachedPairedPhones: List<com.shilapi.xcertplay.transport.anw.AnwPhoneLinkClient.PairedDevice>? = null

    /** 设置弹窗打开时刷新其日志区；未打开时什么都不做（零 UI 开销）。 */
    private fun refreshDialogLog() {
        val view = dialogLogText ?: return
        view.text = logLines.joinToString("\n").ifEmpty { "（暂无日志）" }
        (view.parent as? ScrollView)?.let { scroll ->
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /** 凭据安装已由 CarPlayApp.provisionBundledMfi 在应用启动时完成（assets → filesDir）。 */
    private fun installOfflineMfiFromAssets() {
        val target = File(filesDir, "offline-mfi")
        val ready = File(target, "identity.pk8").isFile && File(target, "certificate.p7b").isFile
        if (ready) {
            log("MFi 凭据已就绪: ${target.path}")
        } else {
            log("未内置 MFi 凭据（assets/offline-mfi 缺失）：")
            log("  CarPlay 认证阶段会失败，但 Anw 蓝牙/iAP2/AirPlay 流程可完整验证")
        }
    }

    private fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    /** 首次自动建热点时的默认 SSID：JSplay-XXXX（取身份后四位）。 */
    private fun defaultSsid(): String {
        val id = deviceId(AirPlayPersistence.loadIdentity(this)).replace(":", "")
        return "JSplay-" + id.takeLast(4)
    }

    /**
     * iPhone CarPlay 主屏的「车机品牌图标」槽位（DiPlay 上游给 BYD 用的那个位置）。
     * 图标经 /info 的 oemIcons 下发；iOS 要求 label 非空否则整个隐藏，
     * 故固定用应用名（不随图标换品牌文字）。
     */
    internal enum class CarButtonIcon(
        val key: String,
        val displayName: String,
        val rawRes: Int,
    ) {
        JIASHENG(AirPlayPersistence.OEM_ICON_JIASHENG, "加圣桌面（默认）", R.raw.ic_car_home_jiasheng),
        CITROEN(AirPlayPersistence.OEM_ICON_CITROEN, "雪铁龙", R.raw.ic_car_home_citroen),
        PEUGEOT(AirPlayPersistence.OEM_ICON_PEUGEOT, "标致", R.raw.ic_car_home_peugeot),
    }

    /** 当前选定的车机图标（持久化；默认加圣桌面）。 */
    private fun loadCarButtonIcon(): CarButtonIcon =
        CarButtonIcon.entries.firstOrNull { it.key == AirPlayPersistence.loadOemIcon(this) }
            ?: CarButtonIcon.JIASHENG

    /**
     * 读图标 PNG → 校验方形 → 超过 192px 则缩放。上游教训（DiPlay #567）：
     * 过大的图标（如 1024px 数 MB）会让 iPhone 读 /info 后直接拒绝连接。
     */
    private fun buildAirPlayIcon(choice: CarButtonIcon): AirPlayIcon? = runCatching {
        val bytes = resources.openRawResource(choice.rawRes).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val size = bounds.outWidth
        if (size <= 0 || size != bounds.outHeight) return@runCatching null
        if (size <= MAX_CAR_ICON_PIXELS) return@runCatching AirPlayIcon(size, size, bytes)
        var sample = 1
        while (size / (sample * 2) >= MAX_CAR_ICON_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@runCatching null
        val scaled = Bitmap.createScaledBitmap(decoded, MAX_CAR_ICON_PIXELS, MAX_CAR_ICON_PIXELS, true)
        if (scaled !== decoded) decoded.recycle()
        val encoded = ByteArrayOutputStream().use { output ->
            if (scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
        }
        scaled.recycle()
        encoded?.let { AirPlayIcon(MAX_CAR_ICON_PIXELS, MAX_CAR_ICON_PIXELS, it) }
    }.getOrNull()

    /** 切换到非车机热点模式时自动关闭车机热点（恢复车机 WiFi）。 */
    private fun maybeDisableHotspotForMode(mode: ConnectionMode) {
        if (mode == ConnectionMode.CAR_HOTSPOT) return
        Thread({
            val enabled = runCatching { ApHotspot.isApEnabled(this) }.getOrDefault(false)
            if (!enabled) return@Thread
            log("已选其他模式：关闭车机热点，恢复车机 WiFi…")
            val ok = runCatching { ApHotspot.setApEnabled(this, false) }.getOrDefault(false)
            post {
                log(if (ok) "车机热点已关闭（车机 WiFi 可用）" else "车机热点关闭失败，可手动到系统设置关闭")
            }
        }, "disable-ap").apply { isDaemon = true }.start()
    }

    @SuppressLint("SetTextI18n")
    private fun selectMode(mode: ConnectionMode, silent: Boolean = false) {
        if (connectionMode == mode) return
        if (connectionMode != ConnectionMode.IPHONE_HOTSPOT) {
            AirPlayPersistence.saveConnectionCredentials(
                this,
                connectionMode.name,
                ssidInput.text.toString().trim(),
                passphraseInput.text.toString(),
            )
        }
        connectionMode = mode
        AirPlayPersistence.saveConnectionMode(this, mode.name)
        val credentials = if (mode == ConnectionMode.IPHONE_HOTSPOT) null else {
            AirPlayPersistence.loadConnectionCredentials(this, mode.name)
        }
        if (credentials != null) {
            ssidInput.setText(credentials.first)
            passphraseInput.setText(credentials.second)
        } else {
            // A currently connected network is checked at connect time; it is not necessarily
            // the selected topology's network, so never copy it into another mode's fields.
            ssidInput.setText("")
            passphraseInput.setText("")
        }
        channelInput.setText(
            if (mode == ConnectionMode.CAR_HOTSPOT) {
                AirPlayPersistence.loadManualHotspotChannel(this).takeIf { it > 0 }?.toString().orEmpty()
            } else "",
        )
        refreshModeCards()
        // 切到非车机热点模式时自动关热点（恢复车机 WiFi）；切回车机热点不动（等一键连接时弹窗确认）
        maybeDisableHotspotForMode(mode)
        if (!silent) {
            when (mode) {
                ConnectionMode.CAR_HOTSPOT -> {
                    log("已选「车机热点」：连接时自动开热点，iPhone 自动加入（WiFi 会断开）")
                }
                ConnectionMode.SAME_NETWORK -> {
                    log("已选「同一 WiFi」：不开热点，车机与 iPhone 连同一个路由器")
                    refreshCurrentWifiHint()
                }
                ConnectionMode.IPHONE_HOTSPOT -> {
                    log("已选「iPhone 热点」：请先在车机系统 Wi-Fi 中连接 iPhone 热点，DiPlay 只读取当前网络")
                }
            }
        }
    }

    /** 刷新三张选择卡的高亮与主按钮文案。 */
    @SuppressLint("SetTextI18n")
    private fun refreshModeCards() {
        val selectedBg = resources.getDrawable(R.drawable.card_selected)
        val normalBg = resources.getDrawable(R.drawable.panel_purple_dark)
        cardHotspot.background = if (connectionMode == ConnectionMode.CAR_HOTSPOT) selectedBg else normalBg
        cardSamenet.background = if (connectionMode == ConnectionMode.SAME_NETWORK) selectedBg else normalBg
        cardIphoneHotspot.background =
            if (connectionMode == ConnectionMode.IPHONE_HOTSPOT) selectedBg else normalBg
        wirelessButton.text = when (connectionMode) {
            ConnectionMode.CAR_HOTSPOT -> "一键连接（车机热点）"
            ConnectionMode.SAME_NETWORK -> "一键连接（同一 WiFi）"
            ConnectionMode.IPHONE_HOTSPOT -> "一键连接（iPhone 热点）"
        }
        ssidInput.hint = when (connectionMode) {
            ConnectionMode.CAR_HOTSPOT -> "车机热点名称（可留空用系统配置）"
            ConnectionMode.SAME_NETWORK -> "同一 WiFi 名称（留空使用当前网络）"
            ConnectionMode.IPHONE_HOTSPOT -> "先在车机系统中连接 iPhone 热点"
        }
        passphraseInput.hint = when (connectionMode) {
            ConnectionMode.CAR_HOTSPOT -> "车机热点密码（系统配置可自动读取）"
            ConnectionMode.SAME_NETWORK -> "Wi-Fi 密码（系统可读取时免填）"
            ConnectionMode.IPHONE_HOTSPOT -> "当前网络密码备用（通常可由系统读取）"
        }
        ssidInput.isEnabled = connectionMode != ConnectionMode.IPHONE_HOTSPOT
        passphraseInput.isEnabled = true
        channelInput.isEnabled = connectionMode != ConnectionMode.IPHONE_HOTSPOT
    }

    /** 读取当前连接的 WiFi 名称并回填 SSID 框；密码优先自动读系统保存值，读不到才需手填。 */
    private fun refreshCurrentWifiHint() {
        Thread({
            val name = runCatching { currentWifiName() }.getOrNull()
            post {
                if (name != null) {
                    ssidInput.setText(name)
                    log("当前连接的 WiFi：$name（密码自动读取系统保存值，读不到时才需手填）")
                } else {
                    log("未读到当前 WiFi：请先让车机连上 WiFi，或手动填 SSID")
                }
            }
        }, "read-wifi").apply { isDaemon = true }.start()
    }

    /** 当前连接的 WiFi SSID；读不到（未连/无权限）返回 null。 */
    private fun currentWifiName(): String? {
        val manager = getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return null
        val info = runCatching { manager.connectionInfo }.getOrNull() ?: return null
        val ssid = info?.ssid?.let { raw ->
            if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.substring(1, raw.length - 1)
            } else {
                raw
            }
        } ?: return null
        return ssid.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    /** Current station SSID plus a non-zero address; an SSID alone is not an established link. */
    private fun currentWifiConnection(): Pair<String, Int>? {
        val manager = getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return null
        val info = runCatching { manager.connectionInfo }.getOrNull() ?: return null
        val rawSsid = info.ssid ?: return null
        val name = if (rawSsid.length >= 2 && rawSsid.startsWith("\"") && rawSsid.endsWith("\"")) {
            rawSsid.substring(1, rawSsid.length - 1)
        } else {
            rawSsid
        }
        if (name.isBlank() || name == "<unknown ssid>") return null
        val ipAddress = info.ipAddress
        return if (ipAddress == 0) null else name to ipAddress
    }

    /** 恢复上次选择的组网方式；兼容旧版的同网开关迁移。 */
    private fun loadConnectionMode(): ConnectionMode {
        val legacySamenet = AirPlayPersistence.loadSameNetworkEnabled(this)
        val stored = AirPlayPersistence.loadConnectionMode(this)
        return when {
            stored == ConnectionMode.SAME_NETWORK.name ||
                (stored.isNullOrBlank() && legacySamenet) -> ConnectionMode.SAME_NETWORK
            stored == ConnectionMode.IPHONE_HOTSPOT.name -> ConnectionMode.IPHONE_HOTSPOT
            else -> ConnectionMode.CAR_HOTSPOT
        }
    }

    /** 蓝牙配对按钮：让车机进入配对模式（iPhone 蓝牙里搜到车机后配对）。 */
    private fun requestPairMode() {
        log("让车机蓝牙进入配对模式…")
        setStatus("等待 iPhone 蓝牙配对")
        setBluetoothPairMode(1)
    }

    private fun setBluetoothPairMode(mode: Int) {
        Thread({
            var link: com.shilapi.xcertplay.transport.anw.AnwPhoneLinkClient? = null
            try {
                link = com.shilapi.xcertplay.transport.anw.AnwPhoneLinkClient.bind(this)
                if (link != null) {
                    val result = link.setPairMode(mode)
                    post {
                        log("anw setPairMode($mode) result=$result")
                        if (mode == 1 && result >= 0) {
                            log("车机蓝牙已进入配对模式：请在 iPhone 蓝牙设置里找到车机并配对")
                        } else if (result < 0) {
                            log("车机拒绝切换蓝牙配对模式")
                        }
                    }
                } else {
                    post {
                        log("标准蓝牙栈：请直接在系统蓝牙设置里配对（无需配对模式）")
                    }
                }
            } catch (error: Exception) {
                post { log("setPairMode 失败: ${error.message}") }
            } finally {
                runCatching { link?.close() }
            }
        }, "anw-pairmode").apply { isDaemon = true }.start()
    }

    private companion object {
        // ---------- 常量（须在依赖它们的属性之前声明） ----------

        /** 日志缓冲行数与合帧间隔：E43 单核下全量重排版是大开销，必须限流。 */
        const val LOG_BUFFER_LINES = 120
        const val LOG_REFRESH_INTERVAL_MS = 500L

        /** CarPlay 车机图标槽位的像素上限（上游 DiPlay #567：过大会被 iPhone 拒连）。 */
        const val MAX_CAR_ICON_PIXELS = 192

        /** 车机图标槽位固定标签：iOS 空标签会隐藏整个图标，故恒为应用名、不随图标变。 */
        const val CAR_BUTTON_ICON_LABEL = "加圣桌面"

        // ---------- 进程级会话延续（重开 App 免握手直回 CarPlay） ----------

        /** 当前存活的 Activity 实例：进程级回调（会话/状态/日志）通过它转发到 UI。 */
        @Volatile var instance: MainActivity? = null

        /** 仍在运行的会话三件套：Activity 重建后 onCreate 直接领回。 */
        @Volatile var liveController: CarPlayController? = null
        @Volatile var liveSink: AndroidMediaSink? = null
        @Volatile var liveSession: AirPlaySession? = null

        /** 主线程 Handler：进程级，回调与日志合帧共用（不随 Activity 重建失效）。 */
        val mainHandler = Handler(Looper.getMainLooper())

        /** 日志缓冲（进程级）：设置弹窗打开时才渲染，Activity 重建不丢历史。 */
        private val logTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        private val logLines = java.util.ArrayDeque<String>(LOG_BUFFER_LINES)
        private var logRefreshScheduled = false

        fun handleStatus(status: CarPlayStatus) {
            mainHandler.post { instance?.onStatusInternal(status) }
        }

        /** 进程级会话回调：不随 Activity 重建失效，始终转发给当前实例。 */
        val sessionListener = object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                liveSession = session
                mainHandler.post {
                    instance?.apply {
                        log("AirPlay 会话已激活"); setStatus("CarPlay 会话运行中")
                        resetTouchState()
                        activeSession = session
                        showVideoMode()
                        attachSurfaceToSink("sessionActive")
                    }
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                // 首帧真正上屏：解除视频模式看门狗（半途夭折兑底的反向信号）。
                mainHandler.post { instance?.onFirstVideoFrame() }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                liveSession = null
                mainHandler.post {
                    instance?.apply {
                        log("AirPlay 会话结束"); setStatus("会话结束")
                        activeSession = null
                        resetTouchState()
                        cancelVideoWatchdog()
                        hideVideoMode()
                        // 会话结束：回到本地默认圆角（下个会话的 iPhone 遮罩会重新到达）
                        cornerOverlay.setIphoneMask(null)
                    }
                }
            }

            override fun onCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) {
                // PNG 解码绝不在 RTSP 线程做：丢后台线程，产物回 UI 线程应用。
                Thread({
                    val mask = IphoneCornerMask.decode(png, displayWidth)
                    mainHandler.post {
                        instance?.apply {
                            if (mask != null) {
                                cornerOverlay.setIphoneMask(mask)
                                log("iPhone 圆角遮罩已应用（${mask.size}px）")
                            } else {
                                log("iPhone 圆角遮罩解码失败，继续使用本地默认圆角")
                            }
                        }
                    }
                }, "corner-mask").apply { isDaemon = true }.start()
            }

            override fun onTransportError(message: String) {
                mainHandler.post { instance?.log("传输错误: $message") }
            }

            override fun onDebugLog(message: String) {
                mainHandler.post {
                    instance?.apply {
                        log(message)
                        when {
                            message.contains("iap2 identification starting") -> setStatus("无线：等待 iPhone 确认车机信息")
                            message.contains("iap2 identification accepted") -> setStatus("无线：iAP2 识别通过")
                            message.contains("iap2 mfi authentication starting") -> setStatus("无线：等待 iPhone MFi 认证")
                            message.contains("iap2 mfi rx=0xaa00") -> setStatus("无线：发送 MFi 认证证书")
                            message.contains("iap2 mfi rx=0xaa02") -> setStatus("无线：响应 MFi 认证挑战")
                            message.contains("iap2 mfi rx=0xaa05") -> setStatus("无线：MFi 认证通过")
                            message.contains("iap2 authentication accepted") -> setStatus("无线：MFi 认证通过")
                            message.contains("iap2 subscriptions sent") -> setStatus("无线：等待手机发送 Wi-Fi 参数")
                            message.contains("0x5703") -> setStatus("无线：已发送 Wi-Fi 参数，等待 CarPlay")
                            message.contains("0x4300") -> setStatus("无线：iPhone 已允许 CarPlay，正在启动")
                            message.contains("0x4301") -> setStatus("无线：已请求建立 CarPlay")
                            message.contains("0x4e0d") -> setStatus("无线：iPhone 已通知 CarPlay 可用")
                            message.contains("wireless handoff ready") -> setStatus("无线已激活")
                            message.contains("wireless bring-up failed") -> setStatus("无线建立失败，查看日志")
                        }
                    }
                }
            }
        }

        // ---------- 常量（其余） ----------

        /** 视频模式看门狗时长：会话激活后这么久仍无首帧即判定半途夭折，退回控制面板。 */
        const val VIDEO_WATCHDOG_MS = 5_000L
        const val A2DP_ROUTE_SETTLE_MS = 700L

        /**
         * AirPlay 主屏流类型：与协议 SETUP 流 type 一致（110），解码器按此索引 Surface。
         * 原验证壳误写为 0，导致解码器（type=110）永远拿不到 Surface —— 黑屏根因之一。
         */
        const val SCREEN_MAIN = 110

        /** 首次自动建热点的默认密码（WPA2 至少 8 位）。 */
        const val DEFAULT_PASSPHRASE = "jsplay123456"

        /** API 19 读不到实际信道时的预填信道（2.4G 常见值）。 */
        const val DEFAULT_CHANNEL = 6

        /** 等待热点开启的超时。 */
        const val AWAIT_AP_TIMEOUT_MILLIS = 20_000L

        /** 蓝牙运行时权限请求码。 */
        const val REQUEST_BLUETOOTH_PERMISSIONS = 7001
    }
}
