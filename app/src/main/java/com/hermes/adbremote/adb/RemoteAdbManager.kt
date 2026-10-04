package com.hermes.adbremote.adb

import android.content.Context
import android.net.Uri
import com.hermes.adbremote.model.RemoteAppInfo
import com.hermes.adbremote.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class RemoteAdbManager {

    var currentTarget: String = "" // "ip:port"
    var isConnected: Boolean = false

    private fun targetCmd(cmd: String): String {
        return if (currentTarget.isNotEmpty()) {
            "adb -s $currentTarget $cmd"
        } else {
            "adb $cmd"
        }
    }

    suspend fun connect(ip: String, port: Int = 5555): Pair<Boolean, String> {
        val target = "$ip:$port"
        val res = ShizukuShell.exec("adb connect $target")
        val out = (res.output + " " + res.error).trim()
        
        return if (out.contains("connected to") && !out.contains("failed") && !out.contains("unable")) {
            currentTarget = target
            isConnected = true
            Pair(true, "连接成功: $target")
        } else if (out.contains("already connected")) {
            currentTarget = target
            isConnected = true
            Pair(true, "已连接到: $target")
        } else {
            isConnected = false
            Pair(false, if (out.isEmpty()) "连接超时或目标设备未开启网络调试" else out)
        }
    }

    suspend fun disconnect(): Pair<Boolean, String> {
        if (currentTarget.isEmpty()) return Pair(true, "未连接")
        val res = ShizukuShell.exec("adb disconnect $currentTarget")
        isConnected = false
        val prev = currentTarget
        currentTarget = ""
        return Pair(true, "已断开与 $prev 的连接")
    }

    suspend fun listPackages(includeSystem: Boolean = false): List<RemoteAppInfo> = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext emptyList()
        val filter = if (includeSystem) "" else "-3"
        val res = ShizukuShell.exec(targetCmd("shell pm list packages $filter"))
        if (!res.isSuccess) return@withContext emptyList()

        res.output.split("\n")
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .map { pkg ->
                RemoteAppInfo(
                    packageName = pkg,
                    isSystemApp = !includeSystem // 简单归类
                )
            }
    }

    suspend fun stopApp(packageName: String): Pair<Boolean, String> {
        if (!isConnected) return Pair(false, "设备未连接")
        val res = ShizukuShell.exec(targetCmd("shell am force-stop $packageName"))
        return if (res.isSuccess) {
            Pair(true, "已成功停止: $packageName")
        } else {
            Pair(false, "停止失败: ${res.error.ifEmpty { res.output }}")
        }
    }

    suspend fun installApk(context: Context, apkUri: Uri, onProgress: (String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext Pair(false, "设备未连接")

        try {
            onProgress("正在从本地读取安装包...")
            val cacheFile = File(context.cacheDir, "remote_install_temp.apk")
            if (cacheFile.exists()) cacheFile.delete()

            context.contentResolver.openInputStream(apkUri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Pair(false, "无法读取所选 APK 文件")

            val localPath = cacheFile.absolutePath
            onProgress("正在推送到远程设备并安装...")

            // 使用 adb install -r 推送安装
            val installCmd = targetCmd("install -r $localPath")
            val res = ShizukuShell.exec(installCmd)
            
            // 清理缓存
            cacheFile.delete()

            val combined = (res.output + " " + res.error).trim()
            if (combined.contains("Success", ignoreCase = true)) {
                Pair(true, "安装成功！")
            } else {
                Pair(false, "安装失败: $combined")
            }
        } catch (e: Exception) {
            Pair(false, "安装异常: ${e.localizedMessage}")
        }
    }
}
