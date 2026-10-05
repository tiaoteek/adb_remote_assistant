package com.hermes.adbremote.adb

import android.content.Context
import android.net.Uri
import com.hermes.adbremote.model.RemoteAppInfo
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    data class Connecting(val target: String) : ConnectionStatus()
    data class Connected(val target: String) : ConnectionStatus()
    data class Reconnecting(val target: String) : ConnectionStatus()
    data class Failed(val message: String) : ConnectionStatus()
}

class RemoteAdbManager {

    private var currentDadb: Dadb? = null
    var currentTarget: String = "" // "ip:port"
    var lastIp: String = ""
    var lastPort: Int = 5555

    private val _connectionState = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    private var heartbeatJob: Job? = null
    private val isBusy = AtomicBoolean(false) // 正在安装或传输时不打断通信

    val isConnected: Boolean get() = _connectionState.value is ConnectionStatus.Connected

    private fun getOrCreateKeyPair(context: Context): AdbKeyPair {
        val privFile = File(context.filesDir, "adbkey")
        val pubFile = File(context.filesDir, "adbkey.pub")
        if (!privFile.exists() || !pubFile.exists()) {
            AdbKeyPair.generate(privFile, pubFile)
        }
        return AdbKeyPair.read(privFile, pubFile)
    }

    /**
     * 主动发起连接
     */
    suspend fun connect(context: Context, ip: String, port: Int = 5555): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        lastIp = ip
        lastPort = port
        val target = "$ip:$port"

        _connectionState.value = ConnectionStatus.Connecting(target)

        try {
            safeCloseDadb()
            val keyPair = getOrCreateKeyPair(context)
            val dadb = Dadb.create(ip, port, keyPair)

            // 握手健康验证
            val testResp = dadb.shell("echo ping")
            if (testResp.output.contains("ping")) {
                currentDadb = dadb
                currentTarget = target
                _connectionState.value = ConnectionStatus.Connected(target)
                startHeartbeat(context)
                Pair(true, "连接成功: $target")
            } else {
                dadb.close()
                currentDadb = null
                _connectionState.value = ConnectionStatus.Failed("响应异常: ${testResp.allOutput}")
                Pair(false, "连接响应异常: ${testResp.allOutput}")
            }
        } catch (e: Exception) {
            currentDadb = null
            currentTarget = ""
            val err = e.localizedMessage ?: "连接超时，请确认目标设备已开启5555端口且处于同一局域网"
            _connectionState.value = ConnectionStatus.Failed(err)
            Pair(false, "连接失败: $err")
        }
    }

    /**
     * 自动确保处于连通状态（静默自愈重连机制）
     */
    suspend fun ensureConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isConnectionHealthy()) {
            return@withContext true
        }

        if (lastIp.isEmpty()) {
            return@withContext false
        }

        // 尝试自动重连自愈
        val target = "$lastIp:$lastPort"
        _connectionState.value = ConnectionStatus.Reconnecting(target)

        try {
            safeCloseDadb()
            val keyPair = getOrCreateKeyPair(context)
            val dadb = Dadb.create(lastIp, lastPort, keyPair)
            val testResp = dadb.shell("echo ping")
            if (testResp.output.contains("ping")) {
                currentDadb = dadb
                currentTarget = target
                _connectionState.value = ConnectionStatus.Connected(target)
                startHeartbeat(context)
                return@withContext true
            } else {
                dadb.close()
                currentDadb = null
                _connectionState.value = ConnectionStatus.Failed("自动重连失败: 响应异常")
                return@withContext false
            }
        } catch (e: Exception) {
            currentDadb = null
            _connectionState.value = ConnectionStatus.Failed("自动重连失败: ${e.localizedMessage}")
            return@withContext false
        }
    }

    /**
     * 快速验证现有链路健康
     */
    private fun isConnectionHealthy(): Boolean {
        val dadb = currentDadb ?: return false
        return try {
            val res = dadb.shell("echo 1")
            res.output.trim() == "1"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 持续后台心跳监测回路（每4秒轻量探测）
     */
    private fun startHeartbeat(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(4000)
                if (isBusy.get()) continue // 正在传输时不打扰

                if (!isConnectionHealthy()) {
                    val target = currentTarget.ifEmpty { "$lastIp:$lastPort" }
                    if (target.isNotEmpty()) {
                        _connectionState.value = ConnectionStatus.Reconnecting(target)
                        // 发现断线后立即自动在后台尝试自愈一次
                        ensureConnected(context)
                    } else {
                        _connectionState.value = ConnectionStatus.Disconnected
                    }
                } else {
                    if (_connectionState.value !is ConnectionStatus.Connected && currentTarget.isNotEmpty()) {
                        _connectionState.value = ConnectionStatus.Connected(currentTarget)
                    }
                }
            }
        }
    }

    suspend fun disconnect(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        safeCloseDadb()
        val prev = currentTarget
        currentTarget = ""
        _connectionState.value = ConnectionStatus.Disconnected
        Pair(true, if (prev.isNotEmpty()) "已断开与 $prev 的连接" else "未连接")
    }

    private fun safeCloseDadb() {
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

            // 使用 Dadb 进行带参数覆盖安装 (-r)
            dadb.install(cacheFile, "-r")

            cacheFile.delete()
            Pair(true, "安装成功！")
        } catch (e: Exception) {
            Pair(false, "安装失败: ${e.localizedMessage}")
        } finally {
            isBusy.set(false)
        }
    }
}
