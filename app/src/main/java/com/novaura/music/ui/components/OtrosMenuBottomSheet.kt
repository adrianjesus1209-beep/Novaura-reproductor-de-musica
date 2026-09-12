package com.novaura.music.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

    // Dark semi-transparent container color (Glassmorphism look: ~82% dark translucent opacity)
    val translucentDarkBg = Color(0xD6140D24)
    val scrimOverlayColor = Color.Black.copy(alpha = 0.45f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = translucentDarkBg,
        scrimColor = scrimOverlayColor,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
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
                color = Color.White,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White.copy(alpha = 0.15f)
            )

            // 1. Ordenado por
            OtrosMenuItem(
                icon = Icons.AutoMirrored.Filled.Sort,
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
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = Color.White.copy(alpha = 0.6f)
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

            Spacer(modifier = Modifier.height(20.dp))
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.25f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "iconScale"
    )

    val rotation by animateFloatAsState(
        targetValue = if (isPressed) 15f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "iconRotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isPressed) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(20.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
            modifier = Modifier.weight(1f)
        )

        if (trailing != null) {
            trailing()
        }
    }
}
