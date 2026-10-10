package de.chris.dexreloaded

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import de.chris.dexreloaded.shizuku.ShizukuManager
import de.chris.dexreloaded.ui.DexTheme
import de.chris.dexreloaded.ui.MainScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShizukuManager.init(this)
        DexController.init(this)

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
