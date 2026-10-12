package com.example.lectorpdf

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

enum class Screen { HOME, SETTINGS, READER }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = SettingsRepository(applicationContext)

        setContent {
            val loaded by repo.settings.collectAsState(initial = null)
            val s = loaded
            if (s != null) {
                LectorTheme(s.themeMode) {
                    Surface(Modifier.fillMaxSize()) {
                        val scope = rememberCoroutineScope()
                        val browser = remember { FolderBrowser() }
                        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                        var pdfUri by rememberSaveable { mutableStateOf<String?>(null) }
                        var pdfName by rememberSaveable { mutableStateOf("") }
                        var startPage by rememberSaveable { mutableStateOf(0) }

                        BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

                        when (screen) {
                            Screen.HOME -> HomeScreen(
                                browser = browser,
                                rootUri = s.rootFolderUri,
                                folderView = s.folderView,
                                onFolderViewChange = { scope.launch { repo.setFolderView(it) } },
                                onRootSelected = { uri -> scope.launch { repo.setRootFolder(uri.toString()) } },
                                onOpenPdf = { uri, name ->
                                    scope.launch {
                                        startPage = repo.getLastPage(uri.toString())
                                        pdfUri = uri.toString()
                                        pdfName = name
                                        screen = Screen.READER
                                    }
                                },
                                onOpenSettings = { screen = Screen.SETTINGS }
                            )

                            Screen.SETTINGS -> SettingsScreen(
                                settings = s,
                                onPageTurnChange = { scope.launch { repo.setPageTurnMode(it) } },
                                onViewModeChange = { scope.launch { repo.setViewMode(it) } },
                                onThemeChange = { scope.launch { repo.setThemeMode(it) } },
                                onBack = { screen = Screen.HOME }
                            )

                            Screen.READER -> pdfUri?.let { fileUri ->
                                ReaderScreen(
                                    uri = Uri.parse(fileUri),
                                    title = pdfName,
                                    pageTurnMode = s.pageTurnMode,
                                    viewMode = s.viewMode,
                                    initialPage = startPage,
                                    onPageSave = { page -> scope.launch { repo.setLastPage(fileUri, page) } },
                                    onBack = { screen = Screen.HOME }
                                )
                            }

                        }
                    }
                }
            }
        }
    }
}
