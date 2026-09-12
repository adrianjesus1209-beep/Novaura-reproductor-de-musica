package com.novaura.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novaura.music.navigation.HomeTab
import com.novaura.music.ui.theme.icon

private val ItemMinWidth = 72.dp

/**
 * Barra de navegación inferior transparente/translúcida estilo cristal gris neutral.
 */
@Composable
fun BottomNavBar(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = Color.Black.copy(alpha = 0.30f),
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.12f),
                thickness = 1.dp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeTab.entries.forEach { tab ->
                    BottomNavItem(
                        tab = tab,
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(tab) }
                    )
                }
            }
        }
    }

    LaunchedEffect(selectedTab) {
        val itemWidthPx = with(density) { ItemMinWidth.toPx() }
        scrollState.animateScrollTo((selectedTab.ordinal * itemWidthPx).toInt())
    }
}

@Composable
private fun BottomNavItem(
    tab: HomeTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    val activeColor = Color.White
    val inactiveColor = Color.White.copy(alpha = 0.55f)
    val contentColor by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "navItemColor"
    )
    val iconSize by animateDpAsState(
        targetValue = if (selected) 25.dp else 21.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "navIconSize"
    )

    Column(
        modifier = Modifier
            .widthIn(min = ItemMinWidth)
            .height(64.dp)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pastilla de fondo para ícono activo
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 50.dp, height = 28.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (selected) Color.White.copy(alpha = 0.22f)
                    else Color.Transparent
                )
        ) {
            Icon(
                imageVector = tab.icon(),
                contentDescription = stringResource(tab.labelRes),
                tint = contentColor,
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(tab.labelRes),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}