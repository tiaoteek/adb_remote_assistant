package com.hermes.adbremote.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import com.hermes.adbremote.adb.RemoteAdbManager
import com.hermes.adbremote.databinding.ActivityMainBinding
import com.hermes.adbremote.shizuku.ShizukuShell
import dev.rikka.shizuku.Shizuku
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adbManager = RemoteAdbManager()
    private lateinit var appAdapter: RemoteAppAdapter

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            updateShizukuStatus(true)
        } else {
            updateShizukuStatus(false)
        }
    }

    private val selectApkLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleInstallApk(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initShizuku()
        initTabs()
        initViews()
    }

    private fun initShizuku() {
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    updateShizukuStatus(true)
                } else {
                    Shizuku.requestPermission(1001)
                }
            } else {
                updateShizukuStatus(false)
            }
        } catch (e: Exception) {
            updateShizukuStatus(false)
        }
    }

    private fun updateShizukuStatus(granted: Boolean) {
        runOnUiThread {
            if (granted) {
                binding.tvShizukuState.text = "✓ Shizuku 已连接且授权就绪 (Android 16 兼容)"
                binding.tvShizukuState.setTextColor(getColor(R.color.success))
            } else {
                binding.tvShizukuState.text = "✗ Shizuku 未授权或未运行，请检查 Shizuku 状态"
                binding.tvShizukuState.setTextColor(getColor(R.color.danger))
            }
        }
    }

    private fun initTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> switchPanel(0)
                    1 -> {
                        switchPanel(1)
                        if (adbManager.isConnected) loadRemoteApps()
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
        // 连接与断开
        binding.btnConnect.setOnClickListener {
            val ip = binding.etIp.text.toString().trim()
            val portStr = binding.etPort.text.toString().trim()
            val port = portStr.toIntOrNull() ?: 5555

            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入目标设备 IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnConnect.isEnabled = false
            binding.tvStatus.text = "正在连接 $ip:$port ..."

            lifecycleScope.launch {
                val (success, msg) = adbManager.connect(ip, port)
                binding.btnConnect.isEnabled = true
                binding.btnDisconnect.isEnabled = success
                binding.tvStatus.text = msg

                if (success) {
                    binding.tvStatus.setTextColor(getColor(R.color.success))
                    Toast.makeText(this@MainActivity, "连接成功", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvStatus.setTextColor(getColor(R.color.danger))
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnDisconnect.setOnClickListener {
            lifecycleScope.launch {
                val (_, msg) = adbManager.disconnect()
                binding.btnDisconnect.isEnabled = false
                binding.tvStatus.text = msg
                binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
                appAdapter.updateData(emptyList())
            }
        }

        // 应用管理
        appAdapter = RemoteAppAdapter(emptyList()) { app ->
            lifecycleScope.launch {
                val (success, msg) = adbManager.stopApp(app.packageName)
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

        // 安装面板
        binding.btnSelectApk.setOnClickListener {
            if (!adbManager.isConnected) {
                Toast.makeText(this, "请先连接远程设备", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/vnd.android.package-archive"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            selectApkLauncher.launch(Intent.createChooser(intent, "选择要推送的 APK"))
        }
    }

    private fun loadRemoteApps() {
        if (!adbManager.isConnected) {
            Toast.makeText(this, "请先在首页连接设备", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在读取远程应用列表...", Toast.LENGTH_SHORT).show()
            val list = adbManager.listPackages(includeSystem = false)
            appAdapter.updateData(list)
            Toast.makeText(this@MainActivity, "已加载 ${list.size} 个应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleInstallApk(uri: Uri) {
        binding.pbInstall.visibility = View.VISIBLE
        binding.btnSelectApk.isEnabled = false

        lifecycleScope.launch {
            val (success, msg) = adbManager.installApk(this@MainActivity, uri) { progress ->
                runOnUiThread {
                    binding.tvInstallProgress.text = progress
                }
            }

            binding.pbInstall.visibility = View.GONE
            binding.btnSelectApk.isEnabled = true
            binding.tvInstallProgress.text = msg

            if (success) {
                binding.tvInstallProgress.setTextColor(getColor(R.color.success))
                Toast.makeText(this@MainActivity, "远程设备安装完成！", Toast.LENGTH_LONG).show()
            } else {
                binding.tvInstallProgress.setTextColor(getColor(R.color.danger))
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) {}
    }
}
