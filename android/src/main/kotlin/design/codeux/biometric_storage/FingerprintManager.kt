import android.content.Context
import android.os.Build
import androidx.core.hardware.fingerprint.FingerprintManagerCompat
import androidx.core.os.CancellationSignal
import javax.crypto.Cipher

class FingerprintManager(
        private val context: Context,
) {

    private var cancellationSignal: CancellationSignal? = null
    private lateinit var fingerprintManager: FingerprintManagerCompat

    fun authenticate(cipher: Cipher,
                     onSuccess: (cipher: Cipher?) -> Unit,
                     onFailure: (error: String) -> Unit
    ) {

            fingerprintManager = FingerprintManagerCompat.from(context)
            cancellationSignal = CancellationSignal()

            if (fingerprintManager.isHardwareDetected && fingerprintManager.hasEnrolledFingerprints()) {
                val cryptoObject = FingerprintManagerCompat.CryptoObject(cipher)
                fingerprintManager.authenticate(
                        cryptoObject,
                        0,
                        cancellationSignal,
                        object : FingerprintManagerCompat.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: FingerprintManagerCompat.AuthenticationResult) {
                                super.onAuthenticationSucceeded(result)
                                onSuccess(result.cryptoObject?.cipher)
                            }

                            override fun onAuthenticationFailed() {
                                super.onAuthenticationFailed()
                                onFailure("Authentication failed")
                            }

                            override fun onAuthenticationError(errMsgId: Int, errString: CharSequence) {
                                super.onAuthenticationError(errMsgId, errString)
                                onFailure(errString.toString())
                            }
                        },
                        null
                )
            }
        }

    fun cancelAuthentication() {
        cancellationSignal?.cancel()
    }
}
