package com.example.familytree

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val viewModel: FamilyViewModel by viewModels()
    private val settings by lazy { AppSettings(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = when (settings.theme) {
                "day" -> false
                "night" -> true
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    FamilyTreeScreen(viewModel, settings)
                }
            }
        }
    }
}
