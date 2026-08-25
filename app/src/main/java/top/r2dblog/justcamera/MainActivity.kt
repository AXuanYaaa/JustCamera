package top.r2dblog.justcamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import top.r2dblog.justcamera.camera.application.CameraController
import top.r2dblog.justcamera.ui.JustCameraApp
import top.r2dblog.justcamera.ui.theme.JustCameraTheme
import top.r2dblog.justcamera.settings.AppLanguage
import top.r2dblog.justcamera.settings.AppLanguageManager
import top.r2dblog.justcamera.settings.AppSettingsRepository

class MainActivity : AppCompatActivity() {
    private lateinit var cameraController: CameraController
    private lateinit var settingsRepository: AppSettingsRepository
    private var cameraPermissionGranted by mutableStateOf(false)
    private var storagePermissionGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        syncPermissionState()
        cameraController.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsRepository = AppSettingsRepository(this)
        cameraController = CameraController(applicationContext)
        syncPermissionState()
        val selectedLanguage = settingsRepository.language()
        setContent {
            JustCameraTheme {
                JustCameraApp(
                    cameraController = cameraController,
                    cameraPermissionGranted = cameraPermissionGranted,
                    storagePermissionGranted = storagePermissionGranted,
                    requestPermissions = ::requestRequiredPermissions,
                    selectedLanguage = selectedLanguage,
                    selectLanguage = ::selectLanguage,
                )
            }
        }
    }

    private fun selectLanguage(language: AppLanguage) {
        if (settingsRepository.language() == language) return
        settingsRepository.setLanguage(language)
        AppLanguageManager.apply(language)
    }

    override fun onStart() {
        super.onStart()
        syncPermissionState()
        cameraController.start()
    }

    override fun onStop() {
        cameraController.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cameraController.release()
        super.onDestroy()
    }

    private fun requestRequiredPermissions() {
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray(),
        )
    }

    private fun syncPermissionState() {
        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        storagePermissionGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        if (::cameraController.isInitialized) {
            cameraController.updatePermissions(
                cameraGranted = cameraPermissionGranted,
                storageGranted = storagePermissionGranted,
            )
        }
    }
}
