// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// On-screen controls over a running game, after Madeira's TouchGamepad,
// TouchControlPresets.swift and the controls editor: a full Xbox controller
// layout (sticks, face buttons, D-pad, shoulders), a keyboard-and-mouse layout,
// and the user's own layouts. Pad controls feed the XInput bridge; key and
// mouse controls go to Wine's keyboard and mouse.

package org.pocketarcade.madeira.session

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot
import kotlin.math.min

/** One control: a pad input ("A", "LS", "D↑"...), a key (Android keycode) or a mouse button. */
data class TouchControl(
    val kind: String,
    val name: String,
    val keycode: Int = 0,
    val label: String = name,
    /** Centre, as a fraction of the screen's width and height. */
    val nx: Float,
    val ny: Float,
    val scale: Float = 1f,
) {
    val isStick: Boolean get() = (kind == "pad" && (name == "LS" || name == "RS")) || kind == "wasd"

    fun toJson() = JSONObject().apply { put("kind", kind); put("name", name); put("keycode", keycode); put("label", label); put("nx", nx.toDouble()); put("ny", ny.toDouble()); put("scale", scale.toDouble()) }

    companion object {
        fun from(o: JSONObject) = TouchControl(o.optString("kind", "pad"), o.optString("name"), o.optInt("keycode"), o.optString("label", o.optString("name")),
            o.optDouble("nx", 0.5).toFloat(), o.optDouble("ny", 0.5).toFloat(), o.optDouble("scale", 1.0).toFloat().coerceIn(0.5f, 3f))
    }
}

data class ControlLayout(val id: String, val name: String, val controls: List<TouchControl>) {
    val builtIn: Boolean get() = id.startsWith("builtin.")
}

object ControlLayouts {
    const val XBOX = "builtin.xbox"
    const val KEYBOARD = "builtin.keyboard"
    const val BASE_DP = 64f

    /**
     * The Xbox layout for a landscape screen of `w` x `h` dp (Madeira's ControlPresetLayout.xbox):
     * triggers over bumpers in the top corners, D-pad and face diamond above the sticks,
     * View/Menu at the bottom centre, L3/R3 inside the sticks.
     */
    fun xbox(w: Float, h: Float): List<TouchControl> {
        if (w <= 0 || h <= 0) return emptyList()
        val k = (h / 400f).coerceIn(1f, 1.3f)
        val L = 16f; val R = 16f; val T = 8f; val B = 8f
        val out = ArrayList<TouchControl>()
        fun add(name: String, scale: Float, x: Float, y: Float) { out += TouchControl("pad", name, nx = x / w, ny = y / h, scale = scale) }
        fun d(s: Float) = BASE_DP * s
        val stickScale = 1.45f * k; val faceScale = 0.78f * k; val dpadScale = 0.7f * k
        val shoulderScale = 0.88f * k; val systemScale = 0.75f * k; val clickScale = 0.62f * k
        val stick = d(stickScale)
        val stickY = h - B - 12 * k - stick / 2
        val lStickX = L + 76 * k; val rStickX = w - R - 76 * k
        add("LS", stickScale, lStickX, stickY); add("RS", stickScale, rStickX, stickY)
        val stickTop = stickY - stick / 2
        val sh = d(shoulderScale); val shW = sh * 1.6f; val shH = sh * 0.74f
        val shoulderX = shW / 2 + 6 * k
        val triggerY = T + 6 * k + shH / 2
        val bumperY = triggerY + shH + 8 * k
        add("LT", shoulderScale, L + shoulderX, triggerY); add("LB", shoulderScale, L + shoulderX, bumperY)
        add("RB", shoulderScale, w - R - shoulderX, bumperY); add("RT", shoulderScale, w - R - shoulderX, triggerY)
        val rowBottom = bumperY + shH / 2
        val columnInner = shoulderX + shW / 2 + 10 * k
        val dp = d(dpadScale); val arm = dp + 6 * k
        val stacked = rowBottom + 8 * k + 2 * arm + dp <= stickTop - 8 * k
        val dpadX = if (stacked) L + 6 * k + arm + dp / 2 else L + columnInner + arm + dp / 2
        val dpadY = if (stacked) rowBottom + 8 * k + arm + dp / 2 else maxOf(stickTop - 22 * k - arm - dp / 2, T + 6 * k + arm + dp / 2)
        add("D↑", dpadScale, dpadX, dpadY - arm); add("D←", dpadScale, dpadX - arm, dpadY)
        add("D→", dpadScale, dpadX + arm, dpadY); add("D↓", dpadScale, dpadX, dpadY + arm)
        val face = d(faceScale); val o = face / 2 + 22 * k
        val cx = if (stacked) w - R - 6 * k - o - face / 2 else w - R - columnInner - o - face / 2
        val cy = if (stacked) rowBottom + 8 * k + o + face / 2 else maxOf(stickTop - 22 * k - o - face / 2, T + 6 * k + o + face / 2)
        add("Y", faceScale, cx, cy - o); add("X", faceScale, cx - o, cy); add("B", faceScale, cx + o, cy); add("A", faceScale, cx, cy + o)
        val sys = d(systemScale); val sysY = h - B - 20 * k - sys / 2
        add("View", systemScale, w / 2 - 46 * k, sysY); add("Menu", systemScale, w / 2 + 46 * k, sysY)
        val click = d(clickScale); val clickY = stickY + stick / 2 - click / 2
        add("L3", clickScale, lStickX + stick / 2 + 20 * k + click / 2, clickY)
        add("R3", clickScale, rStickX - stick / 2 - 20 * k - click / 2, clickY)
        return out
    }

