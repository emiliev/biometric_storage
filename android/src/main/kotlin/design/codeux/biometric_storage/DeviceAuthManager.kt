import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

class DeviceAuthManager(private val activity: Activity) {

    private val keyguardManager = activity.getSystemService(KeyguardManager::class.java)

    fun canAuthenticateWithPin(): Boolean  {
       val intent = keyguardManager.createConfirmDeviceCredentialIntent("", "")
        return intent != null
    }
}
