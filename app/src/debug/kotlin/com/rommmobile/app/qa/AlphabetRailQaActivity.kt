package com.rommmobile.app.qa

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.ThemeMode
import com.rommmobile.app.feature.library.AlphabetRail
import com.rommmobile.app.feature.library.LetterEntry

/** Debug-only visual/interaction fixture: production rail, deterministic data, no server login. */
class AlphabetRailQaActivity : ComponentActivity() {
    private var selected by mutableIntStateOf(0)
    private var releases by mutableIntStateOf(0)
    private var letters = emptyList<LetterEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val labels = listOf("#") + ('A'..'Z').map(Char::toString) + "@"
        val dataset = intent.getStringExtra("dataset") ?: "full"
        letters = if (dataset == "empty") emptyList() else labels.mapIndexed { i, label ->
            LetterEntry(label, if (dataset == "sparse" && i % 3 == 1) null else i)
        }
        enableEdgeToEdge()
        setContent {
            RommTheme(ThemeMode.DARK) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    Box(Modifier.fillMaxWidth().height(56.dp).padding(12.dp)) {
                        Text("Alphabet rail QA ($dataset)", fontSize = 18.sp)
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.weight(1f).padding(16.dp)) {
                            Text("Selected: ${letters.getOrNull(selected)?.label ?: "none"}", fontSize = 24.sp)
                            Text("Releases: $releases")
                            Text("L1/R1: previous/next enabled letter")
                            Text("${RommTheme.layout.widthDp} x ${RommTheme.layout.heightDp} dp")
                        }
                        AlphabetRail(
                            letters = letters,
                            current = letters.getOrNull(selected)?.label,
                            onJump = { entry -> selected = letters.indexOf(entry) },
                            onRelease = { releases++ },
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp)) {
                        Text("Debug fixture - no network data")
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> -1
            KeyEvent.KEYCODE_BUTTON_R1 -> 1
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val candidates = if (direction > 0) (selected + 1)..letters.lastIndex else (selected - 1) downTo 0
            candidates.firstOrNull { letters[it].offset != null }?.let { selected = it }
        }
        return true
    }
}
