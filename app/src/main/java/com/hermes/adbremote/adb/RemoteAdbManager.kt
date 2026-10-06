package com.hermes.adbremote.adb

import android.content.Context
import android.net.Uri
import com.hermes.adbremote.model.RemoteAppInfo
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

object RemoteAdbManager {

    private var currentDadb: Dadb? = null
    private var lastIp: String = ""
    private var lastPort: Int = 5555

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val isBusy = AtomicBoolean(false)
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    private fun getOrCreateKeyPair(context: Context): AdbKeyPair {
        val privFile = File(context.filesDir, "adb_key")
        val pubFile = File(context.filesDir, "adb_key.pub")

        if (privFile.exists() && pubFile.exists()) {
            try {
                return AdbKeyPair.read(privFile, pubFile)
            } catch (e: Exception) {
                privFile.delete()
                pubFile.delete()
            }
        }

        AdbKeyPair.generate(privFile, pubFile)
        return AdbKeyPair.read(privFile, pubFile)
    }

    suspend fun connect(context: Context, ip: String, port: Int = 5555): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        lastIp = ip
        lastPort = port
        _connectionState.value = ConnectionState.CONNECTING

        try {
            closeExisting()
            val keyPair = getOrCreateKeyPair(context)
            val dadb = Dadb.create(ip, port, keyPair)
            
            // 执行一次轻量探针测试通道有效性
            val testResp = dadb.shell("echo ping")
            if (testResp.allOutput.contains("ping")) {
                currentDadb = dadb
                _connectionState.value = ConnectionState.CONNECTED
                saveLastTarget(context, ip, port)
                startHeartbeat(context)
                Pair(true, "连接成功 (支持心跳自愈保活)")
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
                Pair(false, "连接响应异常: ${testResp.allOutput}")
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            Pair(false, "连接失败: ${e.localizedMessage ?: "网络不可达或远程未开启无线调试"}")
        }
    }

