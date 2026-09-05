// SPDX-FileCopyrightText: 2015 - 2026 Rime community
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.osfans.trime.R

/**
 * Notification surface for IME package import/compile.
 *
 * A single notification id is reused across the whole import → compile →
 * complete lifecycle so the user sees one continuous item instead of a gap
 * between "importing" and "compiling". The completion notification is left in
 * the shade (not auto-dismissed) so the result is visible until the user acts
 * on it.
 */
object ImePackageNotifications {
    const val CHANNEL_ID = "ime-package-compile"
    const val NOTIFICATION_ID = 2332

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.ime_package_compile),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.ime_package_compile)
                }
            manager.createNotificationChannel(channel)
        }
    }

    /** Post immediately when the user starts importing a package. */
    fun notifyImporting(context: Context) {
        send(context) {
            setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
            setContentTitle(context.getString(R.string.ime_package_compile))
            setContentText(context.getString(R.string.ime_package_importing))
            setProgress(0, 0, true)
            setOngoing(true)
            setAutoCancel(false)
            setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }
    }

    /** Build the foreground notification used while the compile service runs. */
    fun buildCompilingNotification(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
            .setContentTitle(context.getString(R.string.ime_package_compile))
            .setContentText(context.getString(R.string.ime_package_compiling))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /**
     * Mark the import/compile as finished.
     *
     * The notification intentionally has no timeout and is not auto-cancelled:
     * the user should be able to see the result until they swipe it away.
     */
    fun notifyCompiled(context: Context) {
        send(context) {
            setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
            setColor(Color.GREEN)
            setContentTitle(context.getString(R.string.ime_package_compile))
            setContentText(context.getString(R.string.ime_package_compiled_switch_hint))
            setOngoing(false)
            setAutoCancel(false)
            setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }
    }

    /** Called after the active package has been reloaded in memory. */
    fun notifyRefreshed(context: Context) {
        send(context) {
            setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
            setColor(Color.GREEN)
            setContentTitle(context.getString(R.string.ime_package_compile))
            setContentText(context.getString(R.string.ime_package_compiled_active_refreshed))
            setOngoing(false)
            setAutoCancel(false)
            setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }
    }

    fun notifyCompileFailed(context: Context) {
        send(context) {
            setSmallIcon(R.drawable.ic_baseline_warning_24)
            setColor(Color.YELLOW)
            setContentTitle(context.getString(R.string.ime_package_compile))
            setContentText(context.getString(R.string.ime_package_compile_failed))
            setOngoing(false)
            setAutoCancel(false)
            setPriority(NotificationCompat.PRIORITY_HIGH)
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private inline fun send(
        context: Context,
        buildAction: NotificationCompat.Builder.() -> Unit,
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.ime_package_compile))
        builder.buildAction()
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
