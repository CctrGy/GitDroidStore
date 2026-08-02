package com.gitdroidstore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitdroidstore.model.StoreApp
import com.gitdroidstore.update.UpdateScheduler
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val vm by viewModels<StoreViewModel>()
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GitDroidTheme { StoreScreen(vm) { installPermission.launch(vm.installer.settingsIntent()) } } }
    }
}

private enum class Tab { HOME, SETTINGS, LOGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StoreScreen(vm: StoreViewModel, requestInstallPermission: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("GitDroidStore", fontWeight = FontWeight.Bold) }, actions = {
            if (tab == Tab.HOME) IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, "Actualizar") }
        }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { NavigationBar {
            NavigationBarItem(tab == Tab.HOME, { tab = Tab.HOME }, { Icon(Icons.Default.Home, null) }, label = { Text("Inicio") })
            NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(Icons.Default.Settings, null) }, label = { Text("Ajustes") })
            NavigationBarItem(tab == Tab.LOGS, { tab = Tab.LOGS; vm.reloadLogs() }, { Icon(Icons.Default.List, null) }, label = { Text("Logs") })
        } }
    ) { padding -> Box(Modifier.padding(padding).fillMaxSize()) {
        when (tab) {
            Tab.HOME -> Home(state.apps, state.loading, vm::refresh) { app ->
                if (vm.installer.canInstall()) vm.install(app) else requestInstallPermission()
            }
            Tab.SETTINGS -> Settings(vm)
            Tab.LOGS -> Logs(state.logs)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    } }
}

@Composable private fun Home(apps: List<StoreApp>, loading: Boolean, refresh: () -> Unit, install: (StoreApp) -> Unit) {
    if (apps.isEmpty() && !loading) Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Android, null, Modifier.size(64.dp)); Spacer(Modifier.height(16.dp))
        Text("No hay aplicaciones", style = MaterialTheme.typography.headlineSmall)
        Text("Configura un usuario de GitHub. Solo aparecerán repositorios cuya última Release contenga app.apk.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp)); Button(onClick = refresh) { Text("Buscar aplicaciones") }
    } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(apps, key = { "${it.owner}/${it.repo}" }) { app -> AppCard(app, install) }
    }
}

@Composable private fun AppCard(app: StoreApp, install: (StoreApp) -> Unit) = ElevatedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Android, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                Text(app.displayName, style = MaterialTheme.typography.titleLarge)
                Text("${app.owner}/${app.repo}", style = MaterialTheme.typography.labelMedium)
            }
            AssistChip(onClick = {}, label = { Text(if (app.hasUpdate) "Actualización" else if (app.isInstalled) "Instalada" else "Disponible") })
        }
        if (app.description.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(app.description) }
        Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
            Text(app.versionName?.let { "Versión $it" } ?: "Versión no declarada", Modifier.weight(1f))
            Button(onClick = { install(app) }) { Text(if (app.isInstalled) "Actualizar" else "Instalar") }
        }
    }
}

@Composable private fun Settings(vm: StoreViewModel) {
    var user by remember { mutableStateOf(vm.settings.githubUser) }
    var token by remember { mutableStateOf(vm.settings.githubToken) }
    var auto by remember { mutableStateOf(vm.settings.autoCheck) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Origen GitHub", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(user, { user = it }, label = { Text("Usuario de GitHub") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Token personal (opcional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(auto, { auto = it }); Spacer(Modifier.width(10.dp)); Text("Comprobar actualizaciones automáticamente") }
        Button(onClick = { vm.settings.githubUser = user; vm.settings.githubToken = token; vm.settings.autoCheck = auto; UpdateScheduler.schedule(vm.getApplication(), auto); vm.refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Guardar y actualizar") }
        HorizontalDivider(); Text("Seguridad", style = MaterialTheme.typography.titleMedium)
        Text("Las primeras instalaciones exigen certificateSha256 en version.json. Las actualizaciones deben conservar ese certificado.")
    }
}

@Composable private fun Logs(logs: List<com.gitdroidstore.model.LogEntry>) = LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    items(logs, key = { it.id }) { log -> ListItem(
        headlineContent = { Text(log.message) }, supportingContent = { Text(DateFormat.getDateTimeInstance().format(Date(log.timestamp))) },
        leadingContent = { Icon(if (log.level == "ERROR") Icons.Default.Error else Icons.Default.Info, null) }
    ) }
}

@Composable private fun GitDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF146C43), secondary = androidx.compose.ui.graphics.Color(0xFF4F6356)), content = content)
}