    /** Keyboard and mouse: a WASD stick, common keys, and the mouse buttons. */
    fun keyboard(w: Float, h: Float): List<TouchControl> {
        if (w <= 0 || h <= 0) return emptyList()
        val out = ArrayList<TouchControl>()
        fun key(code: Int, label: String, x: Float, y: Float, s: Float = 0.8f) { out += TouchControl("key", label, code, label, x / w, y / h, s) }
        out += TouchControl("wasd", "WASD", label = "WASD", nx = 100f / w, ny = (h - 100f) / h, scale = 1.45f)
        key(KeyEvent.KEYCODE_SPACE, "Space", w - 90f, h - 70f, 1f)
        key(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift", 190f, h - 40f, 0.7f)
        key(KeyEvent.KEYCODE_E, "E", w - 170f, h - 120f)
        key(KeyEvent.KEYCODE_R, "R", w - 90f, h - 160f)
        key(KeyEvent.KEYCODE_ENTER, "Enter", w - 250f, h - 60f, 0.8f)
        key(KeyEvent.KEYCODE_ESCAPE, "Esc", 50f, 40f, 0.7f)
        key(KeyEvent.KEYCODE_TAB, "Tab", 120f, 40f, 0.7f)
        out += TouchControl("mouse", "left", label = "L", nx = (w - 60f) / w, ny = 0.4f, scale = 0.9f)
        out += TouchControl("mouse", "right", label = "R", nx = (w - 130f) / w, ny = 0.4f, scale = 0.9f)
        return out
    }

    fun builtIns(w: Float, h: Float) = listOf(ControlLayout(XBOX, "Xbox controller", xbox(w, h)), ControlLayout(KEYBOARD, "Keyboard and mouse", keyboard(w, h)))

    private fun file(context: Context) = File(context.filesDir, "control-layouts.json")

    fun user(context: Context): List<ControlLayout> = try {
        val a = JSONArray(file(context).readText())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val c = o.optJSONArray("controls") ?: JSONArray()
            ControlLayout(o.optString("id"), o.optString("name"), (0 until c.length()).mapNotNull { c.optJSONObject(it)?.let(TouchControl::from) })
        }.filter { it.id.isNotEmpty() && !it.builtIn }
    } catch (_: Exception) { emptyList() }

    fun saveUser(context: Context, layouts: List<ControlLayout>) {
        val a = JSONArray(layouts.filter { !it.builtIn }.map { l -> JSONObject().apply { put("id", l.id); put("name", l.name); put("controls", JSONArray(l.controls.map { it.toJson() })) } })
        val f = file(context); val tmp = File(f.path + ".tmp")
        tmp.writeText(a.toString()); tmp.renameTo(f)
    }

    fun all(context: Context, w: Float, h: Float) = builtIns(w, h) + user(context)

    fun nextName(existing: List<ControlLayout>): String {
        var n = 1
        while (existing.any { it.name.equals("Custom Layout $n", true) }) n++
        return "Custom Layout $n"
    }
}

