package com.classfocus

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 기기관리자로 등록되면 설정에서 '관리자 해제'를 하기 전까지 앱 삭제가 막힌다 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "해제하면 선생님 화면에 표시됩니다."
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (ctx.getSharedPreferences("s", Context.MODE_PRIVATE).getString("code", "").isNullOrEmpty()) return
        ClassService.start(ctx)
    }
}
