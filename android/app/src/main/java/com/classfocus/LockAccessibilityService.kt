package com.classfocus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/** 수업 중 허용되지 않은 앱이 앞에 나오면 즉시 수업집중 화면으로 되돌린다 */
class LockAccessibilityService : AccessibilityService() {
    private val alwaysOk = setOf(
        "com.android.systemui", "com.android.server.telecom",
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", // 긴급전화
    )

    override fun onServiceConnected() { ClassState.load(this) }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !ClassState.active) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ClassState.allowed || pkg in alwaysOk || pkg.contains("inputmethod")) return
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onInterrupt() {}
}
