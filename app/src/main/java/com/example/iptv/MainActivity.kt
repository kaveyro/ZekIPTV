package com.example.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.iptv.ui.theme.IptvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Auf dem TV soll die Oberfläche unabhängig von der Systemeinstellung dunkel sein.
            IptvTheme(darkTheme = true, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    IptvApp()
                }
            }
        }
    }
}

@Composable
fun IptvApp(mainViewModel: MainViewModel = viewModel()) {
    val selectedChannel by mainViewModel.selectedChannel

    val channel = selectedChannel
    if (channel == null) {
        MainScreen(mainViewModel)
    } else {
        VideoPlayer(
            channel = channel,
            onBack = { mainViewModel.deselectChannel() },
            onZap = { delta -> mainViewModel.zapChannel(delta) }
        )
    }
}
