import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

class DevicePinManager(private val activity: Activity) {

    private val REQUEST_CODE = 1001
    private val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
    private lateinit var onSuccess: (cipher: Cipher?) -> Unit
    private lateinit var onFailure: () -> Unit

    fun authenticate(onSuccess: (cipher: Cipher?) -> Unit,
                     onFailure: () -> Unit,
                     promptInfo: AndroidPromptInfo
    ) {
        if (keyguardManager.isKeyguardSecure) {
            val intent = keyguardManager.createConfirmDeviceCredentialIntent(
                    promptInfo.title, promptInfo.subtitle)
            activity.startActivityForResult(intent, REQUEST_CODE)
        } else {
            onFailure()
        }
        this.onSuccess = onSuccess
        this.onFailure = onFailure
    }

    fun handleAuthenticationResult(requestCode: Int, resultCode: Int) {
        if (requestCode == REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                this.onSuccess(null)
            } else {
                this.onFailure()
            }
        }
    }
}
