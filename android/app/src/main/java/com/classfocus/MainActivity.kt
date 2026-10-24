package com.classfocus

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("s", Context.MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ClassState.load(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 48) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); ClassState.listener = { render() }; render() }
    override fun onPause() { ClassState.listener = null; super.onPause() }
    @Deprecated("") override fun onBackPressed() { if (!ClassState.active) super.onBackPressed() }

    private fun render() {
        root.removeAllViews()
        if (ClassState.active) renderLocked() else renderSetup()
    }

    private fun text(s: String, size: Float = 16f) = TextView(this).apply { text = s; textSize = size; setPadding(0, 12, 0, 12) }
    private fun btn(s: String, f: () -> Unit) = Button(this).apply { text = s; setOnClickListener { f() } }

    private fun renderLocked() {
        root.addView(text("📵 수업 중", 28f).apply { gravity = Gravity.CENTER })
        root.addView(text("선생님이 허용한 앱만 사용할 수 있어요.").apply { gravity = Gravity.CENTER })
        val pm = packageManager
        var n = 0
        ClassState.allowed.forEach { pkg ->
            val i = pm.getLaunchIntentForPackage(pkg) ?: return@forEach
            val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            root.addView(btn(label) { startActivity(i) }); n++
        }
        if (n == 0) root.addView(text("(허용된 앱이 아직 없어요)").apply { gravity = Gravity.CENTER })
    }

    private fun adminComp() = ComponentName(this, AdminReceiver::class.java)
    private fun hasAdmin() = getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComp())
    private fun hasA11y() = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(packageName)
    private fun mark(b: Boolean) = if (b) "✅" else "❌"

    private fun renderSetup() {
        root.addView(text("수업집중", 28f))
        fun field(hint: String, key: String, def: String) = EditText(this).apply {
            this.hint = hint; setText(prefs.getString(key, def)); setSingleLine()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }.also { root.addView(it) }
        val url = field("서버 주소 (ws://…/ws)", "url", "ws://10.0.2.2:3000/ws")
        val code = field("수업 코드(6자리)", "code", "")
        val name = field("이름", "name", "")

        root.addView(text("필수 권한 (수업 중 잠금과 삭제 방지에 필요)"))
        root.addView(btn("${mark(hasA11y())} 접근성 서비스 켜기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(btn("${mark(hasAdmin())} 삭제 방지(기기관리자) 켜기") {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp())
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "수업집중 앱이 실수로 삭제되는 것을 막습니다."))
        })
        root.addView(btn("${mark(Settings.canDrawOverlays(this))} '다른 앱 위에 표시' 허용") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("수업에 연결") {
            if (prefs.getString("id", null) == null) prefs.edit().putString("id", ClassService.newId()).apply()
            prefs.edit().putString("url", url.text.toString().trim()).putString("code", code.text.toString().trim())
                .putString("name", name.text.toString().trim().ifEmpty { "학생" }).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            ClassService.start(this)
            Toast.makeText(this, "연결 중…", Toast.LENGTH_SHORT).show()
        })
    }
}
