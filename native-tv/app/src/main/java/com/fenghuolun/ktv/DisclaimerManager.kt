package com.fenghuolun.ktv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 麦动KTV（风火轮K歌版）免责与风险声明固定文本。
 *
 * 体系对齐风火轮音乐播放器的 disclaimer 包（DisclaimerText/DisclaimerManager）：
 * 文本集中维护，禁止在业务代码中散落或删减「版权自负 / 无担保 / 权限用途 / 风险自负」条款。
 */
object DisclaimerText {

    const val DIALOG_TITLE = "免责与风险使用声明"

    const val DIALOG_TEXT =
        "⚠️ 重要提示：请仔细阅读以下条款，勾选同意后方可使用本软件\n\n" +
        "1. 项目属性\n" +
        "风火轮K歌版（麦动KTV）是面向车载场景的 K 歌应用，曲库与歌曲资源来自第三方网络服务，" +
        "仅供个人娱乐体验与学习研究，禁止商用售卖、打包分发、逆向破解、违规引流。\n\n" +
        "2. 无担保承诺（AS IS 现状提供）\n" +
        "本软件按照「现状」提供，开发者不作出任何明示或默示担保：\n" +
        "- 不保证兼容所有安卓车机、定制车载系统；\n" +
        "- 不保证无闪退、断连、破音、下载中断等技术问题；\n" +
        "- 演唱评分与音准为本地算法估算结果，仅供娱乐参考，不代表真实演唱水平。\n\n" +
        "3. 权限与第三方联动风险自负\n" +
        "本APP所需权限（存储、网络、录音、局域网）均为实现以下功能所必需：\n" +
        "- 存储/网络：曲库同步、歌曲下载、U盘导入；\n" +
        "- 录音：演唱录音与评分；\n" +
        "- 局域网：手机扫码点歌、手机当话筒（数据仅在本地局域网流转，不上传隐私数据）。\n" +
        "用户主动授予权限即代表知晓上述用途，由此产生的功能失效等后果由使用者自行承担。\n\n" +
        "4. 版权与用户行为责任\n" +
        "- 音乐、歌词资源的版权合法性由使用者自行负责，本应用不存储、不提供任何版权音视频资源；\n" +
        "- 用户不得利用本软件侵犯第三方版权、规避系统限制、从事违法违规行为，" +
        "一切违规操作的法律责任由使用人独立承担。\n\n" +
        "5. 风险自负原则\n" +
        "您下载、安装、运行本软件，即表示已完整阅读、理解并自愿接受全部免责条款。" +
        "因使用本软件造成的车机系统异常、存储写满、数据异常、间接损失等全部风险，" +
        "均由使用者自行承担，项目开发者不承担任何直接或间接法律与经济责任。\n" +
        "⚠️ 行车过程中请专注驾驶，切勿在行驶中操作点歌界面。"

    const val CHECKBOX_TEXT = "我已完整阅读并同意以上全部条款（勾选后解锁进入软件）"

    /** 首页常驻风险提示（点击查看完整声明）。 */
    const val HOME_FOOTER =
        "⚠ 曲库来自第三方网络，仅供个人娱乐 · 版权风险自负 · 评分仅供娱乐参考 ｜ 点击查看完整声明"
}

/**
 * 首次启动免责声明弹窗管理器（对齐风火轮音乐播放器 DisclaimerManager）。
 *
 * 行为约定：
 * - 首次冷启动显示**不可绕过**弹窗，同意前禁止进入主程序；
 * - 同意标记写私有 SharedPreferences，异常按未同意处理；
 * - 首页常驻提示条可随时重新查看完整声明（[showAlways]）。
 */
object DisclaimerManager {
    private const val TAG = "DisclaimerManager"
    private const val PREFS_NAME = "ktv_legal_prefs"
    private const val KEY_AGREE_DISCLAIMER = "agree_disclaimer"

