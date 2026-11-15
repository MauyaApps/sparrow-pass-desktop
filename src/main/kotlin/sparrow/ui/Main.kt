package sparrow.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import sparrow.vault.Prefs
import sparrow.vault.VaultData
import sparrow.vault.VaultSession

const val AUTOLOCK_MS = 15 * 60_000L // lock after 15 minutes without activity

class AppState {
    var session by mutableStateOf<VaultSession?>(null)
    var data by mutableStateOf(VaultData())
    var message by mutableStateOf<String?>(null)
    var lastActive by mutableLongStateOf(System.currentTimeMillis())

    fun touch() { lastActive = System.currentTimeMillis() }

    fun open(s: VaultSession) { session = s; data = s.data; touch() }

    /** Replace the data and write the encrypted file. */
    fun update(newData: VaultData) {
        data = newData
        session?.let { it.data = newData; it.save() }
    }

    fun lock() { session?.close(); session = null; data = VaultData() }
}

private val HEX = Regex("#?[0-9a-fA-F]{6}")
fun isHex(s: String) = HEX.matches(s.trim())
fun parseColor(hex: String, fallback: Color): Color =
    if (isHex(hex)) Color(0xFF000000.toInt() or hex.trim().removePrefix("#").toInt(16)) else fallback

@Composable
fun SparrowTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val accent = parseColor(prefs.accent, Color(0xFF3DDC84))
    val bg = parseColor(prefs.background, Color(0xFF06110A))
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = if (accent.luminance() > 0.5f) Color.Black else Color.White,
            background = bg,
            onBackground = Color(0xFFD9F5E4),
            surface = lerp(bg, accent, 0.07f),
            onSurface = Color(0xFFD9F5E4),
            surfaceVariant = lerp(bg, accent, 0.15f),
            onSurfaceVariant = Color(0xFF9CC4AC),
            secondaryContainer = lerp(bg, accent, 0.28f),
            onSecondaryContainer = Color(0xFFD9F5E4),
            outline = lerp(bg, accent, 0.40f),
        ),
        content = content,
    )
}

fun main() = application {
    val state = remember { AppState() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Sparrow",
        state = rememberWindowState(width = 1150.dp, height = 740.dp),
        onKeyEvent = { state.touch(); false },
    ) {
        SparrowTheme(state.data.prefs) {
            Surface(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            state.touch()
                        }
                    }
                },
                color = MaterialTheme.colorScheme.background,
            ) {
                LaunchedEffect(state.session) {
                    while (state.session != null) {
                        delay(10_000)
                        if (System.currentTimeMillis() - state.lastActive > AUTOLOCK_MS) state.lock()
                    }
                }
                if (state.session == null) UnlockScreen(state) else MainScreen(state)
            }
        }
    }
}
