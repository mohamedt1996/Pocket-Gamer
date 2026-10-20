// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// Controllers for Wine: the shared file the XInput bridge DLL reads
// (runtime/android/addons/xinput), Android game controllers read into XInput
// state, and the keyboard-and-mouse translation for games without controller
// support (after Madeira's PadKeyboardMouse.swift).

package org.pocketarcade.madeira.session

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One XInput pad's state. Buttons use XInput's masks. */
data class PadState(
    var buttons: Int = 0,
    var leftTrigger: Int = 0,
    var rightTrigger: Int = 0,
    var lx: Int = 0, var ly: Int = 0, var rx: Int = 0, var ry: Int = 0,
) {
    fun isIdle() = buttons == 0 && leftTrigger < 16 && rightTrigger < 16 && abs(lx) < 4000 && abs(ly) < 4000 && abs(rx) < 4000 && abs(ry) < 4000

    fun merge(o: PadState) = PadState(buttons or o.buttons, maxOf(leftTrigger, o.leftTrigger), maxOf(rightTrigger, o.rightTrigger),
        pick(lx, o.lx), pick(ly, o.ly), pick(rx, o.rx), pick(ry, o.ry))

    private fun pick(a: Int, b: Int) = if (abs(b) > abs(a)) b else a

    companion object {
        const val DPAD_UP = 0x0001; const val DPAD_DOWN = 0x0002; const val DPAD_LEFT = 0x0004; const val DPAD_RIGHT = 0x0008
        const val START = 0x0010; const val BACK = 0x0020; const val LEFT_THUMB = 0x0040; const val RIGHT_THUMB = 0x0080
        const val LEFT_SHOULDER = 0x0100; const val RIGHT_SHOULDER = 0x0200; const val GUIDE = 0x0400
        const val A = 0x1000; const val B = 0x2000; const val X = 0x4000; const val Y = 0x8000

        /** The pad input names the touch controls and binds use. */
        val BUTTONS = linkedMapOf(
            "A" to A, "B" to B, "X" to X, "Y" to Y, "LB" to LEFT_SHOULDER, "RB" to RIGHT_SHOULDER,
            "View" to BACK, "Menu" to START, "L3" to LEFT_THUMB, "R3" to RIGHT_THUMB,
            "D↑" to DPAD_UP, "D↓" to DPAD_DOWN, "D←" to DPAD_LEFT, "D→" to DPAD_RIGHT, "Guide" to GUIDE,
        )
    }
}

/** The file the XInput bridge maps: "PAPAD001", a read counter, then four 64-byte pad slots. */
class PadBridge private constructor(private val buffer: MappedByteBuffer) {
    private val packets = IntArray(4)
    private val last = arrayOfNulls<PadState>(4)

    /** How often the game has asked XInput for a pad: 0 means it does not read controllers. */
    val reads: Int get() = buffer.getInt(16)

    @Synchronized fun write(slot: Int, state: PadState?) {
        if (slot !in 0..3) return
        val base = 64 + 64 * slot
        if (state == null) { buffer.putInt(base, 0); last[slot] = null; return }
        if (state == last[slot] && buffer.getInt(base) == 1) return
        last[slot] = state.copy()
        buffer.putShort(base + 8, state.buttons.toShort())
        buffer.put(base + 10, state.leftTrigger.coerceIn(0, 255).toByte())
        buffer.put(base + 11, state.rightTrigger.coerceIn(0, 255).toByte())
        buffer.putShort(base + 12, state.lx.coerceIn(-32768, 32767).toShort())
        buffer.putShort(base + 14, state.ly.coerceIn(-32768, 32767).toShort())
        buffer.putShort(base + 16, state.rx.coerceIn(-32768, 32767).toShort())
        buffer.putShort(base + 18, state.ry.coerceIn(-32768, 32767).toShort())
        buffer.putInt(base + 4, ++packets[slot])
        buffer.putInt(base, 1)
    }

