package com.hermes.adbremote.adb

import android.content.Context
import android.net.Uri
import com.hermes.adbremote.model.RemoteAppInfo
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class RemoteAdbManager {

    private var currentDadb: Dadb? = null
    var currentTarget: String = "" // "ip:port"
    val isConnected: Boolean get() = currentDadb != null

    private fun getOrCreateKeyPair(context: Context): AdbKeyPair {
        val privFile = File(context.filesDir, "adbkey")
        val pubFile = File(context.filesDir, "adbkey.pub")
        if (!privFile.exists() || !pubFile.exists()) {
            AdbKeyPair.generate(privFile, pubFile)
        }
        return AdbKeyPair.read(privFile, pubFile)
    }

    suspend fun connect(context: Context, ip: String, port: Int = 5555): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val keyPair = getOrCreateKeyPair(context)
            val dadb = Dadb.create(ip, port, keyPair)
            
            // 简单验证连通性
            val testResp = dadb.shell("echo ping")
            if (testResp.output.contains("ping")) {
                currentDadb = dadb
                currentTarget = "$ip:$port"
                Pair(true, "连接成功: $currentTarget")
            } else {
                dadb.close()
                Pair(false, "连接响应异常: ${testResp.allOutput}")
            }
        } catch (e: Exception) {
            currentDadb = null
            currentTarget = ""
            Pair(false, "连接失败: ${e.localizedMessage ?: "连接超时，请确认目标设备已开启5555端口且局域网互通"}")
        }
    }

    suspend fun disconnect(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            currentDadb?.close()
        } catch (e: Exception) {}
        currentDadb = null
        val prev = currentTarget
        currentTarget = ""
        Pair(true, if (prev.isNotEmpty()) "已断开与 $prev 的连接" else "未连接")
    }

    suspend fun listPackages(includeSystem: Boolean = false): List<RemoteAppInfo> = withContext(Dispatchers.IO) {
        val dadb = currentDadb ?: return@withContext emptyList()
        try {
            val filter = if (includeSystem) "" else "-3"
            val res = dadb.shell("pm list packages $filter")
            
            res.output.split("\n")
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .map { pkg ->
                    RemoteAppInfo(
                        packageName = pkg,
                        isSystemApp = includeSystem
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun stopApp(packageName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val dadb = currentDadb ?: return@withContext Pair(false, "设备未连接")
        try {
            val res = dadb.shell("am force-stop $packageName")
            if (res.exitCode == 0) {
                Pair(true, "已成功停止: $packageName")
            } else {
                Pair(false, "停止失败: ${res.allOutput}")
            }
        } catch (e: Exception) {
            Pair(false, "执行异常: ${e.localizedMessage}")
        }
    }

    suspend fun installApk(context: Context, apkUri: Uri, onProgress: (String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val dadb = currentDadb ?: return@withContext Pair(false, "设备未连接")

        try {
            onProgress("正在从本地读取安装包...")
            val cacheFile = File(context.cacheDir, "remote_install_temp.apk")
            if (cacheFile.exists()) cacheFile.delete()

            context.contentResolver.openInputStream(apkUri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Pair(false, "无法读取所选 APK 文件")

            onProgress("正在推送到远程设备并安装...")
            
            // 使用 Dadb 内置的 install 方法（支持带参数 "-r"）
            dadb.install(cacheFile, "-r")
            
            // 清理本地缓存
            cacheFile.delete()

            Pair(true, "安装成功！")
        } catch (e: Exception) {
            Pair(false, "安装失败: ${e.localizedMessage}")
        }
    }
}
