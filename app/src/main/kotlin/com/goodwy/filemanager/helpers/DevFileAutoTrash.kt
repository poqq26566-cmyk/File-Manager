package com.goodwy.filemanager.helpers

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.goodwy.filemanager.extensions.config
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * "自动清理开发文件"功能：设置里的开关默认关闭。开启后，文件管理器每次列出一个
 * 目录内容时，如果发现里面有之前没见过的 .kt / .java / .xml 后缀文件，就记下它，
 * 并通过 WorkManager 排一个 1 分钟后执行的延迟任务（[DevFileTrashWorker]）。到点后
 * 如果开关还开着、文件还在原地，就自动把它移动到回收站，用户可以在回收站里找回。
 *
 * 用 WorkManager 而不是简单的内存定时器，是因为它由系统持久化调度，App 进程被杀
 * 掉、甚至手机重启，只要设备重新开机，之前排队的任务依然会按时触发。
 *
 * 已经见过的文件路径本身只存在内存里，不做持久化——这个功能本来就是"监控新出现
 * 的文件"，不需要跨进程重启记住"曾经见过"这件事；就算重启后重新扫描到同一个文件
 * 再排一次队，也不会重复移动（WorkManager 用路径做唯一 work name，加上 KEEP 策略，
 * 已经排队的任务不会被重复排队）。
 */
object DevFileAutoTrash {

    private val TRACKED_EXTENSIONS = setOf("kt", "java", "xml")
    private const val DELAY_MINUTES = 1L
    private const val WORK_NAME_PREFIX = "dev_file_auto_trash:"

    private val seenPaths = Collections.synchronizedSet(HashSet<String>())

    /**
     * 在目录内容被加载/展示出来的地方调用。传入这一批被"识别到"的文件路径
     * （只需要非目录的文件路径即可，目录会被自动忽略）。
     */
    fun onFilesDetected(context: Context, filePaths: List<String>) {
        val appContext = context.applicationContext
        if (!appContext.config.autoTrashDevFiles) {
            return
        }

        for (path in filePaths) {
            val extension = path.substringAfterLast('.', "").lowercase()
            if (extension !in TRACKED_EXTENSIONS) {
                continue
            }
            if (!seenPaths.add(path)) {
                // 已经见过这个文件了，不重复安排
                continue
            }
            scheduleAutoTrash(appContext, path)
        }
    }

    private fun scheduleAutoTrash(context: Context, path: String) {
        val inputData = Data.Builder()
            .putString(DevFileTrashWorker.KEY_PATH, path)
            .build()

        val request = OneTimeWorkRequestBuilder<DevFileTrashWorker>()
            .setInitialDelay(DELAY_MINUTES, TimeUnit.MINUTES)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_PREFIX + path,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
