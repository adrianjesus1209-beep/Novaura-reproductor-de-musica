package com.novaura.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtrosMenuBottomSheet(
    isDarkMode: Boolean,
    onSortClick: () -> Unit,
    onDarkModeToggle: () -> Unit,
    onThemesClick: () -> Unit,
    onCutsClick: () -> Unit,
    onHiddenListClick: () -> Unit,
    onRemoveAdsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDeveloperAppsClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Otros",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )

            // 1. Ordenado por
            OtrosMenuItem(
                icon = Icons.Default.Sort,
                title = "Ordenado por",
                onClick = {
                    onDismiss()
                    onSortClick()
                }
            )

            // 2. Modo oscuro
            OtrosMenuItem(
                icon = Icons.Default.DarkMode,
                title = "Modo oscuro",
                trailing = {
                    RadioButton(
                        selected = isDarkMode,
                        onClick = onDarkModeToggle,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                },
                onClick = onDarkModeToggle
            )

            // 3. Temas
            OtrosMenuItem(
                icon = Icons.Default.Checkroom,
                title = "Temas",
                onClick = {
                    onDismiss()
                    onThemesClick()
                }
            )

            // 4. Lista de cortes
            OtrosMenuItem(
                icon = Icons.Default.ContentCut,
                title = "Lista de cortes",
                onClick = {
                    onDismiss()
                    onCutsClick()
                }
            )

            // 5. Lista oculta
            OtrosMenuItem(
                icon = Icons.Default.VisibilityOff,
                title = "Lista oculta",
                onClick = {
                    onDismiss()
                    onHiddenListClick()
                }
            )

            // 6. Eliminar anuncios
            OtrosMenuItem(
                icon = Icons.Default.Block,
                title = "Eliminar anuncios",
                onClick = {
                    onDismiss()
                    onRemoveAdsClick()
                }
            )

            // 7. Configuración
            OtrosMenuItem(
                icon = Icons.Default.Settings,
                title = "Configuración",
                onClick = {
                    onDismiss()
                    onSettingsClick()
                }
            )

            // 8. Más aplicaciones de este desarrollador
            OtrosMenuItem(
                icon = Icons.Default.Apps,
                title = "Más aplicaciones de este desarrollador",
                onClick = {
                    onDismiss()
                    onDeveloperAppsClick()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OtrosMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            trailing()
        }
    }
}
