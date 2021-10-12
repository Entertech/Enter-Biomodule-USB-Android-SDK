package cn.entertech.sdk

import android.util.Log

object LogHelper {
    var isDebug = false
        set(value) {
            field = value
        }

    var tag: String = ""
        set(value) {
            field = value
        }

    fun v(msg: String) {
        if (isDebug) {
            Log.v(tag, msg)
        }
    }

    fun d(msg: String) {
        if (isDebug) {
            Log.d(tag, msg)
        }
    }

    fun i(msg: String) {
        if (isDebug) {
            Log.i(tag, msg)
        }
    }

    fun w(msg: String) {
        if (isDebug) {
            Log.w(tag, msg)
        }
    }

    fun e(msg: String) {
        if (isDebug) {
            Log.e(tag, msg)
        }
    }
}