package de.foldki.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.foldki.app.ui.ChatApp
import de.foldki.app.ui.FoldTheme

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        de.foldki.app.data.DebugLog.init(applicationContext)
        // Always the dark design: light status/navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { FoldTheme { ChatApp(vm) } }
    }

    /** In the background or low on memory: free the local model (it loads again with the next question). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) vm.onBackground()
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) vm.onLowMemory()
    }
}
