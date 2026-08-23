// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.osfans.trime.R
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.ui.main.LogActivity
import com.osfans.trime.util.createNotificationChannel
import com.osfans.trime.util.readText
import com.osfans.trime.util.subprocess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.systemservices.notificationManager

/**
 * Android UI concern: surfaces Rime deploy/restart progress as notifications.
 *
 * Kept out of [RimeDaemon] so the daemon only manages the engine lifecycle and
 * session refcounting.
 */
class DeployNotifier(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private var restartId = 0

    fun start(messageFlow: SharedFlow<RimeMessage<*>>) {
        createNotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.rime_daemon),
        )
        scope.launch {
            messageFlow.collect { handleRimeMessage(it) }
        }
    }

    /** Returns a notification id representing the ongoing restart. */
    fun notifyRestartStarted(): Int {
        val id = restartId++
        sendNotification(id) {
            setSmallIcon(R.drawable.ic_baseline_sync_24)
            setContentTitle(appContext.getString(R.string.rime_daemon))
            setContentText(appContext.getString(R.string.restarting_rime))
            setOngoing(true)
            setProgress(100, 0, true)
            setPriority(NotificationCompat.PRIORITY_HIGH)
        }
        return id
    }

    fun notifyRestartFinished(id: Int) {
        notificationManager.cancel(id)
    }

    private inline fun sendNotification(
        id: Int,
        buildAction: NotificationCompat.Builder.() -> Unit,
    ) {
        val builder =
            NotificationCompat
                .Builder(appContext, CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.rime_daemon))
        builder.buildAction()
        builder.build().let { notificationManager.notify(id, it) }
    }

    private suspend fun handleRimeMessage(it: RimeMessage<*>) {
        if (it !is RimeMessage.DeployMessage) return
        val buildNotification: NotificationCompat.Builder.() -> Unit
        when (it.data) {
            RimeMessage.DeployMessage.State.Start -> {
                buildNotification = {
                    setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
                    setContentText(appContext.getString(R.string.deploy_progress))
                    setProgress(0, 0, true)
                    setOngoing(true)
                    setAutoCancel(false)
                    setPriority(NotificationCompat.PRIORITY_DEFAULT)
                }
                withContext(Dispatchers.IO) { subprocess("logcat", "--clear") }
            }
            RimeMessage.DeployMessage.State.Success -> {
                buildNotification = {
                    setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
                    setColor(Color.GREEN)
                    setContentText(appContext.getString(R.string.deploy_finish))
                    setOngoing(false)
                    setTimeoutAfter(3000L)
                    setAutoCancel(true)
                    setPriority(NotificationCompat.PRIORITY_DEFAULT)
                }
            }
            RimeMessage.DeployMessage.State.Failure -> {
                val intent =
                    Intent(appContext, LogActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        val log =
                            subprocess("logcat", "-v", "brief", "-s", "rime.trime:W", "-d")
                                .readText()
                        putExtra(LogActivity.FROM_DEPLOY, true)
                        putExtra(LogActivity.DEPLOY_FAILURE_TRACE, log)
                    }
                buildNotification = {
                    setSmallIcon(R.drawable.ic_baseline_warning_24)
                    setColor(Color.YELLOW)
                    setContentText(appContext.getString(R.string.view_deploy_failure_log))
                    setContentIntent(
                        PendingIntent.getActivity(
                            appContext,
                            0,
                            intent,
                            PendingIntent.FLAG_ONE_SHOT or
                                PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    setOngoing(false)
                    setAutoCancel(true)
                    setPriority(NotificationCompat.PRIORITY_HIGH)
                }
            }
        }
        sendNotification(MESSAGE_ID, buildNotification)
    }

    private companion object {
        const val CHANNEL_ID = "rime-daemon"
        const val MESSAGE_ID = 2331
    }
}
