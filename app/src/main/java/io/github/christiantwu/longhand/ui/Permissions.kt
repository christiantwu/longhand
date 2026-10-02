package io.github.christiantwu.longhand.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Asks for [permissions] when tapped. After two refusals Android stops showing the dialog and
 * just answers "denied"; from then on this opens Longhand's page in system settings, where the
 * permission can still be granted. Below Android 13 notifications aren't a runtime permission,
 * so that request goes straight to the notification settings. [onResult] runs when the request
 * finishes.
 */
@Composable
fun rememberPermissionRequest(vararg permissions: String, onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Android offers an explanation only after the user has refused once (a dismissed dialog
        // doesn't count), so seeing one, here or when the request starts, marks a real refusal.
        context.findActivity()?.let { activity ->
            Permissions.markRefused(context, result.keys.filter { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
        }
        currentOnResult()
    }
    return {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        val notificationsOnly = missing == listOf(Manifest.permission.POST_NOTIFICATIONS)
        val activity = context.findActivity()
        when {
            missing.isEmpty() -> currentOnResult()
            notificationsOnly && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> Permissions.openSettings(context, notifications = true)
            // Refused before, and Android no longer offers an explanation: it won't show the dialog again.
            activity != null && missing.all { Permissions.refused(context, it) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } ->
                Permissions.openSettings(context, notificationsOnly)
            else -> {
                // Refused earlier, perhaps in system settings: Android offers an explanation now.
                activity?.let { a -> Permissions.markRefused(context, missing.filter { ActivityCompat.shouldShowRequestPermissionRationale(a, it) }) }
                launcher.launch(missing.toTypedArray())
            }
        }
    }
}

object Permissions {
    private fun prefs(context: Context) = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)

    /** Whether the user has refused [permission] in Android's dialog at least once. */
    fun refused(context: Context, permission: String) = prefs(context).getBoolean("refused:$permission", false)

    fun markRefused(context: Context, permissions: List<String>) {
        if (permissions.isNotEmpty()) prefs(context).edit { permissions.forEach { putBoolean("refused:$it", true) } }
    }

    fun openSettings(context: Context, notifications: Boolean) {
        val intent = if (notifications) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
        context.startActivity(intent)
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
