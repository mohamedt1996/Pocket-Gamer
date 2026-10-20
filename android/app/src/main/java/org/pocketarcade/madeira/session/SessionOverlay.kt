// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// What draws over a running game in WineActivity, after Madeira's LibraryHUD,
// DockStartScreen and FPSOverlay: the menu button, the session menu (touch
// controls, layouts and the editor, controller mode, keyboard, performance,
// stop), the touch controls, controllers through the XInput bridge, a frame
// counter, and Madeira Dock's starting screen. Plain Android views: WineActivity
// is a plain Activity.

package org.pocketarcade.madeira.session

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.pocketarcade.madeira.core.DockHandoff
import org.pocketarcade.madeira.core.InstallScripts
import org.pocketarcade.madeira.PortRuntime
import java.io.File
import java.util.concurrent.TimeUnit

class SessionOverlay private constructor(private val activity: Activity, private val input: WineInput, private val spec: LaunchSpec) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val root = FrameLayout(activity)
    private val controlsView = TouchControlsView(activity, input)
    private val menuButton = TextView(activity)
    private val fpsLabel = TextView(activity)
    private val status = LinearLayout(activity)
    private val statusTitle = TextView(activity)
    private val statusDetail = TextView(activity)
    private val statusProgress = ProgressBar(activity)
    private var menu: View? = null
    private var editorBar: View? = null
    private val keyboardField = EditText(activity)

    private val gamepads = GamepadInput()
    private val bridge: PadBridge? = PadBridge.open(File(spec.home, "prefix"))
    private var kbm: PadKeyboardMouse? = null
    private var controllerMode = prefs.getString("controllerMode.${spec.title}", "xinput") ?: "xinput"
    private var touchVisible = spec.touchControls && android.view.InputDevice.getDeviceIds().none { id ->
        val s = android.view.InputDevice.getDevice(id)?.sources ?: 0
        (s and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
    }
    private var layoutID = spec.controlLayout ?: prefs.getString("layout.${spec.title}", ControlLayouts.XBOX) ?: ControlLayouts.XBOX
    private var frames = 0
    private var frameWindowStart = System.nanoTime()
    private var firstFrameAt = 0L
    private var showFps = spec.performance
    private var dockDone = spec.dockReport == null
    private var lastPadTick = System.nanoTime()
    private var lastRumble = 0 to 0
    private var noReadsWarned = false
    private var padInputSeenAt = 0L

    private fun glass(radius: Float, alpha: Int = 150): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius * density
        setColor(Color.argb(alpha, 28, 30, 38))
        setStroke(dp(1f), Color.argb(70, 255, 255, 255))
    }

    private fun build() {
        root.layoutParams = FrameLayout.LayoutParams(-1, -1)
        root.addView(controlsView, FrameLayout.LayoutParams(-1, -1))
        controlsView.visibility = if (touchVisible) View.VISIBLE else View.GONE

        menuButton.text = "☰"
        menuButton.setTextColor(Color.WHITE)
        menuButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        menuButton.gravity = Gravity.CENTER
        menuButton.background = glass(18f)
        menuButton.setOnClickListener { toggleMenu() }
        root.addView(menuButton, FrameLayout.LayoutParams(dp(56f), dp(36f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8f) })

        fpsLabel.setTextColor(Color.WHITE)
        fpsLabel.typeface = Typeface.MONOSPACE
        fpsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        fpsLabel.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        fpsLabel.background = glass(8f, 120)
        fpsLabel.visibility = if (showFps) View.VISIBLE else View.GONE
        root.addView(fpsLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(10f); leftMargin = dp(10f) })

        // Madeira Dock's starting screen: Valve's client signs in and starts the game.
        status.orientation = LinearLayout.VERTICAL
        status.gravity = Gravity.CENTER_HORIZONTAL
        status.setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
        status.background = glass(22f, 210)
        statusTitle.setTextColor(Color.WHITE); statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); statusTitle.typeface = Typeface.DEFAULT_BOLD
        statusTitle.gravity = Gravity.CENTER
        statusDetail.setTextColor(Color.argb(200, 255, 255, 255)); statusDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); statusDetail.gravity = Gravity.CENTER
        status.addView(statusTitle); status.addView(statusProgress, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { topMargin = dp(12f); bottomMargin = dp(8f) })
        status.addView(statusDetail)
        status.visibility = if (spec.dockReport != null) View.VISIBLE else View.GONE
        statusTitle.text = spec.title
        statusDetail.text = "Starting Steam…"
        root.addView(status, FrameLayout.LayoutParams(dp(320f), -2, Gravity.CENTER))

        keyboardField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        keyboardField.alpha = 0f
        keyboardField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            private var clearing = false
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s == null || clearing) return
                repeat(before) { tap(KeyEvent.KEYCODE_DEL) }
                if (count > 0) typeText(s.subSequence(start, start + count).toString())
            }
            // Trimming the hidden field must not send its length in backspaces to the game.
            override fun afterTextChanged(s: Editable?) { if ((s?.length ?: 0) > 64) { clearing = true; s?.clear(); clearing = false } }
        })
        keyboardField.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_ENTER)) { tap(code); true } else false
        }
        root.addView(keyboardField, FrameLayout.LayoutParams(dp(1f), dp(1f)))

        controlsView.onEdited = { saveEdited(it) }
        applyLayout()
        activity.addContentView(root, FrameLayout.LayoutParams(-1, -1))
        main.post(padLoop)
        if (spec.dockReport != null) main.postDelayed(dockLoop, 500)
        main.postDelayed(fpsLoop, 1000)
    }

    private fun tap(code: Int) { input.key(code, true); input.key(code, false) }

    private fun typeText(text: String) {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) ?: return
        for (e in events) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> input.key(e.keyCode, e.action == KeyEvent.ACTION_DOWN)
                else -> input.key(e.keyCode, e.action == KeyEvent.ACTION_DOWN)
            }
        }
    }

    // MARK: Layouts

    private fun screenDp(): Pair<Float, Float> {
        val m = activity.resources.displayMetrics
        val w = maxOf(m.widthPixels, m.heightPixels) / density; val h = minOf(m.widthPixels, m.heightPixels) / density
        return w to h
    }

    private fun layouts(): List<ControlLayout> { val (w, h) = screenDp(); return ControlLayouts.all(activity, w, h) }

    private fun applyLayout() {
        val layout = layouts().firstOrNull { it.id == layoutID } ?: layouts().first()
        layoutID = layout.id
        controlsView.controls = layout.controls
    }

    private fun saveEdited(controls: List<TouchControl>) {
        val current = layouts().firstOrNull { it.id == layoutID } ?: return
        if (current.builtIn) return   // saved as a new layout from the editor's Save button
        val users = ControlLayouts.user(activity).map { if (it.id == layoutID) it.copy(controls = controls) else it }
        ControlLayouts.saveUser(activity, users)
    }

    private fun chooseLayout(id: String) {
        layoutID = id
        prefs.edit().putString("layout.${spec.title}", id).apply()
        applyLayout()
    }

    // MARK: Pads

    private val padLoop = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = ((now - lastPadTick) / 1e9f).coerceIn(0f, 0.1f)
            lastPadTick = now
            val touch = if (touchVisible && !controlsView.editing) controlsView.pad.copy() else PadState()
            val physical0 = gamepads.state(0) ?: PadState()
            val first = physical0.merge(touch)
            if (!first.isIdle()) padInputSeenAt = now
            if (controllerMode == "kbm") {
                val k = kbm ?: PadKeyboardMouse(input, spec.binds).also { kbm = it }
                k.feed(first, dt)
                for (slot in 0 until 4) bridge?.write(slot, null)
            } else {
                kbm?.releaseAll(); kbm = null
                val anyTouch = touchVisible && controlsView.controls.any { it.kind == "pad" }
                bridge?.write(0, if (gamepads.connected > 0 || anyTouch) first else null)
                for (slot in 1 until 4) bridge?.write(slot, gamepads.state(slot))
                rumble()
                // A game that never asks XInput for a pad after the player used one: suggest keyboard and mouse.
                if (!noReadsWarned && padInputSeenAt != 0L && now - padInputSeenAt < 1_000_000_000L && bridge != null &&
                    bridge.reads == 0 && firstFrameAt != 0L && now - firstFrameAt > 20_000_000_000L) {
                    noReadsWarned = true
                    toast("This game does not read controllers. Choose Keyboard & mouse in the menu (☰).")
                }
            }
            main.postDelayed(this, 8)
        }
    }

    private fun rumble() {
        val b = bridge ?: return
        val (l, r) = b.rumble(0)
        if (l to r == lastRumble) return
        lastRumble = l to r
        val strength = maxOf(l, r)
        val vibrator = (android.view.InputDevice.getDeviceIds().asSequence().mapNotNull { android.view.InputDevice.getDevice(it) }
            .firstOrNull { (it.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD }?.vibrator)
            ?: if (touchVisible) activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator else null
        vibrator ?: return
        if (strength == 0) { vibrator.cancel(); return }
        vibrator.vibrate(VibrationEffect.createOneShot(5_000, maxOf(1, minOf(255, strength / 257))))
    }

    /** A controller key; true when taken. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && event.action == KeyEvent.ACTION_DOWN) { main.post { toggleMenu() }; return true }
        if (menu != null && event.keyCode == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_DOWN) { main.post { closeMenu() }; return true }
        if (menu != null) return false
        val taken = gamepads.onKey(event)
        if (taken && touchVisible && spec.touchControls) { touchVisible = false; main.post { controlsView.visibility = View.GONE; controlsView.releaseAll() } }
        return taken
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (menu != null) return false
        val taken = gamepads.onMotion(event)
        if (taken && touchVisible && spec.touchControls) { touchVisible = false; main.post { controlsView.visibility = View.GONE; controlsView.releaseAll() } }
        return taken
    }

    // MARK: Frames

    /** Called for every frame the game presents (TextureView.onSurfaceTextureUpdated). */
    fun frame() {
        frames++
        if (firstFrameAt == 0L) firstFrameAt = System.nanoTime()
    }

    private val fpsLoop = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val fps = frames * 1e9 / (now - frameWindowStart)
            frames = 0; frameWindowStart = now
            if (showFps) fpsLabel.text = String.format("%.0f FPS", fps)
            main.postDelayed(this, 1000)
        }
    }

    // MARK: Madeira Dock

    private val dockLoop = object : Runnable {
        override fun run() {
            if (dockDone) return
            val report = spec.dockReport?.let { File(it) }?.takeIf { it.isFile }?.let { f ->
                try { DockHandoff.parseReport(f.readBytes().let { if (it.size > 32768) it.copyOf(32768) else it }) } catch (_: Exception) { null }
            }
            val installs = spec.dockInstallerResults?.let { File(it) }?.takeIf { it.isFile }?.let { InstallScripts.results(it.readText()) }
            val fields = report?.fields.orEmpty()
            val failure = report?.failure
            when {
                failure != null -> {
                    dockDone = true
                    statusProgress.visibility = View.GONE
                    statusTitle.text = "Steam could not start the game"
                    statusDetail.text = failure
                    addStatusButton("Close") { stopGame() }
                    return
                }
                fields["launch-keep-alive"] != null || (firstFrameAt != 0L && System.nanoTime() - firstFrameAt > 3_000_000_000L && fields["probe-result"] == null && fields["session-requested-app-listed"] == "1") -> {
                    dockDone = true
                    status.animate().alpha(0f).setDuration(400).withEndAction { status.visibility = View.GONE }.start()
                    return
                }
                installs != null && !installs.ended -> {
                    val running = installs.started.keys.filter { installs.exits[it] == null }.maxOrNull()
                    statusDetail.text = if (running != null) "Installing ${installs.started[running]} (${running} of ${installs.total})…" else "Preparing one-time installs…"
                }
                fields["session-requested-app-listed"] == "1" -> statusDetail.text = "Steam confirmed the game. Starting…"
                fields["session-authenticated-online"] == "1" -> statusDetail.text = "Signed in. Checking the game's license…"
                fields["session-native-token-submitted"] != null -> statusDetail.text = "Signing in to Steam…"
                fields["load-client-begin"] != null -> statusDetail.text = "Loading Steam…"
            }
            main.postDelayed(this, 500)
        }
    }

    private fun addStatusButton(label: String, action: () -> Unit) {
        val b = Button(activity)
        b.text = label
        b.setOnClickListener { action() }
        status.addView(b, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12f) })
    }

    // MARK: Menu

    fun onBack() = main.post { if (menu != null) closeMenu() else toggleMenu() }

    private fun toggleMenu() { if (menu != null) closeMenu() else openMenu() }

    private fun closeMenu() {
        menu?.let { root.removeView(it) }
        menu = null
    }

    private fun row(parent: LinearLayout, label: String, detail: String? = null, action: () -> Unit) {
        val t = TextView(activity)
        t.text = if (detail != null) "$label\n$detail" else label
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        t.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        t.background = GradientDrawable().apply { cornerRadius = 12 * density; setColor(Color.argb(40, 255, 255, 255)) }
        t.setOnClickListener { action() }
        parent.addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
    }

    private fun openMenu() {
        closeMenu()
        val panel = LinearLayout(activity)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        panel.background = glass(24f, 225)
        val title = TextView(activity)
        title.text = spec.title.ifEmpty { "Session" }
        title.setTextColor(Color.WHITE); title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); title.typeface = Typeface.DEFAULT_BOLD
        panel.addView(title)
        row(panel, "Resume") { closeMenu() }
        row(panel, "Touch controls", if (touchVisible) "On" else "Off") {
            touchVisible = !touchVisible
            controlsView.visibility = if (touchVisible) View.VISIBLE else View.GONE
            if (!touchVisible) controlsView.releaseAll()
            openMenu()
        }
        val layouts = layouts()
        row(panel, "Controller layout", layouts.firstOrNull { it.id == layoutID }?.name) {
            AlertDialog.Builder(activity).setTitle("Controller layout")
                .setItems(layouts.map { it.name }.toTypedArray()) { _, i -> chooseLayout(layouts[i].id); openMenu() }.show()
        }
        row(panel, "Edit controls") { closeMenu(); startEditor() }
        row(panel, "Controller", if (controllerMode == "kbm") "Keyboard & mouse" else "XInput controller") {
            controllerMode = if (controllerMode == "kbm") "xinput" else "kbm"
            prefs.edit().putString("controllerMode.${spec.title}", controllerMode).apply()
            openMenu()
        }
        row(panel, "Keyboard") { closeMenu(); showKeyboard() }
        row(panel, "Performance overlay", if (showFps) "On" else "Off") {
            showFps = !showFps
            fpsLabel.visibility = if (showFps) View.VISIBLE else View.GONE
            openMenu()
        }
        row(panel, "Stop game") {
            AlertDialog.Builder(activity).setTitle("Stop ${spec.title.ifEmpty { "this game" }}?")
                .setMessage("Unsaved progress is lost.")
                .setPositiveButton("Stop") { _, _ -> stopGame() }.setNegativeButton("Cancel", null).show()
        }
        val scroll = ScrollView(activity)
        scroll.addView(panel)
        menu = scroll
        root.addView(scroll, FrameLayout.LayoutParams(dp(340f), -2, Gravity.CENTER))
    }

    private fun showKeyboard() {
        keyboardField.requestFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(keyboardField, InputMethodManager.SHOW_FORCED)
    }

    private fun toast(text: String) = main.post { android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_LONG).show() }

    // MARK: Editor

    private fun startEditor() {
        touchVisible = true
        controlsView.visibility = View.VISIBLE
        controlsView.editing = true
        val bar = LinearLayout(activity)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        bar.background = glass(18f, 220)
        fun button(label: String, action: () -> Unit) {
            val t = TextView(activity)
            t.text = label; t.setTextColor(Color.WHITE); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            t.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            t.setOnClickListener { action() }
            bar.addView(t)
        }
        fun change(f: (TouchControl) -> TouchControl?) {
            val i = controlsView.selected
            if (i < 0) { toast("Tap a control first."); return }
            val list = controlsView.controls.toMutableList()
            val c = f(list[i])
            if (c == null) list.removeAt(i) else list[i] = c
            ensureUserLayout(list)
        }
        button("Smaller") { change { it.copy(scale = (it.scale - 0.1f).coerceAtLeast(0.5f)) } }
        button("Larger") { change { it.copy(scale = (it.scale + 0.1f).coerceAtMost(3f)) } }
        button("Remove") { change { null } }
        button("Add key") { addKeyControl() }
        button("Add pad button") { addPadControl() }
        button("Reset") {
            val builtIn = layouts().first { it.builtIn && (it.id == layoutID || layoutID.startsWith("user.")) }
            ensureUserLayout(builtIn.controls)
        }
        button("Done") {
            controlsView.editing = false
            editorBar?.let { root.removeView(it) }; editorBar = null
            saveEdited(controlsView.controls)
        }
        val scroll = HorizontalScrollView(activity)
        scroll.addView(bar)
        editorBar = scroll
        root.addView(scroll, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(52f) })
        toast("Drag controls to move them. Tap one to resize or remove it.")
    }

    /** Edits always go to a layout of the user's: editing a built-in copies it first. */
    private fun ensureUserLayout(controls: List<TouchControl>) {
        val users = ControlLayouts.user(activity).toMutableList()
        val current = users.firstOrNull { it.id == layoutID }
        if (current == null) {
            val id = "user." + System.currentTimeMillis()
            users += ControlLayout(id, ControlLayouts.nextName(layouts()), controls)
            ControlLayouts.saveUser(activity, users)
            layoutID = id
            prefs.edit().putString("layout.${spec.title}", id).apply()
        } else {
            ControlLayouts.saveUser(activity, users.map { if (it.id == layoutID) it.copy(controls = controls) else it })
        }
        val selected = controlsView.selected
        controlsView.controls = controls
        controlsView.editing = true
        if (selected >= 0) controlsView.invalidate()
    }

    private fun addKeyControl() {
        val keys = listOf("Space" to KeyEvent.KEYCODE_SPACE, "Enter" to KeyEvent.KEYCODE_ENTER, "Esc" to KeyEvent.KEYCODE_ESCAPE,
            "Tab" to KeyEvent.KEYCODE_TAB, "Shift" to KeyEvent.KEYCODE_SHIFT_LEFT, "Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT, "Alt" to KeyEvent.KEYCODE_ALT_LEFT,
            "↑" to KeyEvent.KEYCODE_DPAD_UP, "↓" to KeyEvent.KEYCODE_DPAD_DOWN, "←" to KeyEvent.KEYCODE_DPAD_LEFT, "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "F1" to KeyEvent.KEYCODE_F1, "F5" to KeyEvent.KEYCODE_F5, "F9" to KeyEvent.KEYCODE_F9) +
            ('A'..'Z').map { it.toString() to (KeyEvent.KEYCODE_A + (it - 'A')) } + ('0'..'9').map { it.toString() to (KeyEvent.KEYCODE_0 + (it - '0')) } +
            listOf("Left click" to -1, "Right click" to -2, "WASD stick" to -3)
        AlertDialog.Builder(activity).setTitle("Add a control").setItems(keys.map { it.first }.toTypedArray()) { _, i ->
            val (label, code) = keys[i]
            val c = when (code) {
                -1 -> TouchControl("mouse", "left", label = "L", nx = 0.5f, ny = 0.5f)
                -2 -> TouchControl("mouse", "right", label = "R", nx = 0.5f, ny = 0.5f)
                -3 -> TouchControl("wasd", "WASD", label = "WASD", nx = 0.5f, ny = 0.5f, scale = 1.4f)
                else -> TouchControl("key", label, code, label, 0.5f, 0.5f, 0.8f)
            }
            ensureUserLayout(controlsView.controls + c)
        }.show()
    }

    private fun addPadControl() {
        val names = PadState.BUTTONS.keys.filter { it != "Guide" } + listOf("LT", "RT", "LS", "RS")
        AlertDialog.Builder(activity).setTitle("Add a pad control").setItems(names.toTypedArray()) { _, i ->
            val n = names[i]
            ensureUserLayout(controlsView.controls + TouchControl("pad", n, nx = 0.5f, ny = 0.5f, scale = if (n == "LS" || n == "RS") 1.4f else 0.8f))
        }.show()
    }

    // MARK: Stop

    fun stopGame() {
        Thread {
            try {
                val runtime = PortRuntime(activity)
                val env = WineLaunch.environment(activity, runtime.root(), spec)
                val pb = ProcessBuilder(File(runtime.root(), "bin/wineserver").absolutePath, "-k")
                pb.environment().putAll(env)
                pb.redirectErrorStream(true)
                val p = pb.start()
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

    fun onPause() {
        controlsView.releaseAll()
        kbm?.releaseAll()
        for (slot in 0 until 4) bridge?.write(slot, null)
    }

    companion object {
        @JvmStatic
        fun attach(activity: Activity, input: WineInput): SessionOverlay {
            val spec = activity.intent.getStringExtra(LaunchSpec.EXTRA)?.let { LaunchSpec.fromJson(it) }
                ?: LaunchSpec(activity.filesDir.absolutePath, "", "", activity.intent.getStringExtra("gameId") ?: "", touchControls = true)
            return SessionOverlay(activity, input, spec).also { it.build() }
        }
    }
}
