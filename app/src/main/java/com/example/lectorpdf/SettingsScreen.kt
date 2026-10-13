package com.example.lectorpdf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onViewModeChange: (ViewMode) -> Unit,
    onDirectionChange: (ReadingDirection) -> Unit,
    onStripPositionChange: (StripPosition) -> Unit,
    onPageTurnChange: (PageTurnMode) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            OptionGroup(
                title = "Visualización",
                options = listOf(
                    ViewMode.SINGLE to "Una página",
                    ViewMode.DOUBLE to "Dos páginas (libro abierto)"
                ),
                selected = settings.viewMode,
                onSelect = onViewModeChange
            )
            Spacer(Modifier.height(24.dp))
            OptionGroup(
                title = "Animación de paso de página",
                options = listOf(
                    PageTurnMode.CORNER to "Desde la esquina (con doblez)",
                    PageTurnMode.SLIDE to "Deslizar la página (sin doblez)"
                ),
                selected = settings.pageTurnMode,
                onSelect = onPageTurnChange
            )
            OptionGroup(
                title = "Dirección de lectura",
                options = listOf(
                    ReadingDirection.LTR to "De izquierda a derecha (predeterminado)",
                    ReadingDirection.RTL to "De derecha a izquierda (manga)"
                ),
                selected = settings.readingDirection,
                onSelect = onDirectionChange
            )
            Spacer(Modifier.height(24.dp))
            OptionGroup(
                title = "Barra de páginas",
                options = listOf(
                    StripPosition.BOTTOM to "Abajo (predeterminado)",
                    StripPosition.TOP to "Arriba"
                ),
                selected = settings.stripPosition,
                onSelect = onStripPositionChange
            )
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.height(24.dp))
            OptionGroup(
                title = "Tema",
                options = listOf(
                    ThemeMode.SYSTEM to "Según el sistema (predeterminado)",
                    ThemeMode.LIGHT to "Claro",
                    ThemeMode.DARK to "Oscuro"
                ),
                selected = settings.themeMode,
                onSelect = onThemeChange
            )
            // Para agregar más opciones: otro OptionGroup (o Switch/Slider) aquí.
        }
    }
}

@Composable
private fun <T> OptionGroup(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    options.forEach { (value, label) ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    role = Role.RadioButton
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = value == selected, onClick = null)
            Text(label, Modifier.padding(start = 12.dp))
        }
    }
}
