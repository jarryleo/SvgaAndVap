package cn.leo.base.log

import android.util.Log

/**
 * @Author     :Leo
 * Date        :2026/11/6
 * Description : base 模块可插拔日志, 供 lib_base 内部使用
 */
object BaseLog : ILogger {

    var log: ILogger? = DefaultBaseLog

    override fun v(tag: String, msg: String) {
        log?.v(tag, msg)
    }

    override fun i(tag: String, msg: String) {
        log?.i(tag, msg)
    }

    override fun d(tag: String, msg: String) {
        log?.d(tag, msg)
    }

    override fun w(tag: String, msg: String) {
        log?.w(tag, msg)
    }

    override fun e(tag: String, msg: String) {
        log?.e(tag, msg)
    }

    override fun e(tag: String, msg: String, tr: Throwable) {
        log?.e(tag, msg, tr)
    }
}

internal object DefaultBaseLog : ILogger {
    override fun v(tag: String, msg: String) {
        Log.v(tag, msg)
    }

    override fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    override fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    override fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    override fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    override fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
    }
}


interface ILogger {
    fun v(tag: String, msg: String) {}
    fun i(tag: String, msg: String) {}
    fun d(tag: String, msg: String) {}
    fun w(tag: String, msg: String) {}
    fun e(tag: String, msg: String) {}
    fun e(tag: String, msg: String, tr: Throwable) {}
}
