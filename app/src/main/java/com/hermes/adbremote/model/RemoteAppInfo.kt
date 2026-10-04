package com.hermes.adbremote.model

data class RemoteAppInfo(
    val packageName: String,
    val isSystemApp: Boolean = false,
    val isRunning: Boolean = false
)