/**
 * Draws the controls and turns touches on them into pad state, keys and mouse buttons.
 * Touches that start outside every control are not taken, so they reach the game as a mouse.
 */
class TouchControlsView(context: Context, private val input: WineInput) : View(context) {
    private var items: List<TouchControl> = emptyList()
    var controls: List<TouchControl>
        get() = items
        set(value) { items = value; releaseAll(); invalidate() }
    var editing = false
        set(value) { field = value; selected = -1; releaseAll(); invalidate() }
    var selected = -1
        private set
    /** Edits move controls; the overlay saves them. */
    var onEdited: ((List<TouchControl>) -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null
    /** Pad state the controls produce; read by the overlay's pad loop. */
    val pad = PadState()

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * resources.displayMetrics.density; color = Color.argb(110, 255, 255, 255) }
    private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * resources.displayMetrics.density; color = Color.rgb(90, 170, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val faceColors = mapOf("A" to Color.rgb(92, 194, 77), "B" to Color.rgb(224, 71, 61), "X" to Color.rgb(61, 135, 235), "Y" to Color.rgb(245, 194, 41))

    private class Touch(val control: Int, var x: Float, var y: Float, val startX: Float, val startY: Float)
    private val touches = HashMap<Int, Touch>()
    private val pressed = HashSet<Int>()
    private val stickOffsets = HashMap<Int, Pair<Float, Float>>()
    private var mouseButtons = 0

    private fun size(c: TouchControl): Pair<Float, Float> {
        val d = ControlLayouts.BASE_DP * c.scale * density
        return when {
            c.kind == "pad" && c.name in setOf("LB", "RB", "LT", "RT") -> d * 1.6f to d * 0.74f
            c.kind == "pad" && c.name in setOf("View", "Menu") -> d * 1.3f to d * 0.7f
            c.kind == "key" && c.label.length > 2 -> d * 1.5f to d * 0.8f
            else -> d to d
        }
    }

    private fun rect(c: TouchControl): RectF {
        val (w, h) = size(c)
        val x = c.nx * width; val y = c.ny * height
        return RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in controls.indices.reversed()) {
            val r = rect(controls[i])
            val slop = 8 * density
            if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        for ((i, c) in controls.withIndex()) {
            val r = rect(c)
            val on = i in pressed
            val paint = if (on) pressedFill else fill
            text.textSize = min(r.width(), r.height()) * (if (c.label.length > 2) 0.28f else 0.42f)
            if (c.isStick) {
                canvas.drawOval(r, fill); canvas.drawOval(r, stroke)
                val (ox, oy) = stickOffsets[i] ?: (0f to 0f)
                val knob = r.width() * 0.42f
                val kx = r.centerX() + ox * r.width() * 0.3f; val ky = r.centerY() + oy * r.height() * 0.3f
                canvas.drawCircle(kx, ky, knob / 2, if (on) pressedFill else fill)
                canvas.drawCircle(kx, ky, knob / 2, stroke)
                canvas.drawText(c.label, kx, ky + text.textSize * 0.35f, text)
            } else {
                val radius = if (r.width() > r.height() * 1.2f) r.height() * 0.3f else r.width() / 2
                faceColors[c.name]?.takeIf { c.kind == "pad" }?.let { color ->
                    val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(if (on) 200 else 110, Color.red(color), Color.green(color), Color.blue(color)) }
                    canvas.drawRoundRect(r, radius, radius, tint)
                } ?: canvas.drawRoundRect(r, radius, radius, paint)
                canvas.drawRoundRect(r, radius, radius, stroke)
                canvas.drawText(c.label, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
            }
            if (editing && i == selected) canvas.drawRoundRect(RectF(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4), 12 * density, 12 * density, selectedStroke)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(index); val y = event.getY(index)
                val c = hit(x, y)
                if (c < 0) {
                    if (editing) { selected = -1; onSelect?.invoke(-1); invalidate(); return true }
                    return touches.isNotEmpty()   // keep a gesture we own; a fresh one goes to the game
                }
                touches[id] = Touch(c, x, y, x, y)
                if (editing) { selected = c; onSelect?.invoke(c); invalidate(); return true }
                press(c, true)
                updateStick(c, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until event.pointerCount) {
                    val t = touches[event.getPointerId(p)] ?: continue
                    val x = event.getX(p); val y = event.getY(p)
                    if (editing) {
                        val c = controls[t.control]
                        val moved = controls.toMutableList()
                        moved[t.control] = c.copy(nx = (c.nx + (x - t.x) / width).coerceIn(0.02f, 0.98f), ny = (c.ny + (y - t.y) / height).coerceIn(0.02f, 0.98f))
                        t.x = x; t.y = y
                        controlsEdited(moved)
                    } else updateStick(t.control, x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (event.actionMasked == MotionEvent.ACTION_CANCEL) touches.keys.toList() else listOf(id)
                for (pid in ids) {
                    val t = touches.remove(pid) ?: continue
                    if (editing) { onEdited?.invoke(controls); continue }
                    if (touches.values.none { it.control == t.control }) {
                        press(t.control, false)
                        if (controls.getOrNull(t.control)?.isStick == true) { stickOffsets.remove(t.control); updatePadSticks() }
                    }
                }
            }
        }
        invalidate()
        return true
    }

    private fun controlsEdited(list: List<TouchControl>) {
        // Assigned without releasing the touches that are dragging.
        items = list
        invalidate()
    }

    private fun updateStick(i: Int, x: Float, y: Float) {
        val c = controls.getOrNull(i) ?: return
        if (!c.isStick) return
        val r = rect(c)
        var dx = (x - r.centerX()) / (r.width() * 0.5f); var dy = (y - r.centerY()) / (r.height() * 0.5f)
        val m = hypot(dx, dy)
        if (m > 1f) { dx /= m; dy /= m }
        stickOffsets[i] = dx to dy
        if (c.kind == "wasd") {
            val keys = HashSet<Int>()
            if (m > 0.3f) {
                if (dy < -0.38f) keys += KeyEvent.KEYCODE_W; if (dy > 0.38f) keys += KeyEvent.KEYCODE_S
                if (dx < -0.38f) keys += KeyEvent.KEYCODE_A; if (dx > 0.38f) keys += KeyEvent.KEYCODE_D
            }
            for (k in wasdHeld - keys) input.key(k, false)
            for (k in keys - wasdHeld) input.key(k, true)
            wasdHeld.clear(); wasdHeld.addAll(keys)
        } else updatePadSticks()
    }

    private val wasdHeld = HashSet<Int>()

    private fun updatePadSticks() {
        pad.lx = 0; pad.ly = 0; pad.rx = 0; pad.ry = 0
        for ((i, o) in stickOffsets) {
            val c = controls.getOrNull(i) ?: continue
            if (c.kind != "pad") continue
            val sx = (o.first * 32767).toInt(); val sy = (-o.second * 32767).toInt()
            if (c.name == "LS") { pad.lx = sx; pad.ly = sy } else { pad.rx = sx; pad.ry = sy }
        }
    }

    private fun press(i: Int, down: Boolean) {
        val c = controls.getOrNull(i) ?: return
        if (down) pressed += i else pressed -= i
        when (c.kind) {
            "pad" -> when (c.name) {
                "LT" -> pad.leftTrigger = if (down) 255 else 0
                "RT" -> pad.rightTrigger = if (down) 255 else 0
                "LS", "RS" -> {}
                else -> PadState.BUTTONS[c.name]?.let { m -> pad.buttons = if (down) pad.buttons or m else pad.buttons and m.inv() }
            }
            "key" -> input.key(c.keycode, down)
            "mouse" -> {
                val bit = if (c.name == "right") MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
                mouseButtons = if (down) mouseButtons or bit else mouseButtons and bit.inv()
                input.mouse(if (down) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE, input.desktopWidth / 2, input.desktopHeight / 2, mouseButtons, 0)
            }
            "wasd" -> if (!down) { for (k in wasdHeld) input.key(k, false); wasdHeld.clear() }
        }
    }

    fun releaseAll() {
        for (i in pressed.toList()) press(i, false)
        pressed.clear(); touches.clear(); stickOffsets.clear()
        for (k in wasdHeld) input.key(k, false)
        wasdHeld.clear()
        pad.buttons = 0; pad.leftTrigger = 0; pad.rightTrigger = 0; pad.lx = 0; pad.ly = 0; pad.rx = 0; pad.ry = 0
    }
}
