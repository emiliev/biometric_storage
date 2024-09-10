package design.codeux.biometric_storage

import android.app.Activity
import android.content.Context
import javax.crypto.Cipher

class LegacyHandler(
    private val context: Context,
    private val activity: Activity
) {
    private val fingerprintManager = FingerprintManager(context)
    private val devicePinManager = DevicePinManager(activity)

    fun authenticate(
        onSuccess: (cipher: Cipher?) -> Unit,
        onFailure: (error: String) -> Unit,
        cipher: Cipher?,
        promptInfo: AndroidPromptInfo
    ) {
        fingerprintManager.authenticate(
                cipher,
                onSuccess,
                { error ->
                    // If fingerprint fails, fallback to device PIN
                    devicePinManager.authenticate(
                            onSuccess,
                            { onFailure("Authentication failed: $error") },
                            promptInfo)
            })
    }

    fun handleAuthenticationResult(requestCode: Int, resultCode: Int) {
        devicePinManager(requestCode, resultCode)
    }
}
