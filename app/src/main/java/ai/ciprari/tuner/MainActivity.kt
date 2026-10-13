package ai.ciprari.tuner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ai.ciprari.tuner.player.PlayerHolder
import ai.ciprari.tuner.ui.App
import ai.ciprari.tuner.ui.TunerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TunerTheme { App() } }
    }

    override fun onStart() {
        super.onStart()
        PlayerHolder.onAppForeground()
    }

    override fun onStop() {
        PlayerHolder.onAppBackground()
        super.onStop()
    }
}
