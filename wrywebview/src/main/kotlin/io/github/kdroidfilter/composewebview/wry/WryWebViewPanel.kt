package io.github.kdroidfilter.composewebview.wry

import com.sun.jna.Native
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer


class WryWebViewPanel(initialUrl: String) : JPanel() {
    private val host = SkikoInterop.createHost()
    private var webviewId: ULong? = null
    private var parentHandle: ULong = 0UL
    private var parentIsWindow: Boolean = false
    private var pendingUrl: String = initialUrl
    private var createTimer: Timer? = null
    private var gtkTimer: Timer? = null
    private var windowsTimer: Timer? = null
    private var skikoInitialized: Boolean = false
    private var lastBounds: Bounds? = null
    private var pendingBounds: Bounds? = null
    private var boundsTimer: Timer? = null

    init {
        layout = BorderLayout()
        add(host, BorderLayout.CENTER)
        log("init url=$initialUrl")
    }

    override fun addNotify() {
        super.addNotify()
        log("addNotify displayable=${host.isDisplayable} showing=${host.isShowing} size=${host.width}x${host.height}")
        SwingUtilities.invokeLater { scheduleCreateIfNeeded() }
    }

    override fun removeNotify() {
        log("removeNotify")
        stopCreateTimer()
        destroyIfNeeded()
        super.removeNotify()
    }

    override fun doLayout() {
        super.doLayout()
        log("doLayout size=${host.width}x${host.height} displayable=${host.isDisplayable} showing=${host.isShowing}")
        updateBounds()
        scheduleCreateIfNeeded()
    }

    fun loadUrl(url: String) {
        pendingUrl = url
        if (SwingUtilities.isEventDispatchThread()) {
            webviewId?.let { NativeBindings.loadUrl(it, url) }
                ?: scheduleCreateIfNeeded()
        } else {
            SwingUtilities.invokeLater {
                webviewId?.let { NativeBindings.loadUrl(it, url) } ?: scheduleCreateIfNeeded()
            }
        }
        log("loadUrl url=$url webviewId=$webviewId")
    }

    private fun createIfNeeded(): Boolean {
        if (webviewId != null) return true
        if (!host.isDisplayable || !host.isShowing) return false
        if (host.width <= 0 || host.height <= 0) return false
        // On Windows, wait for the window to be fully visible
        if (IS_WINDOWS) {
            val window = SwingUtilities.getWindowAncestor(host)
            if (window == null || !window.isShowing) return false
        }
        if (!skikoInitialized) {
            skikoInitialized = try {
                val initResult = SkikoInterop.init(host)
                log("skiko init result=$initResult")
                initResult
            } catch (e: RuntimeException) {
                log("skiko init failed: ${e.message}")
                false
            }
        }
        val resolved = resolveParentHandle() ?: run {
            log("createIfNeeded no parent handle; host displayable=${host.isDisplayable} showing=${host.isShowing} size=${host.width}x${host.height}")
            return false
        }
        parentHandle = resolved.handle
        parentIsWindow = resolved.isWindow
        log("createIfNeeded handle=$parentHandle parentIsWindow=$parentIsWindow size=${host.width}x${host.height}")
        return try {
            webviewId = NativeBindings.createWebview(
                parentHandle,
                host.width.coerceAtLeast(1),
                host.height.coerceAtLeast(1),
                pendingUrl,
            )
            updateBounds()
            startGtkPumpIfNeeded()
            startWindowsPumpIfNeeded()
            log("createIfNeeded success id=$webviewId")
            true
        } catch (e: RuntimeException) {
            System.err.println("Failed to create Wry webview: ${e.message}")
            e.printStackTrace()
            true
        }
    }

    private fun destroyIfNeeded() {
        stopGtkPump()
        stopWindowsPump()
        stopBoundsTimer()
        webviewId?.let {
            log("destroy id=$it")
            NativeBindings.destroyWebview(it)
        }
        webviewId = null
        parentHandle = 0UL
        parentIsWindow = false
        lastBounds = null
    }

    private fun updateBounds() {
        val id = webviewId ?: return
        val bounds = boundsInParent()
        if (IS_LINUX) {
            pendingBounds = bounds
            if (boundsTimer == null) {
                boundsTimer = Timer(16) {
                    val currentId = webviewId ?: return@Timer
                    val toSend = pendingBounds ?: return@Timer
                    pendingBounds = null
                    if (toSend != lastBounds) {
                        lastBounds = toSend
                        log("setBounds id=$currentId pos=(${toSend.x}, ${toSend.y}) size=${toSend.width}x${toSend.height}")
                        NativeBindings.setBounds(currentId, toSend.x, toSend.y, toSend.width, toSend.height)
                    }
                    if (pendingBounds == null) {
                        stopBoundsTimer()
                    }
                }.apply { start() }
            }
            return
        }
        if (bounds == lastBounds) return
        lastBounds = bounds
        log("setBounds id=$id pos=(${bounds.x}, ${bounds.y}) size=${bounds.width}x${bounds.height}")
        NativeBindings.setBounds(id, bounds.x, bounds.y, bounds.width, bounds.height)
    }

    private fun startGtkPumpIfNeeded() {
        if (!IS_LINUX || gtkTimer != null) return
        log("startGtkPump (noop, handled in native GTK thread)")
    }

    private fun stopGtkPump() {
        gtkTimer?.stop()
        gtkTimer = null
    }

    private fun startWindowsPumpIfNeeded() {
        if (!IS_WINDOWS || windowsTimer != null) return
        log("startWindowsPump")
        windowsTimer = Timer(16) { NativeBindings.pumpWindowsEvents() }.apply { start() }
    }

    private fun stopWindowsPump() {
        windowsTimer?.stop()
        windowsTimer = null
    }