    /** The rumble the game asked for (left, right motor 0-65535). */
    fun rumble(slot: Int): Pair<Int, Int> {
        val base = 64 + 64 * slot
        return (buffer.getShort(base + 20).toInt() and 0xFFFF) to (buffer.getShort(base + 22).toInt() and 0xFFFF)
    }

    companion object {
        fun file(prefix: File) = File(prefix, "drive_c/windows/pocketarcade-pad.bin")

        /** Creates the file with its header and no pads connected. */
        fun ensureFile(prefix: File) {
            val f = file(prefix)
            f.parentFile?.mkdirs()
            RandomAccessFile(f, "rw").use { r ->
                if (r.length() != 4096L) r.setLength(4096)
                r.seek(0); r.write("PAPAD001".toByteArray())
                r.seek(16); r.writeInt(0)
                for (i in 0 until 4) { r.seek(64L + 64 * i); r.writeInt(0) }
            }
        }

        fun open(prefix: File): PadBridge? = try {
            ensureFile(prefix)
            RandomAccessFile(file(prefix), "rw").use { r ->
                val b = r.channel.map(FileChannel.MapMode.READ_WRITE, 0, 4096)
                b.order(ByteOrder.LITTLE_ENDIAN)
                PadBridge(b)
            }
        } catch (_: Exception) { null }
    }
}

/** Android game controllers as XInput pads, one slot per device in order of first input. */
class GamepadInput {
    private val slots = LinkedHashMap<Int, PadState>()

    val connected: Int get() = slots.size

    fun state(slot: Int): PadState? = slots.values.elementAtOrNull(slot)
    fun deviceSlot(deviceId: Int): Int = slots.keys.indexOf(deviceId)

    fun removeMissing() {
        val present = InputDevice.getDeviceIds().toSet()
        slots.keys.retainAll(present)
    }

    private fun isPad(device: InputDevice?): Boolean {
        val s = device?.sources ?: return false
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun pad(id: Int): PadState = slots.getOrPut(id) { PadState() }

    /** A controller key. Returns whether it was a pad button. */
    fun onKey(event: KeyEvent): Boolean {
        if (!isPad(event.device) && !KeyEvent.isGamepadButton(event.keyCode)) return false
        val mask = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> PadState.A; KeyEvent.KEYCODE_BUTTON_B -> PadState.B
            KeyEvent.KEYCODE_BUTTON_X -> PadState.X; KeyEvent.KEYCODE_BUTTON_Y -> PadState.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> PadState.LEFT_SHOULDER; KeyEvent.KEYCODE_BUTTON_R1 -> PadState.RIGHT_SHOULDER
            KeyEvent.KEYCODE_BUTTON_THUMBL -> PadState.LEFT_THUMB; KeyEvent.KEYCODE_BUTTON_THUMBR -> PadState.RIGHT_THUMB
            KeyEvent.KEYCODE_BUTTON_START -> PadState.START; KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> PadState.BACK
            KeyEvent.KEYCODE_BUTTON_MODE -> PadState.GUIDE
            KeyEvent.KEYCODE_DPAD_UP -> PadState.DPAD_UP; KeyEvent.KEYCODE_DPAD_DOWN -> PadState.DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> PadState.DPAD_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> PadState.DPAD_RIGHT
            KeyEvent.KEYCODE_BUTTON_L2 -> -1; KeyEvent.KEYCODE_BUTTON_R2 -> -2
            else -> return false
        }
        if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK) && !isPad(event.device)) return false
        val p = pad(event.deviceId)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (mask) {
            -1 -> p.leftTrigger = if (down) 255 else 0
            -2 -> p.rightTrigger = if (down) 255 else 0
            else -> p.buttons = if (down) p.buttons or mask else p.buttons and mask.inv()
        }
        return true
    }

    /** Stick, trigger and hat axes. Returns whether the event came from a controller. */
    fun onMotion(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        val p = pad(event.deviceId)
        fun axis(a: Int): Float {
            val range = device.getMotionRange(a, event.source) ?: return 0f
            val v = event.getAxisValue(a)
            return if (abs(v) <= range.flat) 0f else v
        }
        fun stick(v: Float) = (v * 32767).roundToInt().coerceIn(-32768, 32767)
        p.lx = stick(axis(MotionEvent.AXIS_X)); p.ly = -stick(axis(MotionEvent.AXIS_Y))
        p.rx = stick(axis(MotionEvent.AXIS_Z)); p.ry = -stick(axis(MotionEvent.AXIS_RZ))
        val lt = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))
        p.leftTrigger = (lt * 255).roundToInt().coerceIn(0, 255)
        p.rightTrigger = (rt * 255).roundToInt().coerceIn(0, 255)
        val hx = axis(MotionEvent.AXIS_HAT_X); val hy = axis(MotionEvent.AXIS_HAT_Y)
        var b = p.buttons and (PadState.DPAD_UP or PadState.DPAD_DOWN or PadState.DPAD_LEFT or PadState.DPAD_RIGHT).inv()
        if (hx < -0.5f) b = b or PadState.DPAD_LEFT; if (hx > 0.5f) b = b or PadState.DPAD_RIGHT
        if (hy < -0.5f) b = b or PadState.DPAD_UP; if (hy > 0.5f) b = b or PadState.DPAD_DOWN
        p.buttons = b
        return true
    }
}

