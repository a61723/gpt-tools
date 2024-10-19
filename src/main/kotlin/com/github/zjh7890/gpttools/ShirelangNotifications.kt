package com.github.zjh7890.gpttools

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object ShirelangNotifications {
    private fun group(): NotificationGroup? {
        return NotificationGroupManager.getInstance().getNotificationGroup("Shirelang.notification.group")
    }

    fun info(project: Project, msg: String) {
        group()?.createNotification(msg, NotificationType.INFORMATION)?.notify(project)
    }

    fun error(project: Project, msg: String) {
        group()?.createNotification(msg, NotificationType.ERROR)?.notify(project)
    }

    fun warn(project: Project, msg: String) {
        group()?.createNotification(msg, NotificationType.WARNING)?.notify(project)
    }
}
