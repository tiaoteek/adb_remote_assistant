package com.hermes.adbremote.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.hermes.adbremote.R
import com.hermes.adbremote.adb.ConnectionState
import com.hermes.adbremote.adb.RemoteAdbManager
import com.hermes.adbremote.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appAdapter: RemoteAppAdapter

    private val selectApkLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleFileAction(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initTabs()
        initViews()
        observeConnectionState()
        restoreLastDeviceAndAutoConnect()
    }

    private fun initTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> switchPanel(0)
                    1 -> {
                        switchPanel(1)
                        loadRemoteApps()
                    }
                    2 -> switchPanel(2)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun switchPanel(index: Int) {
        binding.panelConnect.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.panelApps.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.panelInstall.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    private fun initViews() {
        // 点击顶部全局连接条触发重连
        binding.bannerGlobalStatus.setOnClickListener {
            val ip = binding.etIp.text.toString().trim()
            val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 5555
            if (ip.isNotEmpty()) {
                startConnect(ip, port)
            }
        }

        // 连接与断开
        binding.btnConnect.setOnClickListener {
            val ip = binding.etIp.text.toString().trim()
            val portStr = binding.etPort.text.toString().trim()
            val port = portStr.toIntOrNull() ?: 5555

            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入目标设备 IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startConnect(ip, port)
        }

        binding.btnDisconnect.setOnClickListener {
            lifecycleScope.launch {
                RemoteAdbManager.disconnect()
                Toast.makeText(this@MainActivity, "已断开连接", Toast.LENGTH_SHORT).show()
                appAdapter.updateData(emptyList())
            }
        }

        // 应用管理
        appAdapter = RemoteAppAdapter(emptyList()) { app ->
            lifecycleScope.launch {
                val (_, msg) = RemoteAdbManager.stopApp(this@MainActivity, app.packageName)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = appAdapter

        binding.btnRefreshApps.setOnClickListener {
            loadRemoteApps()
        }

        binding.etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                appAdapter.filter(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 安装/推送面板：模式切换监听
        binding.rgTransferMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbPushToDir) {
                binding.layoutCustomPath.visibility = View.VISIBLE
                binding.btnSelectApk.text = "选择 APK 文件并推送到指定位置"
            } else {
                binding.layoutCustomPath.visibility = View.GONE
                binding.btnSelectApk.text = getString(R.string.select_apk)
            }
        }

        // 快捷路径填充
        binding.tvQuickDownload.setOnClickListener {
            binding.etRemotePath.setText("/sdcard/Download/")
        }
        binding.tvQuickTmp.setOnClickListener {
            binding.etRemotePath.setText("/data/local/tmp/")
        }

        // 选择 APK 按钮
        binding.btnSelectApk.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                val mimeTypes = arrayOf("application/vnd.android.package-archive", "application/octet-stream")
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            selectApkLauncher.launch(Intent.createChooser(intent, "选择 APK 文件"))
        }
    }

    private fun startConnect(ip: String, port: Int) {
        binding.btnConnect.isEnabled = false
        binding.tvStatus.text = "正在连接 $ip:$port ..."
        binding.tvStatus.setTextColor(getColor(R.color.text_secondary))

        lifecycleScope.launch {
            val (success, msg) = RemoteAdbManager.connect(this@MainActivity, ip, port)
            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = success
            binding.tvStatus.text = msg

            if (success) {
                binding.tvStatus.setTextColor(getColor(R.color.success))
                saveLastDevice(ip, port)
                Toast.makeText(this@MainActivity, "连接成功", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvStatus.setTextColor(getColor(R.color.danger))
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 实时监控响应式连接状态（全局多Tab状态同步与心跳指示）
     */
    private fun observeConnectionState() {
        lifecycleScope.launchWhenStarted {
            RemoteAdbManager.connectionState.collectLatest { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        binding.viewStatusDot.setBackgroundResource(R.drawable.indicator_connected)
                        binding.tvGlobalStatus.text = "已连接远程设备 (心跳在线)"
                        binding.tvReconnectHint.visibility = View.GONE
                        binding.btnConnect.isEnabled = false
                        binding.btnDisconnect.isEnabled = true
                        binding.tvStatus.text = "已连接远程设备"
                        binding.tvStatus.setTextColor(getColor(R.color.success))
                    }
                    ConnectionState.RECONNECTING -> {
                        binding.viewStatusDot.setBackgroundResource(R.drawable.indicator_reconnecting)
                        binding.tvGlobalStatus.text = "连接波动: 正在自动重连..."
                        binding.tvReconnectHint.visibility = View.VISIBLE
                        binding.tvReconnectHint.text = "点击立即重试"
                        binding.btnConnect.isEnabled = true
                        binding.btnDisconnect.isEnabled = false
                        binding.tvStatus.text = "正在自动重连..."
                        binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
                    }
                    ConnectionState.CONNECTING -> {
                        binding.viewStatusDot.setBackgroundResource(R.drawable.indicator_reconnecting)
                        binding.tvGlobalStatus.text = "正在握手连接中..."
                        binding.tvReconnectHint.visibility = View.GONE
                        binding.btnConnect.isEnabled = false
                        binding.btnDisconnect.isEnabled = false
                    }
                    ConnectionState.DISCONNECTED -> {
                        binding.viewStatusDot.setBackgroundResource(R.drawable.indicator_disconnected)
                        binding.tvGlobalStatus.text = "未连接远程设备"
                        binding.tvReconnectHint.visibility = View.VISIBLE
                        binding.tvReconnectHint.text = "点此连接"
                        binding.btnConnect.isEnabled = true
                        binding.btnDisconnect.isEnabled = false
                        binding.tvStatus.text = getString(R.string.status_not_connected)
                        binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
                    }
                }
            }
        }
    }

    private fun loadRemoteApps() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在同步远程应用列表...", Toast.LENGTH_SHORT).show()
            val list = RemoteAdbManager.listPackages(this@MainActivity, includeSystem = false)
            appAdapter.updateData(list)
            if (list.isNotEmpty()) {
                Toast.makeText(this@MainActivity, "已加载 ${list.size} 个第三方应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "app_payload.apk"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    val displayName = cursor.getString(nameIndex)
                    if (!displayName.isNullOrBlank()) {
                        name = displayName
                    }
                }
            }
        } catch (e: Exception) {}
        if (!name.endsWith(".apk", ignoreCase = true)) {
            name += ".apk"
        }
        return name
    }

    private fun handleFileAction(uri: Uri) {
        val isPushMode = binding.rbPushToDir.isChecked
        val targetDir = binding.etRemotePath.text.toString().trim().ifEmpty { "/sdcard/Download/" }
        val fileName = getFileNameFromUri(uri)

        binding.pbInstall.visibility = View.VISIBLE
        binding.btnSelectApk.isEnabled = false

        lifecycleScope.launch {
            val (success, msg) = if (isPushMode) {
                RemoteAdbManager.pushApkToRemotePath(this@MainActivity, uri, fileName, targetDir) { progress ->
                    runOnUiThread {
                        binding.tvInstallProgress.text = progress
                    }
                }
            } else {
                RemoteAdbManager.installApk(this@MainActivity, uri) { progress ->
                    runOnUiThread {
                        binding.tvInstallProgress.text = progress
                    }
                }
            }

            binding.pbInstall.visibility = View.GONE
            binding.btnSelectApk.isEnabled = true
            binding.tvInstallProgress.text = msg

            if (success) {
                binding.tvInstallProgress.setTextColor(getColor(R.color.success))
                Toast.makeText(this@MainActivity, if (isPushMode) "文件推送成功！" else "远程设备安装完成！", Toast.LENGTH_LONG).show()
            } else {
                binding.tvInstallProgress.setTextColor(getColor(R.color.danger))
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveLastDevice(ip: String, port: Int) {
        val sp = getSharedPreferences("adb_remote_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("last_ip", ip).putInt("last_port", port).apply()
    }

    private fun restoreLastDeviceAndAutoConnect() {
        val sp = getSharedPreferences("adb_remote_prefs", Context.MODE_PRIVATE)
        val savedIp = sp.getString("last_ip", "") ?: ""
        val savedPort = sp.getInt("last_port", 5555)

        if (savedIp.isNotEmpty()) {
            binding.etIp.setText(savedIp)
            binding.etPort.setText(savedPort.toString())
            startConnect(savedIp, savedPort)
        }
    }
}
