package com.classfocus

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 서버와 WebSocket 연결을 유지하는 포그라운드 서비스 */
class ClassService : Service() {
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val h = Handler(Looper.getMainLooper())
    private var stopped = false

    private val beat = object : Runnable {
        override fun run() {
            ws?.send(JSONObject().put("type", "status").put("tamper", tamper() ?: JSONObject.NULL).toString())
            h.postDelayed(this, 5000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val ch = "cls"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "수업집중", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, ch).setContentTitle("수업집중 실행 중")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock).build())
        ClassState.load(this)
        stopped = false
        connect()
        h.removeCallbacks(beat); h.post(beat)
        return START_STICKY
    }

    private fun connect() {
        val p = getSharedPreferences("s", Context.MODE_PRIVATE)
        val url = p.getString("url", "") ?: ""
        if (url.isBlank()) return
        ws?.cancel()
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(JSONObject().put("type", "hello").put("role", "student").put("platform", "android")
                    .put("code", p.getString("code", "")).put("name", p.getString("name", "학생"))
                    .put("id", p.getString("id", "")).toString())
                w.send(JSONObject().put("type", "apps").put("apps", installedApps()).toString())
            }
            override fun onMessage(w: WebSocket, text: String) {
                val m = JSONObject(text)
                if (m.optString("type") == "state") {
                    val arr = m.optJSONArray("allowed") ?: JSONArray()
                    val set = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    ClassState.update(this@ClassService, m.optBoolean("active"), set)
                    if (m.optBoolean("active")) bringToFront()
                }
            }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = retry()
            override fun onClosed(w: WebSocket, code: Int, reason: String) = retry()
        })
    }

    private fun retry() { if (!stopped) h.postDelayed({ connect() }, 3000) }

    private fun bringToFront() = startActivity(
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

    private fun installedApps(): JSONArray {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val arr = JSONArray()
        pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }
            .forEach { arr.put(JSONObject().put("pkg", it.first).put("label", it.second)) }
        return arr
    }

    /** 기기관리자/접근성이 꺼졌는지 감지해 교사 화면에 표시 */
    private fun tamper(): String? {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) return "admin_off"
        val en = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (!en.contains(packageName)) return "accessibility_off"
        return null
    }

    override fun onDestroy() { stopped = true; h.removeCallbacksAndMessages(null); ws?.cancel(); super.onDestroy() }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, ClassService::class.java))
        fun newId() = UUID.randomUUID().toString()
    }
}
