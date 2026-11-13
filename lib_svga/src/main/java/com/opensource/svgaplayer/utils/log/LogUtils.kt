package com.opensource.svgaplayer.utils.log

/**
 * 日志输出
 */
internal object LogUtils {
    private const val TAG = "SVGALog"

    fun verbose(tag: String = TAG, msg: String) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.v(tag, msg)
    }

    fun info(tag: String = TAG, msg: String) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.i(tag, msg)
    }

    fun debug(tag: String = TAG, msg: String) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.d(tag, msg)
    }

    fun warn(tag: String = TAG, msg: String) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.w(tag, msg)
    }

    fun error(tag: String = TAG, msg: String) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.e(tag, msg)
    }

    fun error(tag: String, error: Throwable) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.e(tag, error.message.toString(), error)
    }

    fun error(tag: String = TAG, msg: String, error: Throwable) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.e(tag, msg, error)
    }
}