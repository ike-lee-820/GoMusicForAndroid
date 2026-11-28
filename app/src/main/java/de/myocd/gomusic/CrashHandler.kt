package de.myocd.gomusic

import android.content.Context

/**
 * 26.11.87：日志输出已移除，崩溃处理器不再写文件。
 * 仍保留默认处理器委托，保证系统原本的崩溃行为不变。
 */
object CrashHandler {

    fun install(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        // 不再安装任何自定义 handler：没有日志可写，也不需要改动系统默认行为
    }
}
