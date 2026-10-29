package com.fenghuolun.ktv

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log

/**
 * 权限获取助手 —— 获取方式对齐风火轮音乐播放器（PermissionHelper）。
 *
 * 风火轮的核心做法（车载版）：
 * - **不主动弹 Android 原生权限窗**，首选发广播给「哪吒美式/工具箱」静默授权：
 *   `nezha.toolbox.action.REQUEST_PERMISSIONS`（携带权限列表 + 包名）；
 * - 注册 `nezha.toolbox.action.PERMISSION_GRANTED` 接收授权结果（校验包名防串播）；
 * - 工具箱不响应时（如 ADB 直装的开发环境）由调用方走原生弹窗兜底，
 *   保证两种环境都能用。
 *
 * 与风火轮的差异：本项目是原生单 Activity（非 RN），授权结果直接回调
 * Kotlin lambda，不经过 RN EventEmitter。
 */
object NezhaPermissionHelper {
    private const val TAG = "NezhaPermission"

    /** 美式工具箱权限请求广播（与风火轮 PermissionHelper 一致）。 */
    private const val NEZHA_REQUEST_PERMISSION_ACTION = "nezha.toolbox.action.REQUEST_PERMISSIONS"

    /** 美式工具箱授权结果广播。 */
    private const val NEZHA_PERMISSION_GRANTED_ACTION = "nezha.toolbox.action.PERMISSION_GRANTED"

    /**
     * API 23 才加入 Framework 的常量，用字面量引用（与风火轮 PermissionHelper 一致），
     * 避免 minSdk 21 设备上 GETSTATIC 不存在的字段。
     */
    private const val REQUEST_INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"

    private var nezhaGrantReceiver: BroadcastReceiver? = null

    /**
     * 统一检测本应用全部运行时/特殊权限的授予状态。
     *
     * 存储按版本分流：Android 11+ 看 MANAGE_EXTERNAL_STORAGE（全部文件访问），
     * 以下看传统 READ/WRITE_EXTERNAL_STORAGE（targetSdk 25 + requestLegacyExternalStorage
     * 在车机 Android 10 上走的就是这条路）。
     *
     * v1.2.8：蓝牙麦（BLUETOOTH_CONNECT）已拆除 —— 老板真车验证行不通。
     */
    fun checkAllPermissions(context: Context): Map<String, Boolean> {
        val result = linkedMapOf<String, Boolean>()
        result["storage"] = hasStoragePermission(context)
        result[Manifest.permission.RECORD_AUDIO] = isGranted(context, Manifest.permission.RECORD_AUDIO)
        result[REQUEST_INSTALL_PACKAGES] = canInstallPackages(context)
        return result
    }

    /** 是否存在尚未授予的权限（启动时决定要不要发起一键授权广播，已全授予则零打扰）。 */
    fun hasMissingPermission(context: Context): Boolean =
        checkAllPermissions(context).values.any { !it }

    /** 存储权限（含 Android 11+ 全部文件访问）。 */
    fun hasStoragePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= 23) {
            isGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) &&
                isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            true
        }

    private fun isGranted(context: Context, permission: String): Boolean =
        if (Build.VERSION.SDK_INT >= 23) {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * 发起美式工具箱一键授权广播（静默，不弹任何系统窗口）。
     *
     * 只带本应用**声明过且需要运行时授权**的权限；普通权限（INTERNET 等）
     * targetSdk 25 下安装即授予，不进列表。
     */
    fun broadcastNezhaPermissionRequest(context: Context) {
        try {
            val permissions = arrayListOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO, // 演唱录音 / 评分
                REQUEST_INSTALL_PACKAGES, // 应用内更新
            )
            // ⚠️ MANAGE_EXTERNAL_STORAGE 字段 API 30 才存在，低版本车机上引用即
            // NoSuchFieldError —— 必须按版本进列表。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                permissions.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
            }
            val intent = Intent(NEZHA_REQUEST_PERMISSION_ACTION)
            intent.putStringArrayListExtra("permissions", permissions)
            intent.putExtra("package", context.packageName)
            context.sendBroadcast(intent)
            Log.i(TAG, "已发送美式工具箱权限请求广播，权限数: ${permissions.size}")
        } catch (error: Exception) {
            Log.e(TAG, "发送美式工具箱权限请求广播失败", error)
        }
    }

    /**
     * 注册工具箱授权结果接收器。
     *
     * 接收器**必须 exported**：授权方是另一个应用（工具箱）。校验包名，
     * 忽略其它应用的授权广播。重复注册安全（内部去重）。
     *
     * @param onGranted 授权回调（工具箱线程 → 调用方自行 post 到主线程）
     */
    fun registerNezhaGrantReceiver(context: Context, onGranted: (List<String>) -> Unit) {
        if (nezhaGrantReceiver != null) {
            Log.w(TAG, "NezhaGrantReceiver 已注册，跳过重复注册")
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                if (intent.action != NEZHA_PERMISSION_GRANTED_ACTION) return
                val targetPackage = intent.getStringExtra("package")
                if (targetPackage != null && targetPackage != context?.packageName) {
                    Log.w(TAG, "忽略其他应用的权限授予广播: $targetPackage")
                    return
                }
                val granted = intent.getStringArrayListExtra("permissions") ?: arrayListOf()
                Log.i(TAG, "收到美式工具箱权限授予广播，权限数: ${granted.size}")
                onGranted(granted)
            }
        }
        val filter = IntentFilter(NEZHA_PERMISSION_GRANTED_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            nezhaGrantReceiver = receiver
            Log.i(TAG, "NezhaGrantReceiver 注册成功")
        } catch (error: Exception) {
            Log.e(TAG, "NezhaGrantReceiver 注册失败", error)
        }
    }

    /** 注销广播接收器（onDestroy 成对调用，避免泄漏）。 */
    fun unregisterNezhaGrantReceiver(context: Context) {
        val receiver = nezhaGrantReceiver ?: return
        try {
            context.unregisterReceiver(receiver)
            Log.i(TAG, "NezhaGrantReceiver 已注销")
        } catch (error: Exception) {
            Log.w(TAG, "NezhaGrantReceiver 注销失败: ${error.message}")
        }
        nezhaGrantReceiver = null
    }
}
