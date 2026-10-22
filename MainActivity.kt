package com.example.btgamepad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.hypot

// HID report descriptor: 16 tombol, hat (D-pad), 2 stik, 2 trigger (9 byte per laporan)
private val DESC = intArrayOf(
    0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,
    0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
    0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
    0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
    0x35, 0x00, 0x45, 0x00, 0x65, 0x00,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
    0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
    0x05, 0x02, 0x09, 0xC5, 0x09, 0xC4, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
    0xC0
).let { a -> ByteArray(a.size) { a[it].toByte() } }

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private lateinit var pad: PadView
    private lateinit var conn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pad = PadView(this)
        pad.onReport = { r -> host?.let { hid?.sendReport(it, 0, r) } }
        val d = resources.displayMetrics.density

        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply {
            text = t; textSize = 11f; isAllCaps = false
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
            setOnClickListener { f(this) }
        }

        val grow = btn("Besar") { pad.grow(1.1f) }
        val shrink = btn("Kecil") { pad.grow(0.9f) }
        grow.visibility = View.GONE
        shrink.visibility = View.GONE
        conn = btn("Hubungkan") { pick() }

        val bar = LinearLayout(this).apply {
            addView(btn(if (pad.ps) "PS" else "Xbox") {
                pad.ps = !pad.ps
                it.text = if (pad.ps) "PS" else "Xbox"
            })
            addView(btn("Atur") {
                pad.edit = !pad.edit
                it.text = if (pad.edit) "Selesai" else "Atur"
                grow.visibility = if (pad.edit) View.VISIBLE else View.GONE
                shrink.visibility = grow.visibility
            })
            addView(grow)
            addView(shrink)
            addView(btn("Reset") { pad.reset() })
            addView(btn("Pairing") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
            addView(conn)
        }
        val root = FrameLayout(this)
        root.addView(pad)
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1
        ) else start()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) = start()

    private fun start() {
        val ad = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (ad == null) {
            Toast.makeText(this, "HP ini tidak punya Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        ad.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                h.registerApp(
                    BluetoothHidDeviceAppSdpSettings("BT Gamepad", "Gamepad HP", "BtGamepad", 0x08.toByte(), DESC),
                    null, null, mainExecutor,
                    object : BluetoothHidDevice.Callback() {
                        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                            host = if (state == BluetoothProfile.STATE_CONNECTED) device else null
                            runOnUiThread {
                                conn.text = host?.let { "✓ " + (it.name ?: "Terhubung") } ?: "Hubungkan"
                                pad.push(true)
                            }
                        }
                    })
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun pick() {
        val h = hid
        val ad = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val l = ad?.bondedDevices?.toList().orEmpty()
        if (h == null || l.isEmpty()) {
            Toast.makeText(this, "Pairing dulu lewat tombol Pairing", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Pilih HP tujuan")
            .setItems(l.map { it.name ?: it.address }.toTypedArray()) { _, i -> h.connect(l[i]) }
            .show()
    }

    override fun onDestroy() {
        hid?.unregisterApp()
        super.onDestroy()
    }
}

class Ctrl(val id: String, var x: Float, var y: Float, var s: Float) {
    // 0 = tombol, 1 = tombol kecil, 2 = stik analog
    val kind
        get() = when (id) {
            "sl", "sr" -> 2
            "sel", "home", "start", "ls", "rs" -> 1
            else -> 0
        }
}

class PadView(c: Context) : View(c) {
    companion object {
        const val DEF = "lt .09 .10 1;lb .09 .28 1;rt .91 .10 1;rb .91 .28 1;sl .16 .62 1;sr .63 .74 1;" +
            "up .34 .60 .9;down .34 .86 .9;left .27 .73 .9;right .41 .73 .9;" +
            "y .84 .44 1;a .84 .76 1;x .77 .60 1;b .91 .60 1;" +
            "sel .40 .20 1;home .50 .20 1;start .60 .20 1;ls .06 .88 1;rs .94 .88 1"
    }

    private val d = resources.displayMetrics.density
    private val prefs = c.getSharedPreferences("pad", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ctrls = ArrayList<Ctrl>()
    private val owner = HashMap<Int, Ctrl>()
    private val stick = hashMapOf("sl" to FloatArray(2), "sr" to FloatArray(2))
    private var sel: Ctrl? = null
    private var gx = 0f
    private var gy = 0f
    private var last = ByteArray(0)
    var onReport: ((ByteArray) -> Unit)? = null

    var ps = prefs.getBoolean("ps", false)
        set(v) {
            field = v
            prefs.edit().putBoolean("ps", v).apply()
            invalidate()
        }

    var edit = false
        set(v) {
            field = v
            owner.clear()
            stick.values.forEach { it.fill(0f) }
            sel = null
            if (!v) save()
            push()
            invalidate()
        }

    init {
        load(prefs.getString("lay", DEF) ?: DEF)
    }

    private fun load(s: String) {
        ctrls.clear()
        try {
            s.split(";").forEach {
                val p = it.split(" ")
                ctrls.add(Ctrl(p[0], p[1].toFloat(), p[2].toFloat(), p[3].toFloat()))
            }
        } catch (e: Exception) {
            if (s != DEF) load(DEF)
        }
    }

    private fun save() {
        prefs.edit().putString("lay", ctrls.joinToString(";") { "${it.id} ${it.x} ${it.y} ${it.s}" }).apply()
    }

    fun reset() {
        load(DEF)
        save()
        invalidate()
    }

    fun grow(k: Float) {
        sel?.let { it.s = (it.s * k).coerceIn(0.5f, 2.2f) }
        save()
        invalidate()
    }

    private fun rad(c: Ctrl) = d * c.s * when (c.kind) {
        2 -> 60f
        1 -> 20f
        else -> 28f
    }

    private fun hit(x: Float, y: Float) =
        ctrls.lastOrNull { hypot(x - it.x * width, y - it.y * height) <= rad(it) * 1.15f }

    private fun label(id: String) = when (id) {
        "a" -> if (ps) "✕" else "A"
        "b" -> if (ps) "○" else "B"
        "x" -> if (ps) "□" else "X"
        "y" -> if (ps) "△" else "Y"
        "lb" -> if (ps) "L1" else "LB"
        "rb" -> if (ps) "R1" else "RB"
        "lt" -> if (ps) "L2" else "LT"
        "rt" -> if (ps) "R2" else "RT"
        "sel" -> if (ps) "Share" else "Back"
        "start" -> if (ps) "Opt" else "Start"
        "home" -> if (ps) "PS" else "Xbox"
        "up" -> "▲"
        "down" -> "▼"
        "left" -> "◀"
        "right" -> "▶"
        "ls" -> "L3"
        "rs" -> "R3"
        else -> ""
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xFF161A24.toInt())
        val on = owner.values.map { it.id }.toSet()
        for (c in ctrls) {
            val cx = c.x * width
            val cy = c.y * height
            val r = rad(c)
            paint.style = Paint.Style.FILL
            paint.color = when {
                c.kind == 2 -> 0xFF1E2330.toInt()
                c.id in on -> 0xFF5AA9FF.toInt()
                else -> 0xFF272D3B.toInt()
            }
            cv.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d * 2
            paint.color = when {
                !edit -> 0xFF4B5468.toInt()
                c === sel -> Color.WHITE
                else -> 0xFFF0B44C.toInt()
            }
            cv.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.FILL
            if (c.kind == 2) {
                val v = stick[c.id]!!
                paint.color = 0xFF4B5468.toInt()
                cv.drawCircle(cx + v[0] * r, cy + v[1] * r, r * 0.42f, paint)
            } else {
                paint.color = 0xFFE6E9F0.toInt()
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = d * (if (c.kind == 1) 11f else 16f) * c.s
                cv.drawText(label(c.id), cx, cy + paint.textSize * 0.35f, paint)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val pid = e.getPointerId(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val c = hit(e.getX(i), e.getY(i))
                if (edit) {
                    sel = c
                    if (c != null) {
                        gx = e.getX(i) - c.x * width
                        gy = e.getY(i) - c.y * height
                    }
                } else if (c != null) {
                    owner[pid] = c
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (edit) {
                    sel?.let {
                        it.x = ((e.getX(0) - gx) / width).coerceIn(0f, 1f)
                        it.y = ((e.getY(0) - gy) / height).coerceIn(0f, 1f)
                    }
                } else {
                    for (k in 0 until e.pointerCount) {
                        val c = owner[e.getPointerId(k)] ?: continue
                        if (c.kind != 2) continue
                        var dx = (e.getX(k) - c.x * width) / rad(c)
                        var dy = (e.getY(k) - c.y * height) / rad(c)
                        val m = hypot(dx, dy)
                        if (m > 1f) {
                            dx /= m
                            dy /= m
                        }
                        stick[c.id] = floatArrayOf(dx, dy)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val c = owner.remove(pid)
                if (c != null && c.kind == 2) stick[c.id]!!.fill(0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (edit) save()
                owner.clear()
                stick.values.forEach { it.fill(0f) }
            }
        }
        invalidate()
        push()
        return true
    }

    fun push(force: Boolean = false) {
        val r = report()
        if (force || !r.contentEquals(last)) {
            last = r
            onReport?.invoke(r)
        }
    }

    private fun report(): ByteArray {
        val on = owner.values.map { it.id }.toSet()
        fun f(id: String) = if (id in on) 1 else 0
        val btn = f("a") or (f("b") shl 1) or (f("x") shl 3) or (f("y") shl 4) or
            (f("lb") shl 6) or (f("rb") shl 7) or (f("lt") shl 8) or (f("rt") shl 9) or
            (f("sel") shl 10) or (f("start") shl 11) or (f("home") shl 12) or
            (f("ls") shl 13) or (f("rs") shl 14)
        val hat = intArrayOf(5, 6, 7, 4, 8, 0, 3, 2, 1)[
            (f("right") - f("left") + 1) * 3 + f("up") - f("down") + 1
        ]
        fun ax(v: Float) = (v * 127).toInt().toByte()
        val lv = stick["sl"]!!
        val rv = stick["sr"]!!
        return byteArrayOf(
            btn.toByte(), (btn shr 8).toByte(), hat.toByte(),
            ax(lv[0]), ax(lv[1]), ax(rv[0]), ax(rv[1]),
            (if ("lt" in on) 255 else 0).toByte(), (if ("rt" in on) 255 else 0).toByte()
        )
    }
}
