package cn.leo.base.log

/**
 * @Author     :Leo
 * Date        :2026/11/6
 * Description : base 模块可插拔日志, 供 lib_base 内部使用
 */
object BaseLog {

    var isDebug = false

    var log: IBaseLog? = null

    fun i(tag: String, msg: String) {
        log?.i(tag, msg)
    }

    fun d(tag: String, msg: String) {
        if (isDebug) {
            log?.d(tag, msg)
        }
    }

    fun e(tag: String, msg: String) {
        log?.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        log?.e(tag, msg, tr)
    }
}


interface IBaseLog {
    fun i(tag: String, msg: String) {}
    fun d(tag: String, msg: String) {}
    fun e(tag: String, msg: String) {}
    fun e(tag: String, msg: String, tr: Throwable) {}
}
