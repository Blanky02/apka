package dev.blanky.vinyl.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blanky.vinyl.BuildConfig
import dev.blanky.vinyl.VinylApplication
import dev.blanky.vinyl.data.model.AudioQuality
import dev.blanky.vinyl.data.settings.VinylSettings
import dev.blanky.vinyl.data.source.ApiLog
import dev.blanky.vinyl.data.source.octave.OctaveAuthState
import dev.blanky.vinyl.data.source.octave.OctaveLoginResult
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: VinylApplication, modifier: Modifier = Modifier) {
    val settings = app.settings
    val scope = rememberCoroutineScope()

    val quality by settings.preferredQuality.collectAsStateWithLifecycle(AudioQuality.DEFAULT)
    val themeMode by settings.themeMode.collectAsStateWithLifecycle(initialValue = "dark")
    val octaveEnabled by settings.octaveEnabled.collectAsStateWithLifecycle(initialValue = true)
    val octaveBase by settings.octaveBase.collectAsStateWithLifecycle(VinylSettings.DEFAULT_OCTAVE_BASE)
    val searchTpl by settings.octaveSearchTemplate.collectAsStateWithLifecycle(VinylSettings.DEFAULT_OCTAVE_SEARCH)
    val streamTpl by settings.octaveStreamTemplate.collectAsStateWithLifecycle(VinylSettings.DEFAULT_OCTAVE_STREAM)
    val status by app.sources.status.collectAsStateWithLifecycle(initialValue = emptyMap())
    val authState by app.sources.octave.auth.collectAsStateWithLifecycle(initialValue = OctaveAuthState())
    val loginTpl by settings.octaveLoginTemplate.collectAsStateWithLifecycle(initialValue = "")

    var showQualityMenu by remember { mutableStateOf(false) }
    var testingMono by remember { mutableStateOf(false) }
    var testingOct by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var keyField by remember { mutableStateOf("") }

    // Test połączeń przy wejściu w ustawienia.
    LaunchedEffect(Unit) {
        scope.launch { app.sources.testSource("monochrome") }
        if (octaveEnabled) {
            scope.launch { app.sources.testSource("octave") }
            scope.launch { app.sources.octave.refreshAuthFromStoredKey() }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- Jakość ----------
        item { SectionTitle("Jakość dźwięku") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Jakość strumienia", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = quality.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    OutlinedButton(onClick = { showQualityMenu = true }) {
                        Text(quality.label)
                    }
                    DropdownMenu(expanded = showQualityMenu, onDismissRequest = { showQualityMenu = false }) {
                        AudioQuality.entries.forEach { q ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(q.label)
                                        Text(
                                            q.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    showQualityMenu = false
                                    scope.launch { settings.setPreferredQuality(q) }
                                },
                            )
                        }
                    }
                }
            }
        }

        // ---------- Wygląd ----------
        item { SectionTitle("Wygląd") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip("Jasny", "light", themeMode, onClick = { scope.launch { settings.setThemeMode("light") } })
                ThemeChip("System", "system", themeMode, onClick = { scope.launch { settings.setThemeMode("system") } })
                ThemeChip("Ciemny", "dark", themeMode, onClick = { scope.launch { settings.setThemeMode("dark") } })
            }
        }

        // ---------- Źródła ----------
        item { SectionTitle("Źródła muzyki") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Monochrome (Tidal)", style = MaterialTheme.typography.bodyMedium)
                        SourceStatusText("monochrome", status["monochrome"])
                    }
                    OutlinedButton(
                        onClick = {
                            testingMono = true
                            scope.launch {
                                app.sources.testSource("monochrome")
                                testingMono = false
                            }
                        },
                    ) {
                        if (testingMono) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Testuj")
                        }
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Octave (beta)", style = MaterialTheme.typography.bodyMedium)
                        SourceStatusText("octave", status["octave"])
                    }
                    Switch(
                        checked = octaveEnabled,
                        onCheckedChange = { on ->
                            scope.launch {
                                settings.setOctaveEnabled(on)
                                if (on) app.sources.testSource("octave")
                            }
                        },
                    )
                }
                if (octaveEnabled) {
                    Text(
                        text = "Octave nie publikuje dokumentacji API. Pola poniżej to szablony endpointów — „Wykryj” testuje typowe warianty, a gdy poznasz dokładne adresy (np. z Discorda Octave), wklej je tutaj. Placeholder {query} = fraza, {id} = id utworu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = octaveBase,
                        onValueChange = { scope.launch { settings.setOctaveBase(it) } },
                        label = { Text("Adres API") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    OutlinedTextField(
                        value = searchTpl,
                        onValueChange = { scope.launch { settings.setOctaveSearchTemplate(it) } },
                        label = { Text("Szablon wyszukiwarki") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    OutlinedTextField(
                        value = streamTpl,
                        onValueChange = { scope.launch { settings.setOctaveStreamTemplate(it) } },
                        label = { Text("Szablon strumienia") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                probing = true
                                probeResult = null
                                scope.launch {
                                    val r = app.sources.octave.probeSearchEndpoint()
                                    probing = false
                                    probeResult = if (r.found) {
                                        "Znaleziono: ${r.endpoint} (przykładowa odpowiedź: ${r.sampleSize} utworów)"
                                    } else {
                                        "Nie znaleziono żadnego typowego endpointu. Podaj szablon ręcznie."
                                    }
                                    app.sources.testSource("octave")
                                }
                            },
                        ) {
                            if (probing) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Wykryj API")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                testingOct = true
                                scope.launch {
                                    app.sources.testSource("octave")
                                    testingOct = false
                                }
                            },
                        ) {
                            if (testingOct) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Testuj")
                            }
                        }
                    }
                    probeResult?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // ---------- Konto Octave ----------
        if (octaveEnabled) {
            item { SectionTitle("Konto Octave (klucz)") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Octave nie ma hasła — konto to klucz (frasa odzyskiwania) z octavestreaming.com → Settings. Uwaga: endpoint logowania jest obecnie niedostępny (404), ale Octave wydaje token odtwarzania także bez konta, więc pełne strumienie zwykle działają bez logowania. Klucz podaj tylko, jeśli chcesz spróbować konta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        authState.loggedIn -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "✓ Zalogowano",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            app.sources.octave.logout()
                                            keyField = ""
                                        }
                                    },
                                ) { Text("Wyloguj") }
                            }
                            authState.detail?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        else -> {
                            OutlinedTextField(
                                value = keyField,
                                onValueChange = { keyField = it },
                                label = { Text("Klucz konta") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val result = app.sources.octave.loginWithKey(keyField)
                                            if (result is OctaveLoginResult.Success) keyField = ""
                                        }
                                    },
                                    enabled = keyField.isNotBlank() && !authState.busy,
                                ) {
                                    if (authState.busy) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("Zaloguj")
                                    }
                                }
                            }
                            authState.detail?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = loginTpl,
                        onValueChange = { scope.launch { settings.setOctaveLoginTemplate(it) } },
                        label = { Text("Endpoint logowania (opcjonalny)") },
                        placeholder = { Text("np. POST /api/account/login — puste = auto-wykrywanie") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
        }

        // ---------- Diagnostyka ----------
        item { SectionTitle("Diagnostyka") }
        item {
            OutlinedButton(onClick = { showDiagnostics = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Pokaż log wywołań API (${ApiLog.snapshot().size})")
            }
        }

        // ---------- O aplikacji ----------
        item { SectionTitle("O aplikacji") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Vinyl v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Osobisty klient streamingowy. Muzyka: Monochrome API + Octave. Gra w jakości do 24-bit/192 kHz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showDiagnostics) {
        DiagnosticsDialog(onDismiss = { showDiagnostics = false })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp),
    )
}

@Composable
private fun ThemeChip(label: String, mode: String, current: String, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = mode == current,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun SourceStatusText(sourceId: String, status: dev.blanky.vinyl.data.source.SourceStatus?) {
    when {
        status == null -> Unit
        status.available -> Text(
            text = "✓ ${status.detail}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        else -> Text(
            text = status.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val entries = remember { ApiLog.snapshot() }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Log API", modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        val text = entries.joinToString("\n\n") { e ->
                            "${e.time}  ${e.source}/${e.op}  ${if (e.code >= 0) "HTTP ${e.code}" else "ERR"}  ${if (e.ok) "OK" else "!!"}\n${e.url}\n${e.summary}"
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Log API", text))
                    },
                    enabled = entries.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Skopiuj log",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Skopiuj")
                }
            }
        },
        text = {
            if (entries.isEmpty()) {
                Text("Brak wpisów.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(entries.size) { i ->
                            val e = entries[i]
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "${e.time}  ${e.source}/${e.op}  ${if (e.code >= 0) "HTTP ${e.code}" else "ERR"}  ${if (e.ok) "OK" else "!!"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (e.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = e.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = e.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Zamknij") }
        },
    )
}