/** What a game's binds can do with a pad input. */
sealed class PadAction {
    data class Key(val keycode: Int) : PadAction()
    object MouseLeft : PadAction()
    object MouseRight : PadAction()
    object None : PadAction()

    fun encode(): String = when (this) { is Key -> "key:$keycode"; MouseLeft -> "mouse:left"; MouseRight -> "mouse:right"; None -> "none" }

    companion object {
        fun decode(s: String): PadAction? = when {
            s.startsWith("key:") -> s.removePrefix("key:").toIntOrNull()?.let { Key(it) }
            s == "mouse:left" -> MouseLeft
            s == "mouse:right" -> MouseRight
            s == "none" -> None
            else -> null
        }

        /** Most PC games' keyboard-and-mouse layout (Madeira's PadBindings.template). */
        val TEMPLATE: Map<String, PadAction> = mapOf(
            "A" to Key(KeyEvent.KEYCODE_SPACE), "B" to Key(KeyEvent.KEYCODE_CTRL_LEFT), "X" to Key(KeyEvent.KEYCODE_E),
            "Y" to Key(KeyEvent.KEYCODE_R), "LB" to Key(KeyEvent.KEYCODE_Q), "RB" to Key(KeyEvent.KEYCODE_F),
            "LT" to MouseRight, "RT" to MouseLeft, "L3" to Key(KeyEvent.KEYCODE_SHIFT_LEFT), "R3" to Key(KeyEvent.KEYCODE_C),
            "Menu" to Key(KeyEvent.KEYCODE_ESCAPE), "View" to Key(KeyEvent.KEYCODE_TAB),
            "D↑" to Key(KeyEvent.KEYCODE_DPAD_UP), "D↓" to Key(KeyEvent.KEYCODE_DPAD_DOWN),
            "D←" to Key(KeyEvent.KEYCODE_DPAD_LEFT), "D→" to Key(KeyEvent.KEYCODE_DPAD_RIGHT),
        )

        fun label(a: PadAction): String = when (a) {
            is Key -> KeyEvent.keyCodeToString(a.keycode).removePrefix("KEYCODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
            MouseLeft -> "Left click"; MouseRight -> "Right click"; None -> "Nothing"
        }
    }
}

/** Where pad-to-keyboard events go: Wine's keyboard and mouse entry points. */
interface WineInput {
    fun key(keycode: Int, down: Boolean)
    /** Android motion action (BUTTON_PRESS 11, BUTTON_RELEASE 12, HOVER_MOVE 7, SCROLL 8) at desktop pixels. */
    fun mouse(action: Int, x: Int, y: Int, buttons: Int, vscroll: Int)
    val desktopWidth: Int
    val desktopHeight: Int
}

/** Pad samples as keys and mouse, for games that do not read controllers. */
class PadKeyboardMouse(private val input: WineInput, binds: Map<String, String>) {
    private val buttons = HashMap(PadAction.TEMPLATE).apply {
        for ((k, v) in binds) PadAction.decode(v)?.let { put(k, it) }
    }
    private val held = HashSet<String>()
    private val pressedWith = HashMap<String, PadAction>()
    private var stickKeys = IntArray(0)
    private var mouseButtons = 0
    var cursorX = -1f; var cursorY = -1f
    var mouseSpeed = 1400f   // desktop pixels per second at full deflection

