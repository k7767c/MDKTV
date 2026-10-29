package com.fenghuolun.ktv

import android.app.Application
import android.os.Process
import android.util.Log
import com.liulishuo.okdownload.OkDownload

/**
 * 应用入口。
 *
 * 除了原有的 OkDownload 初始化，这里负责两件"出问题能查"的基础设施：
 *
 * 1. **KtvLog.init** —— 统一日志（内存环 + 本地文件 + logcat 镜像）。
 * 2. **崩溃捕获** —— 任何线程（包括服务端的 accept/pool 线程）抛未捕获异常，
 *    都会把堆栈 + 最近日志写进 `files/logs/crash_*.txt`，再链回系统默认 handler
 *    （系统照常弹"已停止"）。以前车机上 app 崩了就是无声无息，只能靠猜。
 */
class KtvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instanceRef = this
        OkDownload.setSingletonInstance(OkDownload.Builder(this).build())
        KtvLog.init(this)
        installCrashHandler()
        Log.i(TAG, "KTV application initialized without native services")
    }

    /**
     * 全局未捕获异常兜底。
     *
     * 注意必须**链回**原 handler：直接吞掉或自行 System.exit 会让系统不记录
     * tombstone、不弹崩溃框，反而更难排查。这里只做"写盘"这一件系统不会做的事。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { KtvLog.writeCrash(thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // 理论上不会走到这（系统总装有默认 handler）；兜底别留活尸进程
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
    }

    companion object {
        private const val TAG = "KtvApplication"

        @JvmStatic
        lateinit var instanceRef: KtvApplication
            private set

        @JvmStatic
        fun getInstance(): KtvApplication = instanceRef
    }
}
