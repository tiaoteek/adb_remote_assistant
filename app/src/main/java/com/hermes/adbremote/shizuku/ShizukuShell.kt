package com.hermes.adbremote.shizuku

import android.content.pm.PackageManager
import dev.rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            // 使用 Shizuku 原生反射创建带特权的 Process (UID=2000 shell)
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)

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
