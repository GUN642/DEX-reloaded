package de.chris.dexreloaded.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.chris.dexreloaded.DexController
import de.chris.dexreloaded.notify.Notifications
import de.chris.dexreloaded.render.ScreenLayout
import de.chris.dexreloaded.workspace.Workspace
import de.chris.dexreloaded.workspace.Workspaces

/** Benachrichtigungen in der Brille: Zugriff, Einblenden, Nicht stören. */
@Composable
fun NotifyCard() {
    val context = LocalContext.current
    val connected by Notifications.connected.collectAsState()
    val popups by Notifications.popups.collectAsState()
    val dnd by Notifications.doNotDisturb.collectAsState()
    val notes by Notifications.list.collectAsState()
    var access by remember { mutableStateOf(Notifications.hasAccess(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { access = Notifications.hasAccess(context) }
    }

    Section("Benachrichtigungen") {
        if (!access) {
            Text("Damit Benachrichtigungen in der Brille erscheinen, braucht DEX reloaded den Benachrichtigungszugriff.")
            Text(
                "Ist der Schalter dort gesperrt: In der App-Info von DEX reloaded oben rechts im Menü „Eingeschränkte Einstellungen zulassen“ wählen, dann noch einmal.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runCatching { context.startActivity(Notifications.accessIntent(context)) } }) {
                    Text("Zugriff erlauben")
                }
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("App-Info") }
            }
        } else {
            Text(
                if (connected) "Verbunden. ${notes.size} Benachrichtigungen. Liste über die Glocke im Startbildschirm."
                else "Zugriff erlaubt, Verbindung wird aufgebaut.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Neue oben in der Brille einblenden", modifier = Modifier.weight(1f))
            Switch(checked = popups, onCheckedChange = { Notifications.setPopups(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nicht stören (nichts einblenden)", modifier = Modifier.weight(1f))
            Switch(checked = dnd, onCheckedChange = { Notifications.setDoNotDisturb(it) })
        }
    }
}

/** Arbeitsplätze speichern, laden, löschen. */
@Composable
fun WorkspacesCard() {
    val list by Workspaces.list.collectAsState()
    val current by Workspaces.current.collectAsState()
    var name by remember { mutableStateOf("") }

    Section("Arbeitsplätze") {
        Text(
            "Speichert Apps pro Bildschirm, weitere Bildschirme, Breitbild mit Fensterlage und Kino. Ein Klick stellt alles wieder her.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (ws in list) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ws.name + if (ws.name == current) "  ●" else "", style = MaterialTheme.typography.titleSmall)
                    Text(summary(ws), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                Button(onClick = { DexController.applyWorkspace(ws) }) { Text("Laden") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { DexController.saveWorkspace(ws.name) }) { Text("Mit aktuellem überschreiben") }
                TextButton(onClick = { Workspaces.delete(ws.name) }) { Text("Löschen") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                label = { Text("Name, z. B. Arbeit") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    DexController.saveWorkspace(name)
                    name = ""
                },
            ) { Text("Speichern") }
        }
    }
}

fun summary(ws: Workspace): String = buildString {
    if (ws.wide) {
        append("Breitbild")
        if (ws.windows.isNotEmpty()) append(", ").append(ws.windows.joinToString(", ") { it.app.label })
    } else {
        append(
            ScreenLayout.sorted(ScreenLayout.BASE + ws.extras)
                .joinToString(" · ") { "${ScreenLayout.shortName(it)}: ${ws.apps[it]?.label ?: "leer"}" },
        )
        if (ws.cinema) append(", Kino")
    }
}
