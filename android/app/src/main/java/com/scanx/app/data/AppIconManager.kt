package com.scanx.app.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Đổi biểu tượng ứng dụng: bật 1 activity-alias (AndroidManifest), tắt các alias còn lại. */
object AppIconManager {
    data class Option(val alias: String, val label: String, val color: Long)

    val OPTIONS = listOf(
        Option("com.scanx.app.IconDefault", "Xanh dương (mặc định)", 0xFF2F6FED),
        Option("com.scanx.app.IconDark", "Tối", 0xFF1B1B1F),
        Option("com.scanx.app.IconGreen", "Xanh lá", 0xFF1E8E5A),
        Option("com.scanx.app.IconOrange", "Cam", 0xFFE8710A),
    )

    fun current(context: Context): String {
        val pm = context.packageManager
        return OPTIONS.firstOrNull { o ->
            val state = pm.getComponentEnabledSetting(ComponentName(context.packageName, o.alias))
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && o == OPTIONS.first())
        }?.alias ?: OPTIONS.first().alias
    }

    /** Launcher có thể mất vài giây để cập nhật icon; một số máy đóng app sau khi đổi. */
    fun set(context: Context, alias: String) {
        val pm = context.packageManager
        // Bật alias mới TRƯỚC rồi mới tắt các alias khác — tránh khoảnh khắc app không còn icon nào.
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, alias),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        OPTIONS.filter { it.alias != alias }.forEach { o ->
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, o.alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
