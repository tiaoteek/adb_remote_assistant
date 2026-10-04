package com.hermes.adbremote.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuShell {

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    suspend fun exec(cmd: String): ShellResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext ShellResult(
                exitCode = -1,
                output = "",
                error = "Shizuku 未运行或未被授权，请在 Shizuku App 中启动并允许授权"
            )
        }

        try {
            // 反射调用 Shizuku 内部进程创建方法以兼容所有版本的 Shizuku (UID=2000 shell)
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process

            val outputText = StringBuilder()
            val errorText = StringBuilder()

            val stdoutThread = Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        outputText.append(line).append("\n")
                    }
                }
            }

            val stderrThread = Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        errorText.append(line).append("\n")
                    }
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val exitCode = process.waitFor()
            stdoutThread.join()
            stderrThread.join()

            ShellResult(
                exitCode = exitCode,
                output = outputText.toString().trim(),
                error = errorText.toString().trim()
            )
        } catch (e: Throwable) {
            ShellResult(
                exitCode = -1,
                output = "",
                error = e.localizedMessage ?: "执行异常"
            )
        }
    }
}

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val error: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}
