package com.classfocus

import android.content.Context
import android.os.Handler
import android.os.Looper

/** 수업 상태(공유). 서비스/접근성서비스/액티비티가 같은 프로세스에서 함께 사용 */
object ClassState {
    @Volatile var active = false
    @Volatile var allowed: Set<String> = emptySet()
    @Volatile var listener: (() -> Unit)? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("s", Context.MODE_PRIVATE)
        active = p.getBoolean("active", false)
        allowed = p.getStringSet("allowed", emptySet())!!.toSet()
    }

    fun update(ctx: Context, a: Boolean, list: Set<String>) {
        active = a; allowed = list
        ctx.getSharedPreferences("s", Context.MODE_PRIVATE).edit()
            .putBoolean("active", a).putStringSet("allowed", list).apply()
        Handler(Looper.getMainLooper()).post { listener?.invoke() }
    }
}
