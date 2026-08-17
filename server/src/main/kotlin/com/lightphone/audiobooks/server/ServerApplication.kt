package com.lightphone.audiobooks.server

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.media.AudioManager
import android.view.KeyEvent
import com.lightphone.audiobooks.server.library.AudiobookProgressStore
import com.lightphone.audiobooks.server.library.LocalBookRepository
import com.thelightphone.sdk.server.ClientCertType
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.DefaultLightSdkServerSettings
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
 * The Audiobooks companion: hosts the SDK's [LightSdkService] (so the tool can
 * bind to it), the /sdcard/Audiobooks scan + progress store, and the media
 * provider that serves the library files to the tool's detached player.
 */
class ServerApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AudiobookProgressStore.init(this)
        LocalBookRepository.init(this)
        PlaybackSettingsStore.init(this)

        with(LightSdkServer) {
            defaultClientFilterLevel = ClientFilterLevel.AllowLightSignedApks
            provideSdkSettings = { DefaultLightSdkServerSettings(it) }
            checkCert = { callingPackage -> checkLightSdkCert(callingPackage) }
            customServiceMethodResolver = { callingId, methodId, payload ->
                MediaServiceMethods.dispatch(methodId, payload)
            }
            // The SDK routes the LP3's hardware keys (incl. the volume rocker)
            // to the server as DeviceKeyEvents — Light's design: the server
            // decides what a device button does. Without a handler the rocker
            // is swallowed and does nothing. Adjust the media stream so it
            // controls playback volume (LightOS's own RN app does the same
            // via LightOSSoundModule; a third-party tool's server must do it).
            onDeviceKeyEvent = { _, event ->
                // One step per press: key auto-repeat (holding the rocker)
                // delivers further ACTION_DOWN events with repeatCount > 0.
                if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount ?: 0) == 0) {
                    val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> audio.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0,
                        )
                        KeyEvent.KEYCODE_VOLUME_DOWN -> audio.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0,
                        )
                    }
                }
            }
        }

        // First launch: scan the Audiobooks folder so the library is ready.
        applicationScope.launch { LocalBookRepository.scan() }
    }

    private fun Context.checkLightSdkCert(callingPackage: String): ClientCertType {
        val info = try {
            packageManager.getPackageInfo(callingPackage, PackageManager.GET_SIGNING_CERTIFICATES)
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
}