    private fun act(a: PadAction, down: Boolean) {
        when (a) {
            is PadAction.Key -> input.key(a.keycode, down)
            PadAction.MouseLeft, PadAction.MouseRight -> {
                val bit = if (a == PadAction.MouseLeft) MotionEvent.BUTTON_PRIMARY else MotionEvent.BUTTON_SECONDARY
                mouseButtons = if (down) mouseButtons or bit else mouseButtons and bit.inv()
                ensureCursor()
                input.mouse(if (down) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE, cursorX.toInt(), cursorY.toInt(), mouseButtons, 0)
            }
            PadAction.None -> {}
        }
    }

    private fun ensureCursor() {
        if (cursorX < 0) { cursorX = input.desktopWidth / 2f; cursorY = input.desktopHeight / 2f }
    }

    fun feed(p: PadState, dt: Float) {
        val down = HashSet<String>()
        for ((name, mask) in PadState.BUTTONS) if (p.buttons and mask != 0) down += name
        if (p.leftTrigger >= 128) down += "LT"
        if (p.rightTrigger >= 128) down += "RT"
        for (name in held - down) pressedWith.remove(name)?.let { act(it, false) }
        for (name in down - held) buttons[name]?.let { pressedWith[name] = it; act(it, true) }
        held.clear(); held.addAll(down)
        // Left stick: WASD in eight sectors.
        val keys = stickSector(p.lx, p.ly)
        for (k in stickKeys) if (k !in keys) input.key(k, false)
        for (k in keys) if (k !in stickKeys) input.key(k, true)
        stickKeys = keys
        // Right stick: the mouse.
        val rx = p.rx / 32767f; val ry = -p.ry / 32767f
        val mag = hypot(rx, ry)
        if (mag > 0.15f) {
            ensureCursor()
            val curve = ((mag - 0.15f) / 0.85f).let { it * it } / mag
            cursorX = (cursorX + rx * curve * mouseSpeed * dt).coerceIn(0f, input.desktopWidth - 1f)
            cursorY = (cursorY + ry * curve * mouseSpeed * dt).coerceIn(0f, input.desktopHeight - 1f)
            input.mouse(MotionEvent.ACTION_HOVER_MOVE, cursorX.toInt(), cursorY.toInt(), mouseButtons, 0)
        }
    }

    private fun stickSector(x: Int, y: Int): IntArray {
        val fx = x / 32767f; val fy = y / 32767f
        if (hypot(fx, fy) < 0.35f) return IntArray(0)
        val angle = Math.toDegrees(atan2(fy.toDouble(), fx.toDouble())).let { if (it < 0) it + 360 else it }
        val sector = ((angle + 22.5) / 45).toInt() % 8
        return when (sector) {
            0 -> intArrayOf(KeyEvent.KEYCODE_D)
            1 -> intArrayOf(KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_W)
            2 -> intArrayOf(KeyEvent.KEYCODE_W)
            3 -> intArrayOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A)
            4 -> intArrayOf(KeyEvent.KEYCODE_A)
            5 -> intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S)
            6 -> intArrayOf(KeyEvent.KEYCODE_S)
            else -> intArrayOf(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D)
        }
    }

    fun releaseAll() {
        for ((_, a) in pressedWith) act(a, false)
        pressedWith.clear(); held.clear()
        for (k in stickKeys) input.key(k, false)
        stickKeys = IntArray(0)
    }
}
