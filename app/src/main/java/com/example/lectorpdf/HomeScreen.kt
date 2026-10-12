package com.example.lectorpdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Mantiene la pila de carpetas visitadas (sobrevive al ir al lector y volver). */
class FolderBrowser {
    class Crumb(val file: DocumentFile, val name: String)

    val stack = mutableStateListOf<Crumb>()
    private var rootKey: String? = null

    suspend fun ensureRoot(context: Context, rootUri: String?) {
        if (rootKey == rootUri && (rootUri == null || stack.isNotEmpty())) return
        rootKey = rootUri
        stack.clear()
        if (rootUri != null) {
            val root = withContext(Dispatchers.IO) {
                DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
                    ?.let { Crumb(it, it.name ?: "Carpeta raíz") }
            }
            if (root != null) stack.add(root)
        }
    }
}

private class Entry(val file: DocumentFile, val name: String, val isDir: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    browser: FolderBrowser,
    rootUri: String?,
    onRootSelected: (Uri) -> Unit,
    onOpenPdf: (Uri, String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val stack = browser.stack

    var ready by remember(rootUri) { mutableStateOf(false) }
    LaunchedEffect(rootUri) {
        browser.ensureRoot(context, rootUri)
        ready = true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Permiso persistente: la carpeta sigue accesible al reabrir la app
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            onRootSelected(uri)
        }
    }

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    val folder = stack.lastOrNull()
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(folder) {
        entries = emptyList()
        val f = folder?.file ?: return@LaunchedEffect
        loading = true
        entries = withContext(Dispatchers.IO) {
            f.listFiles().mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                val dir = file.isDirectory
                val isBook = name.endsWith(".pdf", ignoreCase = true) ||
                    name.endsWith(".cbz", ignoreCase = true)
                if (dir || isBook) Entry(file, name, dir) else null
            }.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(folder?.name ?: "Lector PDF", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (stack.size > 1) {
                        IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Subir una carpeta")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Configuración")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                rootUri == null -> EmptyState(
                    message = "Elige la carpeta donde tienes tus PDF",
                    buttonText = "Elegir carpeta raíz"
                ) { picker.launch(null) }

                !ready -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                folder == null -> EmptyState(
                    message = "No se puede acceder a la carpeta elegida",
                    buttonText = "Elegir otra carpeta"
                ) { picker.launch(null) }

                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        TextButton(
                            onClick = { picker.launch(null) },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) { Text("Cambiar carpeta raíz") }
                    }
                    if (entries.isEmpty()) {
                        item { Text("No hay carpetas ni libros (PDF/CBZ) aquí.", Modifier.padding(16.dp)) }
                    }
                    items(entries, key = { it.file.uri.toString() }) { e ->
                        ListItem(
                            headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                Text(
                                    when {
                                        e.isDir -> "📁"
                                        e.name.endsWith(".cbz", ignoreCase = true) -> "📚"
                                        else -> "📄"
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                if (e.isDir) stack.add(FolderBrowser.Crumb(e.file, e.name))
                                else onOpenPdf(e.file.uri, e.name)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String, buttonText: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message)
        Button(onClick = onClick, modifier = Modifier.padding(top = 16.dp)) { Text(buttonText) }
    }
}
