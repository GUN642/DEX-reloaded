package de.chris.dexreloaded.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import de.chris.dexreloaded.home.HomeStore
import kotlinx.coroutines.launch

/** Einstellungen für den Startbildschirm auf der Brille. */
@Composable
fun HomeCard() {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                message = HomeStore.setWallpaper(uri) ?: "Hintergrundbild übernommen."
            }
        }
    }
    Section("Startbildschirm") {
        Text(
            "Der Startbildschirm ist eine App wie jede andere. In „Bildschirme“ unter „DEX reloaded“ wählbar. " +
                "Apps im Startbildschirm gedrückt halten: auf Bildschirm 1, 2 oder 3 öffnen oder ans Dock heften. " +
                "Zurück zum Startbildschirm: Knopf „Home“ im Trackpad oder Strg+Alt+H.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Hintergrund wählen") }
            OutlinedButton(onClick = {
                scope.launch { message = HomeStore.usePhoneWallpaper() ?: "Hintergrund vom Handy übernommen." }
            }) { Text("Vom Handy") }
        }
        TextButton(onClick = {
            HomeStore.resetWallpaper()
            message = "Standardhintergrund aktiv."
        }) { Text("Standardhintergrund") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