    private fun scheduleCreateIfNeeded() {
        if (webviewId != null || createTimer != null) return
        log("scheduleCreateIfNeeded")
        val delay = if (IS_WINDOWS) 100 else 16
        createTimer = Timer(delay) {
            if (createIfNeeded()) {
                stopCreateTimer()
            }
        }.apply { start() }
    }

    private fun stopCreateTimer() {
        createTimer?.stop()
        createTimer = null
    }

    private fun stopBoundsTimer() {
        boundsTimer?.stop()
        boundsTimer = null
        pendingBounds = null
    }

    private fun componentHandle(component: Component): ULong {
        return try {
            Native.getComponentID(component).toULong()
        } catch (e: RuntimeException) {
            log("componentHandle failed for ${component.javaClass.name}: ${e.message}")
            0UL
        }
    }

    private fun log(message: String) {
        System.err.println("[WryWebViewPanel] $message")
    }

    private fun resolveParentHandle(): ParentHandle? {
        val contentHandle = safeSkikoHandle("content") { SkikoInterop.getContentHandle(host) }
        val windowHandle = safeSkikoHandle("window") { SkikoInterop.getWindowHandle(host) }
        if (IS_WINDOWS) {
            // On Windows, use the window handle and position webview manually
            // Canvas HWND doesn't work well as WebView2 parent
            val window = SwingUtilities.getWindowAncestor(host)
            if (window != null && window.isDisplayable && window.isShowing) {
                val windowHandleJna = componentHandle(window)
                if (windowHandleJna != 0UL) {
                    log("resolveParentHandle jna window=0x${windowHandleJna.toString(16)} (windows)")
                    return ParentHandle(windowHandleJna, true)
                }
            }
        } else if (IS_MAC) {
            if (contentHandle != 0L && contentHandle != windowHandle) {
                log("resolveParentHandle skiko content=0x${contentHandle.toString(16)} window=0x${windowHandle.toString(16)} (macOS content)")
                return ParentHandle(contentHandle.toULong(), false)
            }
            if (windowHandle != 0L) {
                log("resolveParentHandle skiko window=0x${windowHandle.toString(16)} (macOS)")
                return ParentHandle(windowHandle.toULong(), true)
            }
            if (contentHandle != 0L) {
                log("resolveParentHandle skiko content=0x${contentHandle.toString(16)} (macOS fallback)")
                return ParentHandle(contentHandle.toULong(), true)
            }
        } else {
            if (contentHandle != 0L) {
                log("resolveParentHandle skiko content=0x${contentHandle.toString(16)} window=0x${windowHandle.toString(16)}")
                return ParentHandle(contentHandle.toULong(), false)
            }
            if (windowHandle != 0L) {
                log("resolveParentHandle skiko content=0 window=0x${windowHandle.toString(16)} (using window)")
                return ParentHandle(windowHandle.toULong(), true)
            }
        }

        val hostHandle = componentHandle(host)
        if (hostHandle != 0UL) {
            log("resolveParentHandle jna host=0x${hostHandle.toString(16)}")
            return ParentHandle(hostHandle, false)
        }
        val window = SwingUtilities.getWindowAncestor(host) ?: return null
        if (!window.isDisplayable || !window.isShowing) return null
        val windowHandleFallback = componentHandle(window)
        if (windowHandleFallback != 0UL) {
            log("resolveParentHandle jna window=0x${windowHandleFallback.toString(16)}")
            return ParentHandle(windowHandleFallback, true)
        }
        log("resolveParentHandle no handles (content=0 window=0)")
        return null
    }

    private fun safeSkikoHandle(name: String, getter: () -> Long): Long {
        return try {
            getter()
        } catch (e: RuntimeException) {
            log("skiko $name handle failed: ${e.message}")
            0L
        }
    }

    private fun boundsInParent(): Bounds {
        val width = host.width.coerceAtLeast(1)
        val height = host.height.coerceAtLeast(1)
        if (!parentIsWindow) {
            return Bounds(0, 0, width, height)
        }
        val window = SwingUtilities.getWindowAncestor(host) ?: return Bounds(0, 0, width, height)
        val point = SwingUtilities.convertPoint(host, 0, 0, window)
        val insets = window.insets
        val x = point.x - insets.left
        val y = point.y - insets.top
        log("boundsInParent windowOffset=(${x}, ${y}) insets=${insets}")
        return Bounds(x, y, width, height)
    }

    private data class ParentHandle(val handle: ULong, val isWindow: Boolean)
    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    private companion object {
        private val OS_NAME = System.getProperty("os.name")?.lowercase().orEmpty()
        private val IS_LINUX = OS_NAME.contains("linux")
        private val IS_MAC = OS_NAME.contains("mac")
        private val IS_WINDOWS = OS_NAME.contains("windows")
    }
}

private object NativeBindings {
    fun createWebview(parentHandle: ULong, width: Int, height: Int, url: String): ULong {
        return io.github.kdroidfilter.composewebview.wry.createWebview(parentHandle, width, height, url)
    }

    fun setBounds(id: ULong, x: Int, y: Int, width: Int, height: Int) {
        io.github.kdroidfilter.composewebview.wry.setBounds(id, x, y, width, height)
    }

    fun loadUrl(id: ULong, url: String) {
        io.github.kdroidfilter.composewebview.wry.loadUrl(id, url)
    }

    fun destroyWebview(id: ULong) {
        io.github.kdroidfilter.composewebview.wry.destroyWebview(id)
    }

    fun pumpGtkEvents() {
        io.github.kdroidfilter.composewebview.wry.pumpGtkEvents()
    }

    fun pumpWindowsEvents() {
        io.github.kdroidfilter.composewebview.wry.pumpWindowsEvents()
    }
}
