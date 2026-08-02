package com.gitdroidstore

import android.os.Bundle
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitdroidstore.model.StoreApp
import com.gitdroidstore.update.UpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URI
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
        Text("GitDroidStore descarga un único catalog.json público. El catálogo predeterminado es el de CctrGy.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp)); Button(onClick = refresh) { Text("Buscar aplicaciones") }
    } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(apps, key = { "${it.owner}/${it.repo}" }) { app -> AppCard(app, install) }
    }
}

@Composable private fun AppCard(app: StoreApp, install: (StoreApp) -> Unit) = ElevatedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.iconUrl, app.displayName)
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

@Composable private fun AppIcon(url: String?, appName: String) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url) {
        value = if (url == null) null else withContext(Dispatchers.IO) { loadGitHubIcon(url)?.asImageBitmap() }
    }
    val modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer)
    if (bitmap != null) {
        Image(bitmap = bitmap!!, contentDescription = "Icono de $appName", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun loadGitHubIcon(url: String): android.graphics.Bitmap? = runCatching {
    val uri = URI(url)
    require(uri.scheme.equals("https", true) && uri.host.equals("raw.githubusercontent.com", true))
    val connection = uri.toURL().openConnection().apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "GitDroidStore/1")
    }
    require(connection.contentLengthLong in -1..MAX_ICON_BYTES)
    val bytes = connection.getInputStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_ICON_BYTES)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..MAX_ICON_DIMENSION && bounds.outHeight in 1..MAX_ICON_DIMENSION)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

private const val MAX_ICON_BYTES = 2 * 1024 * 1024
private const val MAX_ICON_DIMENSION = 2048

@Composable private fun Settings(vm: StoreViewModel) {
    var user by remember { mutableStateOf(vm.settings.githubUser) }
    var auto by remember { mutableStateOf(vm.settings.autoCheck) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Origen GitHub", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(user, { user = it }, label = { Text("Propietario del catálogo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Se descargará https://raw.githubusercontent.com/<usuario>/GitDroidStore/main/catalog.json. No necesita token.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(auto, { auto = it }); Spacer(Modifier.width(10.dp)); Text("Comprobar actualizaciones automáticamente") }
        Button(onClick = { vm.settings.githubUser = user; vm.settings.githubToken = ""; vm.settings.autoCheck = auto; UpdateScheduler.schedule(vm.getApplication(), auto); vm.refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Guardar y actualizar") }
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
