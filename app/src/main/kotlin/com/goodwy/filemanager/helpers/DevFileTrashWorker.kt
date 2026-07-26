package com.goodwy.filemanager.helpers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.goodwy.commons.models.FileDirItem
import com.goodwy.filemanager.extensions.config
import java.io.File

/**
 * 由 [DevFileAutoTrash] 通过 WorkManager 延迟 1 分钟排队执行的任务：把指定路径的
 * 开发文件（.kt/.java/.xml）移入回收站。用 WorkManager 而不是简单的 Handler 延迟，
 * 是为了让这个"1 分钟后自动清理"在 App 进程被系统杀掉、甚至手机重启之后依然能
 * 按时执行，不依赖 App 进程一直存活。
 */
class DevFileTrashWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        const val KEY_PATH = "path"
    }

    override fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val context = applicationContext

        // 用户可能在这 1 分钟内又把开关关掉了，尊重最新设置，不做任何操作
        if (!context.config.autoTrashDevFiles) {
            return Result.success()
        }

        val file = File(path)
        if (file.exists() && !file.isDirectory) {
            val item = FileDirItem(
                path,
                file.name,
                false,
                0,
                file.length(),
                file.lastModified()
            )
            TrashManager.moveToTrash(context, item)
        }

        return Result.success()
    }
}
