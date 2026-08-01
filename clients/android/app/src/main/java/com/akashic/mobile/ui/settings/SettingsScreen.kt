package com.akashic.mobile.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.akashic.mobile.BuildConfig
import com.akashic.mobile.R
import com.akashic.mobile.update.AppRelease
import com.akashic.mobile.update.AppUpdateRepository
import com.akashic.mobile.update.UpdateCheckResult
import java.io.IOException
import kotlinx.coroutines.launch

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object NoRelease : UpdateUiState
    data class UpToDate(val version: String) : UpdateUiState
    data class Available(val release: AppRelease) : UpdateUiState
    data class Downloading(val release: AppRelease, val progress: Int) : UpdateUiState
    data object PermissionRequired : UpdateUiState
    data object InstallerOpened : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

/** 展示应用身份并完成用户发起的检查、下载与系统安装流程。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repository = remember { AppUpdateRepository() }
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var installerResumeInFlight by remember { mutableStateOf(false) }

    fun openInstallerIfReady() {
        if (installerResumeInFlight) return
        installerResumeInFlight = true
        scope.launch {
            try {
                val apk = repository.resumableUpdate(context) ?: return@launch
                if (!repository.canRequestPackageInstalls(context)) {
                    updateState = UpdateUiState.PermissionRequired
                    return@launch
                }
                repository.launchInstaller(context, apk)
                updateState = UpdateUiState.InstallerOpened
            } catch (error: ActivityNotFoundException) {
                updateState = UpdateUiState.Error("系统安装器不可用")
            } catch (error: SecurityException) {
                updateState = UpdateUiState.Error("系统拒绝打开安装器：${error.message.orEmpty()}")
            } catch (error: IOException) {
                updateState = UpdateUiState.Error(error.message ?: "无法恢复待安装更新")
            } finally {
                installerResumeInFlight = false
            }
        }
    }

    // 1. 首次进入或从“安装未知应用”设置返回时恢复已验证的 APK。
    LaunchedEffect(Unit) { openInstallerIfReady() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) openInstallerIfReady()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 2. 原生设置页保持在 WebView 之上，并接管系统返回动作。
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回对话")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_art),
                contentDescription = "Akashic Mobile 图标",
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(28.dp)),
            )
            Text(
                text = "Akashic Mobile",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(32.dp))
            UpdateCard(
                state = updateState,
                onCheck = {
                    updateState = UpdateUiState.Checking
                    scope.launch {
                        updateState = try {
                            when (val result = repository.checkForUpdate()) {
                                UpdateCheckResult.NoRelease -> UpdateUiState.NoRelease
                                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.latestVersion)
                                is UpdateCheckResult.Available -> UpdateUiState.Available(result.release)
                            }
                        } catch (error: IOException) {
                            UpdateUiState.Error(error.message ?: "检查更新失败")
                        }
                    }
                },
                onDownload = { release ->
                    updateState = UpdateUiState.Downloading(release, 0)
                    scope.launch {
                        try {
                            repository.download(context, release) { progress ->
                                updateState = UpdateUiState.Downloading(release, progress)
                            }
                            if (repository.canRequestPackageInstalls(context)) {
                                openInstallerIfReady()
                            } else {
                                updateState = UpdateUiState.PermissionRequired
                            }
                        } catch (error: IOException) {
                            updateState = UpdateUiState.Error(error.message ?: "下载更新失败")
                        }
                    }
                },
                onOpenPermissionSettings = {
                    updateState = try {
                        repository.openInstallPermissionSettings(context)
                        UpdateUiState.PermissionRequired
                    } catch (error: ActivityNotFoundException) {
                        UpdateUiState.Error("系统安装权限页面不可用")
                    } catch (error: SecurityException) {
                        UpdateUiState.Error("系统拒绝打开安装权限页面")
                    }
                },
                onOpenReleases = {
                    updateState = try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateRepository.RELEASES_URL)),
                        )
                        updateState
                    } catch (error: ActivityNotFoundException) {
                        UpdateUiState.Error("没有可打开 GitHub Releases 的应用")
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 使用 Material 3 tonal card 表达更新状态和唯一主动作。 */
@Composable
private fun UpdateCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: (AppRelease) -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onOpenReleases: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column {
                    Text("应用更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        updateSummary(state),
                        color = if (state is UpdateUiState.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (state is UpdateUiState.Downloading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                )
            }
            if (state is UpdateUiState.Available && state.release.notes.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 18.dp))
                Text(
                    state.release.notes,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    UpdateUiState.Checking -> {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在检查", style = MaterialTheme.typography.labelLarge)
                    }
                    is UpdateUiState.Downloading -> Text(
                        "${state.progress}%",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    is UpdateUiState.Available -> Button(onClick = { onDownload(state.release) }) {
                        Text("下载并安装")
                    }
                    UpdateUiState.PermissionRequired -> Button(onClick = onOpenPermissionSettings) {
                        Text("允许安装更新")
                    }
                    else -> FilledTonalButton(onClick = onCheck) { Text("检查更新") }
                }
            }
            if (state is UpdateUiState.NoRelease || state is UpdateUiState.Error) {
                TextButton(
                    onClick = onOpenReleases,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("查看 GitHub Releases") }
            }
        }
    }
}

private fun updateSummary(state: UpdateUiState): String = when (state) {
    UpdateUiState.Idle -> "从公开 GitHub Release 获取已签名 APK"
    UpdateUiState.Checking -> "正在读取最新稳定版本"
    UpdateUiState.NoRelease -> "公开仓库暂未发布可安装版本"
    is UpdateUiState.UpToDate -> "已是最新版本 ${state.version}"
    is UpdateUiState.Available -> "发现 ${state.release.tagName} · ${formatBytes(state.release.apkSizeBytes)}"
    is UpdateUiState.Downloading -> "正在下载 ${state.release.tagName}"
    UpdateUiState.PermissionRequired -> "更新已验证，请允许 Akashic Mobile 安装未知应用"
    UpdateUiState.InstallerOpened -> "已打开系统安装器，请确认更新"
    is UpdateUiState.Error -> state.message
}

private fun formatBytes(bytes: Long): String = String.format("%.1f MB", bytes / 1024.0 / 1024.0)