    /** 读取私有 SharedPreferences 同意标记；异常时按未同意处理。 */
    fun hasAgreed(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AGREE_DISCLAIMER, false)
    } catch (error: Exception) {
        Log.e(TAG, "read agreement failed", error)
        false
    }

    /** 首次启动显示不可绕过弹窗；已同意则直接回调。 */
    fun showIfNeeded(activity: Activity, onAgreed: Runnable) {
        if (hasAgreed(activity)) {
            onAgreed.run()
            return
        }
        show(activity, onAgreed)
    }

    /** 强制展示（首页提示条重新查看用）。 */
    fun showAlways(activity: Activity) {
        show(activity, null)
    }

    /**
     * 展示声明弹窗。
     *
     * @param onAgreed 非空 = 首次启动拦截模式（同意并落库后回调）；null = 普通查看模式
     *   （同意按钮退化为「知道了」）。深色车机 UI：PANEL 底 + 金色标题。
     */
    private fun show(activity: Activity, onAgreed: Runnable?) {
        try {
            val dialog = Dialog(activity)
            // 首次启动拦截模式下不可绕过；查看模式允许关闭
            val blocking = onAgreed != null
            dialog.setCancelable(!blocking)
            dialog.setCanceledOnTouchOutside(!blocking)
            dialog.setOnKeyListener { _, keyCode, _ ->
                blocking && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
            }

            val root = LinearLayout(activity)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(dp(activity, 24), dp(activity, 18), dp(activity, 24), dp(activity, 18))
            root.setBackground(roundedBackground())

            val title = TextView(activity)
            title.text = DisclaimerText.DIALOG_TITLE
            title.setTextColor(Color.rgb(245, 190, 89)) // GOLD
            title.textSize = 21f
            title.setTypeface(Typeface.DEFAULT_BOLD)
            title.gravity = Gravity.CENTER
            root.addView(title, LinearLayout.LayoutParams(-1, dp(activity, 44)))

            val content = TextView(activity)
            content.text = DisclaimerText.DIALOG_TEXT
            content.setTextColor(Color.rgb(224, 224, 224))
            content.textSize = 14f
            content.setLineSpacing(dp(activity, 2).toFloat(), 1f)
            val scroll = ScrollView(activity)
            scroll.addView(content, LinearLayout.LayoutParams(-1, 0))
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

            val enter = Button(activity)
            enter.setText(if (blocking) "同意进入" else "知道了")
            enter.isAllCaps = false
            enter.isEnabled = !blocking
            root.addView(enter, LinearLayout.LayoutParams(-1, dp(activity, 52)))

            var agree: CheckBox? = null
            if (blocking) {
                agree = CheckBox(activity)
                agree.setText(DisclaimerText.CHECKBOX_TEXT)
                agree.setTextColor(Color.rgb(245, 190, 89))
                agree.textSize = 13f
                agree.setPadding(0, dp(activity, 8), 0, dp(activity, 6))
                agree.setOnCheckedChangeListener { _, checked -> enter.isEnabled = checked }
                root.addView(agree, LinearLayout.LayoutParams(-1, dp(activity, 54)))
            }

            enter.setOnClickListener {
                if (blocking && agree?.isChecked != true) return@setOnClickListener
                if (blocking) {
                    val saved = try {
                        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean(KEY_AGREE_DISCLAIMER, true).commit()
                    } catch (error: Exception) {
                        Log.e(TAG, "save agreement failed", error)
                        false
                    }
                    if (!saved) return@setOnClickListener
                }
                dialog.dismiss()
                onAgreed?.run()
            }

            dialog.setContentView(root)
            val window = dialog.window
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent)
                val params = WindowManager.LayoutParams().apply {
                    copyFrom(window.attributes)
                    width = minOf(
                        (activity.resources.displayMetrics.widthPixels * 0.72f).toInt(),
                        dp(activity, 780),
                    )
                    height = (activity.resources.displayMetrics.heightPixels * 0.86f).toInt()
                    // ⚠️ 不用 Window.setDimAmount()（API 33 才有，车机 Android 10 直接崩）；
                    // 用 LayoutParams.dimAmount + FLAG_DIM_BEHIND（API 1 起可用）。
                    dimAmount = 0.72f
                }
                window.attributes = params
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            dialog.show()
            // show 后再次设置宽高，兼容部分定制车机 Dialog 初次测量覆盖属性的问题
            // （与风火轮 DisclaimerManager 同款兼容）。
            window?.setLayout(
                minOf(
                    (activity.resources.displayMetrics.widthPixels * 0.72f).toInt(),
                    dp(activity, 780),
                ),
                (activity.resources.displayMetrics.heightPixels * 0.86f).toInt(),
            )
            enter.requestFocus()
        } catch (error: Exception) {
            Log.e(TAG, "show disclaimer failed", error)
            // 弹窗异常时不绕过条款：拦截模式下保持不可进入，由用户重启后重试。
            if (onAgreed != null) {
                runCatching { activity.finish() }
            }
        }
    }

    private fun roundedBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(22, 31, 42)) // PANEL
        setCornerRadius(24f)
    }

    private fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)
}
