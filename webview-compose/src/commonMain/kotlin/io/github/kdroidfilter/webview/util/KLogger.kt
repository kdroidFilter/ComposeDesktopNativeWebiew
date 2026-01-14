package io.github.kdroidfilter.webview.util

/**
 * Lightweight logger used by the API layer.
 *
 * Keep logging simple and dependency-free across platforms.
 */
interface KLogger {
    fun setMinSeverity(severity: KLogSeverity)

    fun d(tag: String? = null, msg: () -> String) = log(KLogSeverity.Debug, tag, null, msg)

    fun i(tag: String? = null, msg: () -> String) = log(KLogSeverity.Info, tag, null, msg)

    fun w(tag: String? = null, msg: () -> String) = log(KLogSeverity.Warn, tag, null, msg)

    fun e(t: Throwable? = null, tag: String? = null, msg: () -> String) = log(KLogSeverity.Error, tag, t, msg)

    fun log(severity: KLogSeverity, tag: String?, t: Throwable?, msg: () -> String)


    companion object : KLogger {
        private val loggers = mutableListOf<KLogger>(DefaultKLogger)

        fun addLogger(logger: KLogger) {
            loggers.add(logger)
        }

        fun removeLogger(logger: KLogger) {
            loggers.remove(logger)
        }

        override fun setMinSeverity(severity: KLogSeverity) {
            for (i in loggers) {
                i.setMinSeverity(severity)
            }
        }

        override fun log(
            severity: KLogSeverity,
            tag: String?,
            t: Throwable?,
            msg: () -> String
        ) = loggers.forEach {
            it.log(severity, tag, t, msg)
        }

    }
}

internal object DefaultKLogger : KLogger {
    private var minSeverity: KLogSeverity = KLogSeverity.None

    override fun setMinSeverity(severity: KLogSeverity) {
        minSeverity = severity
    }

    override fun log(severity: KLogSeverity, tag: String?, t: Throwable?, msg: () -> String) {
        if (severity.ordinal < minSeverity.ordinal) return
        val prefix = buildString {
            append("[ComposeWebView]")
            if (!tag.isNullOrBlank()) append("[$tag]")
            append(" ")
        }
        println(prefix + msg())
        if (t != null) println(t.toString())
    }
}

enum class KLogSeverity {
    Verbose,
    Debug,
    Info,
    Warn,
    Error,
    Assert,
    None,
}
