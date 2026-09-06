package com.novaura.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.novaura.music.navigation.AppNavigation
import com.novaura.music.ui.theme.NovauraTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NovauraTheme {
                AppNavigation()
            }
        }
    }
}