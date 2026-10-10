package de.chris.dexreloaded

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import de.chris.dexreloaded.update.Updater
import kotlinx.coroutines.launch
import de.chris.dexreloaded.shizuku.ShizukuManager
import de.chris.dexreloaded.ui.DexTheme
import de.chris.dexreloaded.ui.MainScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShizukuManager.init(this)
        DexController.init(this)
        if (savedInstanceState == null) lifecycleScope.launch { Updater.check() }

        setContent {
            DexTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) {
                        MainScreen(
                            onShowPresentation = { DexController.showPresentation(this@MainActivity) },
                            onOpenShizuku = ::openShizuku,
                        )
                    }
                }
            }
        }
    }

    /** Bluetooth-Tastatur: bei offenem Trackpad an den fokussierten Bildschirm. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (DexController.trackpadActive && isHardwareKey(event) && DexController.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun isHardwareKey(event: KeyEvent): Boolean {
        val dev = event.device ?: return false
        if (dev.isVirtual) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
            -> false
            else -> true
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuManager.refresh()
    }

    override fun onDestroy() {
        if (isFinishing) DexController.shutdown()
        super.onDestroy()
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "Shizuku ist nicht installiert", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))
                )
            }
        }
    }
}
