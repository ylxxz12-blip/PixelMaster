package com.example.pixelmaster

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.pixelmaster.ui.batch.BatchScreen
import com.example.pixelmaster.ui.editor.EditorScreen
import com.example.pixelmaster.ui.home.HomeScreen
import com.example.pixelmaster.ui.theme.PixelMasterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelMasterTheme {
                PixelMasterApp()
            }
        }
    }
}

private sealed interface AppScreen {
    data object Home : AppScreen
    data class Editor(val uri: Uri) : AppScreen
    data class Batch(val uris: List<Uri>) : AppScreen
}

@Composable
fun PixelMasterApp() {
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val s = screen) {
            AppScreen.Home -> HomeScreen(
                onImagePicked = { screen = AppScreen.Editor(it) },
                onBatchPicked = { screen = AppScreen.Batch(it) },
            )

            is AppScreen.Editor -> EditorScreen(uri = s.uri, onExit = { screen = AppScreen.Home })

            is AppScreen.Batch -> BatchScreen(uris = s.uris, onExit = { screen = AppScreen.Home })
        }
    }
}
