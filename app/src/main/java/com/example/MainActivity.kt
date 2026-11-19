package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.CameraViewModel
import com.example.ui.screens.CameraScreen
import com.example.ui.screens.GithubDocsScreen
import com.example.ui.screens.LabEditorScreen
import com.example.ui.theme.KadrRawTheme

const val ROUTE_CAMERA = "camera"
const val ROUTE_LAB = "lab_editor"
const val ROUTE_DOCS = "github_docs"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            KadrRawTheme {
                val navController = rememberNavController()
                val cameraViewModel: CameraViewModel = viewModel()

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_CAMERA
                ) {
                    composable(ROUTE_CAMERA) {
                        CameraScreen(
                            viewModel = cameraViewModel,
                            onNavigateToLab = { navController.navigate(ROUTE_LAB) },
                            onNavigateToDocs = { navController.navigate(ROUTE_DOCS) }
                        )
                    }
                    composable(ROUTE_LAB) {
                        LabEditorScreen(
                            viewModel = cameraViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(ROUTE_DOCS) {
                        GithubDocsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
