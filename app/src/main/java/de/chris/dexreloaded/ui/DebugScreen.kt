package de.chris.dexreloaded.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.chris.dexreloaded.DexController
import de.chris.dexreloaded.debug.DebugInfo
import de.chris.dexreloaded.shizuku.ShizukuManager

@Composable
fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var manual by remember { mutableIntStateOf(0) }
    val displayRev by DexController.displayRevision.collectAsState()
    val slots by DexController.slots.collectAsState()
    val service by ShizukuManager.service.collectAsState()
    val status by ShizukuManager.status.collectAsState()
    val sections = remember(manual, displayRev, slots, service, status) { DebugInfo.collect(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Zurück") }
            OutlinedButton(onClick = { manual++ }) { Text("Neu laden") }
            Button(onClick = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("DEX reloaded Debug", DebugInfo.asText(sections)))
                Toast.makeText(context, "Kopiert", Toast.LENGTH_SHORT).show()
            }) { Text("Kopieren") }
        }
        Text("Debug-Ansicht", style = MaterialTheme.typography.headlineSmall)
        sections.forEach { (title, text) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    SelectionContainer {
                        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
