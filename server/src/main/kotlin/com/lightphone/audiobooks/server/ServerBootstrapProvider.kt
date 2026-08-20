package com.lightphone.audiobooks.server

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import com.lightphone.audiobooks.server.library.AudiobookProgressStore
import com.lightphone.audiobooks.server.library.LocalBookRepository
import com.thelightphone.sdk.server.ClientCertType
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.LightSdkServer
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// SHA-256 fingerprint of sdk/keys/lightsdk-dev.jks (alias: lightsdk-dev). Any
// APK signed with the workspace dev key is treated as Light-SDK-signed.
private const val LIGHTSDK_DEV_CERT_SHA256 =
    "B9C33E29B0CCAD2BFF11ACAB55F65A3C517EF4BC92CD9C77785366FA353D5F28"

/**
 * Single-module build (2026-08-18): the former companion's `ServerApplication`
 * wiring runs here, inside the merged tool APK. A ContentProvider is the only
 * app-start hook with a real [Context] that is not part of the tool-plugin
 * scanned module — it wires the SDK server (settings, cert check, media
 * methods, device keys), initializes the library stores, and kicks off the
 * first scan. Providers run after `Application.onCreate` but before the tool's
 * first binder call, so `LightSdkApplication`'s bind is unaffected.
 */
class ServerBootstrapProvider : ContentProvider() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(): Boolean {
        val context = context?.applicationContext ?: return false
        AudiobookProgressStore.init(context)
        LocalBookRepository.init(context)
        PlaybackSettingsStore.init(context)

        with(LightSdkServer) {
            defaultClientFilterLevel = ClientFilterLevel.AllowLightSignedApks
            provideSdkSettings = { RelaySdkServerSettings(it) }
            checkCert = { callingPackage -> checkLightSdkCert(context, callingPackage) }
            customServiceMethodResolver = { _, methodId, payload ->
                MediaServiceMethods.dispatch(methodId, payload)
            }
            // The tool launches this via the SDK's permission flow when the
            // library scan reports PermissionRequired (empty library).
            permissionActivity = MediaPermissionActivity::class.java
            // The SDK routes the LP3's hardware keys to the server as
            // DeviceKeyEvents. The volume rocker controls the media stream
            // here (one step per press — Light's design; the native LightOS
            // volume panel is ringer-only for third-party tools, so volume
            // keys must NOT be relayed or playback volume becomes
            // uncontrollable). Everything else — wheel, camera/focus, and the
            // volume KEY_UP events — is forwarded to LightOS (PlatformRelay),
            // which re-injects it into its own MainActivity: brightness wheel,
            // camera/flashlight, and the in-app volume panel replica
            // (VolumePanelOverlay) handle the rest (PLATFORM-RELAY.md).
            onDeviceKeyEvent = { _, event ->
                val volumeDown = event.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                        event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                if (volumeDown) {
                    if ((event.repeatCount ?: 0) == 0) { // one step per press; drop auto-repeat
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_VOLUME_UP -> audio.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0,
                            )
                            KeyEvent.KEYCODE_VOLUME_DOWN -> audio.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0,
                            )
                        }
                    }
                } else {
                    PlatformRelay.sendDeviceKeyEvent(event)
                }
            }
        }

        // One connection to the already-running platform SDK server.
        PlatformRelay.bind(context)

        // First launch: scan the Audiobooks folder so the library is ready.
        applicationScope.launch { LocalBookRepository.scan() }
        return true
    }

    private fun checkLightSdkCert(context: Context, callingPackage: String): ClientCertType {
        val info = try {
            context.packageManager.getPackageInfo(
                callingPackage,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            return ClientCertType.Unknown
        }
        val signingInfo = info.signingInfo ?: return ClientCertType.Unknown
        val signers: Array<Signature> = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        val md = MessageDigest.getInstance("SHA-256")
        val matches = signers.any { sig ->
            md.digest(sig.toByteArray()).toHexString()
                .equals(LIGHTSDK_DEV_CERT_SHA256, ignoreCase = true)
        }
        return if (matches) ClientCertType.LightSdkSignedUnverified else ClientCertType.Unknown
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }

    // The provider exists for its onCreate only; no content is served.
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
