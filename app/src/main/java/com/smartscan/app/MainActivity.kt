package com.smartscan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartscan.app.ui.AdjustScreen
import com.smartscan.app.ui.CameraScreen
import com.smartscan.app.ui.CropScreen
import com.smartscan.app.ui.EditorScreen
import com.smartscan.app.ui.HomeScreen
import com.smartscan.app.ui.SmartScanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SmartScanTheme { AppNavigation() } }
    }
}

@Composable
private fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpen = { nav.navigate("editor/$it") },
                onScan = { folderId ->
                    nav.navigate(if (folderId == null) "camera/new" else "camera/new?folder=$folderId")
                },
            )
        }
        composable(
            route = "camera/{docId}?folder={folder}",
            arguments = listOf(
                navArgument("docId") { type = NavType.StringType },
                navArgument("folder") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val arg = entry.arguments?.getString("docId")
            CameraScreen(
                initialDocId = arg?.takeIf { it != "new" },
                folderId = entry.arguments?.getString("folder"),
                onFinished = { docId, cropPageId ->
                    nav.navigate("editor/$docId") { popUpTo("home") }
                    if (cropPageId != null) nav.navigate("crop/$docId/$cropPageId")
                },
                onCancel = { nav.popBackStack() },
            )
        }
        composable("editor/{docId}") { entry ->
            val docId = entry.arguments?.getString("docId") ?: return@composable
            EditorScreen(
                docId = docId,
                onBack = { nav.popBackStack("home", inclusive = false) },
                onCrop = { pageId -> nav.navigate("crop/$docId/$pageId") },
                onAdjust = { pageId -> nav.navigate("adjust/$docId/$pageId") },
                onAddPages = { nav.navigate("camera/$docId") },
            )
        }
        composable("adjust/{docId}/{pageId}") { entry ->
            val docId = entry.arguments?.getString("docId") ?: return@composable
            val pageId = entry.arguments?.getString("pageId") ?: return@composable
            AdjustScreen(docId = docId, pageId = pageId, onDone = { nav.popBackStack() })
        }
        composable("crop/{docId}/{pageId}") { entry ->
            val docId = entry.arguments?.getString("docId") ?: return@composable
            val pageId = entry.arguments?.getString("pageId") ?: return@composable
            CropScreen(docId = docId, pageId = pageId, onDone = { nav.popBackStack() })
        }
    }
}
