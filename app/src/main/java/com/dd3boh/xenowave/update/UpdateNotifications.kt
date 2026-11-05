/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dd3boh.xenowave.R

object UpdateNotifications {
    const val CHANNEL_ID = "xenowave_updates"
    const val NOTIFICATION_ID = 0x5845

    private const val REQUEST_CONTENT = 41
    private const val REQUEST_ACTION = 42

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_notification_channel_description)
            }
        )
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun showPendingConfirmation(
        context: Context,
        versionCode: Int,
        confirmationIntent: Intent?,
    ) {
        val target = confirmationIntent ?: launchIntent(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            target,
            pendingIntentFlags(),
        )
        post(
            context,
            title = context.getString(R.string.update_notification_pending_title),
            text = context.getString(R.string.update_notification_pending_text, versionCode),
            contentIntent = contentIntent,
            actionLabel = context.getString(R.string.update_install_now),
            actionIntent = contentIntent,
        )
    }

    fun showNeedsPermission(context: Context) {
        val settingsIntent = UpdateInstaller(context).settingsIntent()
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            settingsIntent,
            pendingIntentFlags(),
        )
        post(
            context,
            title = context.getString(R.string.update_notification_authorize_title),
            text = context.getString(R.string.update_notification_authorize_text),
            contentIntent = contentIntent,
            actionLabel = context.getString(R.string.update_allow_installs_short),
            actionIntent = contentIntent,
        )
    }

    fun showFailure(context: Context) {
        post(
            context,
            title = context.getString(R.string.update_notification_failed_title),
            text = context.getString(R.string.update_notification_failed_text),
            contentIntent = launchPending(context),
        )
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission") // canPostNotifications() checks the permission/state first.
    private fun post(
        context: Context,
        title: String,
        text: String,
        contentIntent: PendingIntent,
        actionLabel: String? = null,
        actionIntent: PendingIntent? = null,
    ) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.xeno_icon_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (actionLabel != null && actionIntent != null) {
            builder.addAction(0, actionLabel, actionIntent)
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun launchPending(context: Context): PendingIntent {
        val intent = launchIntent(context)
        return PendingIntent.getActivity(context, REQUEST_ACTION, intent, pendingIntentFlags())
    }

    private fun launchIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_VIEW)

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