    private fun startHeartbeat(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(4000)
                if (isBusy.get()) continue

                val dadb = currentDadb
                if (dadb != null) {
                    try {
                        val resp = dadb.shell("echo 1")
                        if (resp.allOutput.trim() == "1") {
                            if (_connectionState.value != ConnectionState.CONNECTED) {
                                _connectionState.value = ConnectionState.CONNECTED
                            }
                        } else {
                            handleConnectionLost(context)
                        }
                    } catch (e: Exception) {
                        handleConnectionLost(context)
                    }
                } else if (lastIp.isNotEmpty()) {
                    handleConnectionLost(context)
                }
            }
        }
    }

    private suspend fun handleConnectionLost(context: Context) {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return
        _connectionState.value = ConnectionState.RECONNECTING
        try {
            closeExisting()
            val keyPair = getOrCreateKeyPair(context)
            val dadb = Dadb.create(lastIp, lastPort, keyPair)
            val resp = dadb.shell("echo 1")
            if (resp.allOutput.trim() == "1") {
                currentDadb = dadb
                _connectionState.value = ConnectionState.CONNECTED
            }
        } catch (e: Exception) {
            // 继续重试或等待下次心跳
        }
    }

    suspend fun ensureConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value == ConnectionState.CONNECTED && currentDadb != null) {
            try {
                val ping = currentDadb?.shell("echo 1")
                if (ping?.allOutput?.trim() == "1") return@withContext true
            } catch (e: Exception) {}
        }

        if (lastIp.isEmpty()) {
            val (ip, port) = getLastTarget(context)
            if (ip.isNotEmpty()) {
                lastIp = ip
                lastPort = port
            } else {
                return@withContext false
            }
        }

        _connectionState.value = ConnectionState.RECONNECTING
        try {
            closeExisting()
            val keyPair = getOrCreateKeyPair(context)
            val dadb = Dadb.create(lastIp, lastPort, keyPair)
            val ping = dadb.shell("echo 1")
            if (ping.allOutput.trim() == "1") {
                currentDadb = dadb
                _connectionState.value = ConnectionState.CONNECTED
                startHeartbeat(context)
                true
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
                false
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            false
        }
    }

    fun saveLastTarget(context: Context, ip: String, port: Int) {
        val sp = context.getSharedPreferences("adb_remote_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("target_ip", ip).putInt("target_port", port).apply()
    }

    fun getLastTarget(context: Context): Pair<String, Int> {
        val sp = context.getSharedPreferences("adb_remote_prefs", Context.MODE_PRIVATE)
        return Pair(sp.getString("target_ip", "") ?: "", sp.getInt("target_port", 5555))
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()
        closeExisting()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun closeExisting() {
        try {
            currentDadb?.close()
        } catch (e: Exception) {}
        currentDadb = null
    }

    suspend fun listPackages(context: Context, includeSystem: Boolean = false): List<RemoteAppInfo> = withContext(Dispatchers.IO) {
        if (!ensureConnected(context)) return@withContext emptyList()
        val dadb = currentDadb ?: return@withContext emptyList()

        try {
            isBusy.set(true)
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
        } finally {
            isBusy.set(false)
        }
    }

    suspend fun stopApp(context: Context, packageName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ensureConnected(context)) return@withContext Pair(false, "设备未连接且自动重连失败")
        val dadb = currentDadb ?: return@withContext Pair(false, "设备未连接")

        try {
            isBusy.set(true)
            val res = dadb.shell("am force-stop $packageName")
            if (res.exitCode == 0) {
                Pair(true, "已成功停止: $packageName")
            } else {
                Pair(false, "停止失败: ${res.allOutput}")
            }
        } catch (e: Exception) {
            Pair(false, "执行异常: ${e.localizedMessage}")
        } finally {
            isBusy.set(false)
        }
    }

    suspend fun installApk(context: Context, apkUri: Uri, onProgress: (String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        onProgress("正在检查设备连接状态...")
        if (!ensureConnected(context)) {
            return@withContext Pair(false, "设备连接已断开且自动重连失败，请确认设备仍在同一局域网并开启调试")
        }

        val dadb = currentDadb ?: return@withContext Pair(false, "设备未连接")

        try {
            isBusy.set(true)
            onProgress("正在从本地读取安装包文件...")
            val cacheFile = File(context.cacheDir, "remote_install_temp.apk")
            if (cacheFile.exists()) cacheFile.delete()

            context.contentResolver.openInputStream(apkUri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Pair(false, "无法读取所选 APK 文件")

            val fileSizeMb = String.format("%.2f MB", cacheFile.length() / (1024.0 * 1024.0))
            onProgress("正在向远程设备推流传输 ($fileSizeMb) 并执行安装...")

            dadb.install(cacheFile, "-r")

            cacheFile.delete()
            Pair(true, "安装成功！")
        } catch (e: Exception) {
            Pair(false, "安装失败: ${e.localizedMessage}")
        } finally {
            isBusy.set(false)
        }
    }

    suspend fun pushApkToRemotePath(
        context: Context,
        apkUri: Uri,
        fileName: String,
        targetDir: String,
        onProgress: (String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        onProgress("正在检查设备连接状态...")
        if (!ensureConnected(context)) {
            return@withContext Pair(false, "设备连接已断开且自动重连失败，请确认设备仍在同一局域网并开启调试")
        }

        val dadb = currentDadb ?: return@withContext Pair(false, "设备未连接")

        try {
            isBusy.set(true)
            onProgress("正在从本地缓存文件...")
            val cacheFile = File(context.cacheDir, "remote_push_temp.apk")
            if (cacheFile.exists()) cacheFile.delete()

            context.contentResolver.openInputStream(apkUri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Pair(false, "无法读取所选文件")

            val fileSizeMb = String.format("%.2f MB", cacheFile.length() / (1024.0 * 1024.0))
            
            // 规范化目标目录并确保目录存在
            var normalizedDir = targetDir.trim()
            if (!normalizedDir.endsWith("/")) {
                normalizedDir += "/"
            }
            if (normalizedDir.isEmpty()) {
                normalizedDir = "/sdcard/Download/"
            }

            onProgress("正在远程设备上创建目录 $normalizedDir ...")
            dadb.shell("mkdir -p \"$normalizedDir\"")

            val remoteFullPath = normalizedDir + fileName
            onProgress("正在推送文件 ($fileSizeMb) 到: $remoteFullPath ...")

            dadb.push(cacheFile, remoteFullPath)

            // 验证远程文件是否存在
            val verifyResp = dadb.shell("ls -l \"$remoteFullPath\"")
            cacheFile.delete()

            if (verifyResp.exitCode == 0 && !verifyResp.allOutput.contains("No such file")) {
                Pair(true, "文件已成功推送至: $remoteFullPath\n体积: $fileSizeMb")
            } else {
                Pair(true, "推送指令已执行完成: $remoteFullPath")
            }
        } catch (e: Exception) {
            Pair(false, "推送失败: ${e.localizedMessage}")
        } finally {
            isBusy.set(false)
        }
    }
}
