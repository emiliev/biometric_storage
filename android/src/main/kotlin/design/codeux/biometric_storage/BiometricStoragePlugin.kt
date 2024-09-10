package design.codeux.biometric_storage

import android.app.Activity
import android.content.Context
import android.os.*
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.biometric.*
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.fragment.app.FragmentActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.*
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.PrintWriter
import java.io.StringWriter
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException


enum class CipherMode {
    Encrypt,
    Decrypt,
}

typealias ErrorCallback = (errorInfo: AuthenticationErrorInfo) -> Unit

class MethodCallException(
    val errorCode: String,
    val errorMessage: String?,
    val errorDetails: Any? = null
) : Exception(errorMessage ?: errorCode)

@Suppress("unused")
enum class CanAuthenticateResponse(val code: Int) {
    Success(BiometricManager.BIOMETRIC_SUCCESS),
    ErrorHwUnavailable(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
    ErrorNoBiometricEnrolled(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
    ErrorNoHardware(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
    ErrorStatusUnknown(BiometricManager.BIOMETRIC_STATUS_UNKNOWN),
    ;

    override fun toString(): String {
        return "CanAuthenticateResponse.${name}: $code"
    }
}

@Suppress("unused")
enum class AuthenticationError(vararg val code: Int) {
    Canceled(BiometricPrompt.ERROR_CANCELED),
    Timeout(BiometricPrompt.ERROR_TIMEOUT),
    UserCanceled(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON),
    Unknown(-1),

    /** Authentication valid, but unknown */
    Failed(-2),
    ResetBiometrics(-3),
    ;

    companion object {
        fun forCode(code: Int) =
            values().firstOrNull { it.code.contains(code) } ?: Unknown
    }
}

data class AuthenticationErrorInfo(
    val error: AuthenticationError,
    val message: CharSequence,
    val errorDetails: String? = null
) {
    constructor(
        error: AuthenticationError,
        message: CharSequence,
        e: Throwable
    ) : this(error, message, e.toCompleteString())
}

private fun Throwable.toCompleteString(): String {
    val out = StringWriter().let { out ->
        printStackTrace(PrintWriter(out))
        out.toString()
    }
    return "$this\n$out"
}

class BiometricStoragePlugin : FlutterPlugin, ActivityAware, MethodCallHandler {

    companion object {
        const val PARAM_NAME = "name"
        const val PARAM_WRITE_CONTENT = "content"
        const val PARAM_ANDROID_PROMPT_INFO = "androidPromptInfo"
        const val REQUEST_CODE = 1001
    }

    private var attachedActivity: FragmentActivity? = null

    private val storageFiles = mutableMapOf<String, BiometricStorageFile>()

    private val biometricManager by lazy { BiometricManager.from(applicationContext) }

    private lateinit var applicationContext: Context

    private lateinit var channel: MethodChannel
    private lateinit var logger: CustomLogger
    private val legayHandler by lazy { LegacyHandler(applicationContext, attachedActivity!) }

    private val isAndroidQ = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
    private val isDeprecatedVersion = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        this.applicationContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "biometric_storage")
        channel.setMethodCallHandler(this)
        logger = CustomLogger(channel)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) { }

    override fun onMethodCall(call: MethodCall, result: Result) {
        logger.trace("onMethodCall(${call.method})");
        try {
            fun <T> requiredArgument(name: String) =
                call.argument<T>(name) ?: throw MethodCallException(
                    "MissingArgument",
                    "Missing required argument '$name'"
                )

            // every method call requires the name of the stored file.
            val getName = { requiredArgument<String>(PARAM_NAME) }
            val getAndroidPromptInfo = {
                requiredArgument<Map<String, Any>>(PARAM_ANDROID_PROMPT_INFO).let {
                    AndroidPromptInfo(
                        title = it["title"] as String,
                        subtitle = it["subtitle"] as String?,
                        description = it["description"] as String?,
                        negativeButton = it["negativeButton"] as String,
                        confirmationRequired = it["confirmationRequired"] as Boolean,
                    )
                }
            }

            fun withStorage(cb: BiometricStorageFile.() -> Unit) {
                val name = getName()
                storageFiles[name]?.apply(cb) ?: run {
                    logger.warn("User tried to access storage '$name', before initialization");
                    result.error("Storage $name was not initialized.", null, null)
                    return
                }
            }

            val resultError: ErrorCallback = { errorInfo ->
                result.error(
                    "AuthError:${errorInfo.error}",
                    errorInfo.message.toString(),
                    errorInfo.errorDetails
                )
                logger.error("AuthError: $errorInfo")

            }

            fun BiometricStorageFile.withAuth(
                mode: CipherMode,
                cb: BiometricStorageFile.(cipher: Cipher?) -> Unit
            ) {
                if (!options.authenticationRequired) {
                    return cb(null)
                }

                fun cipherForMode() = when (mode) {
                    CipherMode.Encrypt -> cipherForEncrypt()
                    CipherMode.Decrypt -> cipherForDecrypt()
                }

                val cipher = if (options.authenticationValidityDurationSeconds > -1 && !options.androidBiometricOnly) {
                    null
                } else try {
                    cipherForMode()
                } catch (e: KeyPermanentlyInvalidatedException) {
                    // TODO should we communicate this to the caller?
                    logger.warn("Key was invalidated. removing previous storage and recreating.")
                    deleteFile()
                    cipherForMode()
                }

               if (cipher == null && options.androidBiometricOnly) {
                   // if we have no cipher, just try the callback and see if the
                   // user requires authentication.
                   try {
                       return cb(null)
                   } catch (e: UserNotAuthenticatedException) {
                       logger.debug("User requires (re)authentication. showing prompt ...")
                   } catch (e: IllegalBlockSizeException) {
                       result.error(
                           "AuthError:${AuthenticationError.ResetBiometrics}",
                           "auth:trying to ask for a prompt with an invalid key",
                           "auth:trying to ask for a prompt with an invalid key"
                       )
                       return
                   }
               }

                val promptInfo = getAndroidPromptInfo()
                auth(cipher, promptInfo, options, {
                    try {
                        cb(cipher)
                    } catch (ex: GeneralSecurityException) {
                    // trying to read/write to a file with an invalid keystore, must reset the biometrics
                    result.error(
                        "AuthError:${AuthenticationError.ResetBiometrics}",
                        ex.toCompleteString(),
                        ex.message
                    )
                }
                }, onError = resultError)
            }

            when (call.method) {
                "canAuthenticate" -> result.success(canAuthenticate().name)
                "hasAuthMechanism" -> result.success(hasAuthMechanism())
                "init" -> {
                    val name = getName()
                    if (storageFiles.containsKey(name)) {
                        if (call.argument<Boolean>("forceInit") == true) {
                            throw MethodCallException(
                                "AlreadyInitialized",
                                "A storage file with the name '$name' was already initialized."
                            )
                        } else {
                            result.success(false)
                            return
                        }
                    }

                    val options = call.argument<Map<String, Any>>("options")?.let { it ->
                        InitOptions(
                            authenticationRequired = it["authenticationRequired"] as Boolean,
                            androidBiometricOnly = if (it["authenticationDevicePinFallback"] as? Boolean ?: false) false else it["androidBiometricOnly"] as Boolean,
                        )
                    } ?: InitOptions()
                    storageFiles[name] = BiometricStorageFile(applicationContext, name, options)
                    result.success(true)
                }

                "dispose" -> storageFiles.remove(getName())?.apply {
                    dispose()
                    result.success(true)
                } ?: throw MethodCallException(
                    "NoSuchStorage",
                    "Tried to dispose non existing storage.",
                    null
                )

                "read" -> withStorage {
                    if (exists()) {
                        withAuth(CipherMode.Decrypt) {
                            val ret = readFile(
                                    it,
                                )
                            ui(resultError) { result.success(ret) }
                        }
                    } else {
                        // trying to read a file which doesn't exist, must reset the biometrics
                        resetStorage()
                        result.error(
                            "AuthError:${AuthenticationError.ResetBiometrics}",
                            "read:file does not exists",
                            "read:file does not exists"
                        )
                    }
                }

                "delete" -> withStorage {
                    if (exists()) {
                        result.success(deleteFile())
                    } else {
                        result.success(false)
                    }
                }

                "write" -> withStorage {
                    withAuth(CipherMode.Encrypt) {
                        writeFile(it, requiredArgument(PARAM_WRITE_CONTENT))
                        ui(resultError) { result.success(true) }
                    }
                }

                else -> result.notImplemented()
            }
        } catch (e: MethodCallException) {
            logger.error("Error while processing method call ${call.method}")
            result.error(e.errorCode, e.errorMessage, e.errorDetails)
        } catch (e: InvalidKeyException) {
            // something wrong with the keystore, reset the biometrics
            resetStorage()
            result.error(
                "AuthError:${AuthenticationError.ResetBiometrics}",
                e.message,
                e.toCompleteString()
            )
        } catch (e: Exception) {
            logger.error("Error while processing method call '${call.method}'")
            result.error("Unexpected Error", e.message, e.toCompleteString())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        legayHandler.handleAuthenticationResult(requestCode, resultCode)
    }

    private fun resetStorage() {
        storageFiles.values.forEach { it.deleteFile() }
        storageFiles.clear()
    }

    private inline fun ui(
        crossinline onError: ErrorCallback,
        crossinline cb: () -> Unit
    ) {
        try {
            cb()
        } catch (e: Throwable) {
            logger.error("Error while calling UI callback. This must not happen." )
            // something really bad happened, should reset the biometrics
            onError(
                AuthenticationErrorInfo(
                    AuthenticationError.ResetBiometrics,
                    "Unexpected authentication error. ${e.localizedMessage}",
                    e
                )
            )
        }
    }

    private inline fun worker(
        crossinline onError: ErrorCallback,
        crossinline cb: () -> Unit
    ) {
        try {
            cb()
        } catch (e: Throwable) {
            logger.error("Error while calling worker callback. This must not happen.")
            // something really bad happened, should reset the biometrics
            onError(
                AuthenticationErrorInfo(
                    AuthenticationError.ResetBiometrics,
                    "Unexpected authentication error. ${e.localizedMessage}",
                    e
                )
            )
        }
    }

    private fun canAuthenticate(): CanAuthenticateResponse {
        val response = biometricManager.canAuthenticate(
            BIOMETRIC_STRONG
        )
        return CanAuthenticateResponse.values().firstOrNull { it.code == response }
            ?: throw Exception(
                "Unknown response code {$response} (available: ${
                    CanAuthenticateResponse
                        .values()
                        .contentToString()
                }"
            )
    }

    private fun hasAuthMechanism(): Boolean {
        logger.trace("hasAuthMechanism()")
        if (isAndroidQ || isDeprecatedVersion) {
             return hasLegacyAuthMechanism()
        } 
        
        val result = biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        
        logger.trace("hasAuthMechanism() result $result")
        
        val response = CanAuthenticateResponse.values().firstOrNull { it.code == result }
        
        logger.trace("hasAuthMechanism() response $response")
        
        val isSuccess = response?.code == BiometricManager.BIOMETRIC_SUCCESS
        logger.trace("hasAuthMechanism() response is success $isSuccess")
        
        return isSuccess   
    }

    private fun hasLegacyAuthMechanism(): Boolean {
        val legacyAuthResp = when {
            isAndroidQ -> biometricManager.canAuthenticate()
            else -> BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        }
        
        logger.trace("hasAuthMechanism() authentication response is $legacyAuthResp")

        if (legacyAuthResp != BiometricManager.BIOMETRIC_SUCCESS) {
            logger.trace("hasAuthMechanism() authentication response not success. Checking keyguardManager isDeviceSecure")
            val keyguardManager = attachedActivity?.getSystemService(KeyguardManager::class.java)
            val isDeviceSecure = keyguardManager?.isDeviceSecure() ?: false;
            logger.trace("hasAuthMechanism() keyguardManager isDeviceSecure: $isDeviceSecure")
            return isDeviceSecure;
        }
        return true;
    }


    private fun auth(
        cipher: Cipher?,
        promptInfo: AndroidPromptInfo,
        options: InitOptions,
        onSuccess: (cipher: Cipher?) -> Unit,
        onError: ErrorCallback) {
            if (isAndroidQ || isDeprecatedVersion && hasLegacyAuthMechanism()) {
                authenticateWithLegacyApi(cipher, promptInfo, onSuccess, onError)    
            } else {
                authenticate(cipher, promptInfo, options,onSuccess, onError)
            }  
    }

    private fun authenticateWithLegacyApi(
        cipher: Cipher?,
        promptInfo: AndroidPromptInfo,
        onSuccess: (cipher: Cipher?) -> Unit,
        onError: ErrorCallback
    ) {
        legayHandler.authenticate(onSuccess, onError, cipher, promptInfo)
    }

    private fun authenticate(
        cipher: Cipher?,
        promptInfo: AndroidPromptInfo,
        options: InitOptions,
        onSuccess: (cipher: Cipher?) -> Unit,
        onError: ErrorCallback
    ) {
        logger.trace("authenticate()")
        val activity = attachedActivity ?: return run {
            logger.error("We are not attached to an activity.")
            onError(
                AuthenticationErrorInfo(
                    AuthenticationError.Failed,
                    "Plugin not attached to any activity."
                )
            )
        }
        val prompt =
            BiometricPrompt(activity, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    logger.trace("onAuthenticationError($errorCode, $errString)")
                    ui(onError) {
                        onError(
                            AuthenticationErrorInfo(
                                AuthenticationError.forCode(
                                    errorCode
                                ), errString
                            )
                        )
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    logger.trace("onAuthenticationSucceeded($result)")
                    worker(onError) { onSuccess(result.cryptoObject?.cipher) }
                }

                override fun onAuthenticationFailed() {
                    logger.trace("onAuthenticationFailed()")
                    // this just means the user was not recognised, but the O/S will handle feedback so we don't have to
                }
            })

        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(promptInfo.title)
            .setSubtitle(promptInfo.subtitle)
            .setDescription(promptInfo.description)
            .setConfirmationRequired(promptInfo.confirmationRequired)

        val biometricOnly =
                options.androidBiometricOnly || Build.VERSION.SDK_INT < Build.VERSION_CODES.R

        if (biometricOnly) {
            promptBuilder.apply {
                setAllowedAuthenticators(BIOMETRIC_STRONG)
                setNegativeButtonText(promptInfo.negativeButton)
            }
        } else if (isDeprecatedVersion) {
            // Do nothing
        } else {
            promptBuilder.setAllowedAuthenticators(DEVICE_CREDENTIAL or BIOMETRIC_STRONG)
        }

        if (cipher == null || options.authenticationValidityDurationSeconds >= 0) {
            // if authenticationValidityDurationSeconds is not -1 we can't use a CryptoObject
            logger.debug("Authenticating without cipher. ${options.authenticationValidityDurationSeconds}")
            prompt.authenticate(promptBuilder.build())
        } else {
            prompt.authenticate(promptBuilder.build(), BiometricPrompt.CryptoObject(cipher))
        }
    }

    override fun onDetachedFromActivity() {
        logger.debug("onDetachedFromActivity")
        attachedActivity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        logger.debug("Attached to new activity.")
        updateAttachedActivity(binding.activity)
    }

    private fun updateAttachedActivity(activity: Activity) {
        if (activity !is FragmentActivity) {
            logger.error("Got attached to activity which is not a FragmentActivity: $activity")
            return
        }
        attachedActivity = activity
        legayHandler = LegacyHandler()
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }
}

data class AndroidPromptInfo(
    val title: String,
    val subtitle: String?,
    val description: String?,
    val negativeButton: String,
    val confirmationRequired: Boolean
)
