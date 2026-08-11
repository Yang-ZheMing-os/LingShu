package com.lingshu.core.common.log

import android.util.Log

object LingShuLog {
    private const val TAG_PREFIX = "LingShu"

    fun d(module: String, message: String) {
        Log.d("$TAG_PREFIX-$module", message)
    }

    fun i(module: String, message: String) {
        Log.i("$TAG_PREFIX-$module", message)
    }

    fun w(module: String, message: String, throwable: Throwable? = null) {
        Log.w("$TAG_PREFIX-$module", message, throwable)
    }

    fun e(module: String, message: String, throwable: Throwable? = null) {
        Log.e("$TAG_PREFIX-$module", message, throwable)
    }

    fun v(module: String, message: String) {
        Log.v("$TAG_PREFIX-$module", message)
    }
}
