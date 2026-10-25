package com.fenghuolun.ktv

import android.content.Context

/** MDKTV integration: preserve upstream logging API, send records to local KtvLog only. */
object MiliLog {
    fun init(context: Context) = KtvLog.init(context)
    @Suppress("UNUSED_PARAMETER")
    fun updateDeviceContext(ip: String? = null, deviceId: String? = null) = Unit
    fun info(msg: String) = KtvLog.i("Media", msg)
    fun notice(msg: String) = KtvLog.i("Media", msg)
    fun warn(msg: String) = KtvLog.w("Media", msg)
    fun error(msg: String) = KtvLog.e("Media", msg)
}
