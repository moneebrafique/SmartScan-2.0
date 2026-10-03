package com.paypeico.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.paypeico.chat.data.ChatRepo
import com.paypeico.chat.data.Me
import com.paypeico.chat.data.Participant
import com.paypeico.chat.data.SessionStore
import com.paypeico.chat.ui.ChatScreen
import com.paypeico.chat.ui.LoginScreen
import com.paypeico.chat.ui.ParticipantsScreen
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App()
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4CF0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E5FF),
    onPrimaryContainer = Color(0xFF1E1366),
    secondary = Color(0xFF8B5CF6),
    background = Color(0xFFF5F5FB),
    onBackground = Color(0xFF16172B),
    surface = Color.White,
    onSurface = Color(0xFF16172B),
    surfaceVariant = Color(0xFFECECF5),
    onSurfaceVariant = Color(0xFF62647A),
    outline = Color(0xFFD3D4E2),
    outlineVariant = Color(0xFFE6E6EF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8B7FFF),
    onPrimary = Color(0xFF140B5C),
    primaryContainer = Color(0xFF2E2873),
    onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFFB39DFF),
    background = Color(0xFF0E0F1D),
    onBackground = Color(0xFFE6E6F2),
    surface = Color(0xFF15172A),
    onSurface = Color(0xFFE6E6F2),
    surfaceVariant = Color(0xFF23263E),
    onSurfaceVariant = Color(0xFFA9ACC6),
    outline = Color(0xFF3A3D58),
    outlineVariant = Color(0xFF262940),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var me by remember { mutableStateOf(SessionStore.load(ctx)) }
    var chatWith by remember { mutableStateOf<Participant?>(null) }

    val current: Me? = me
    if (current == null) {
        LoginScreen(onLoggedIn = { loggedIn ->
            SessionStore.save(ctx, loggedIn)
            me = loggedIn
        })
        return
    }

    // If the account was deactivated or deleted on the web, log out.
    LaunchedEffect(current) {
        try {
            if (!ChatRepo.revalidate(current)) {
                SessionStore.clear(ctx)
                chatWith = null
                me = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No connection right now; keep the session and try again next launch.
        }
    }

    val other = chatWith
    if (other == null) {
        ParticipantsScreen(
            me = current,
            onOpen = { chatWith = it },
            onLogout = {
                SessionStore.clear(ctx)
                me = null
            },
        )
    } else {
        BackHandler { chatWith = null }
        ChatScreen(me = current, other = other, onBack = { chatWith = null })
    }
}
