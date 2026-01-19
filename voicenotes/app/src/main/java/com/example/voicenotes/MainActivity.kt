package com.example.voicenotes

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.voicenotes.service.AudioRecordService
import com.example.voicenotes.ui.DashboardScreen
import com.example.voicenotes.ui.RecordDetailScreen
import com.example.voicenotes.viewmodel.MainViewModel
import com.example.voicenotes.viewmodel.MeetingViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Microphone permission is required to record audio.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val ACTION_OPEN_RECORD = "com.example.voicenotes.action.OPEN_RECORD"
    }

    private var latestIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)

        latestIntent = intent

        setContent {
            MaterialTheme {
                Surface {
                    val navController = rememberNavController()

                    LaunchedEffect(latestIntent) {
                        val i = latestIntent ?: return@LaunchedEffect
                        handleIntent(navController, i)
                    }

                    NavHost(navController = navController, startDestination = "dashboard") {
                        composable("dashboard") {
                            val mainViewModel: MainViewModel = hiltViewModel()
                            DashboardScreen(mainViewModel, navController)
                        }

                        composable(
                            route = "record/{meetingId}",
                            arguments = listOf(navArgument("meetingId") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val meetingId = backStackEntry.arguments?.getInt("meetingId") ?: -1
                            val meetingViewModel: MeetingViewModel = hiltViewModel(backStackEntry)

                            RecordDetailScreen(
                                meetingId = meetingId,
                                viewModel = meetingViewModel,
                                navController = navController
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        latestIntent = intent
    }

    private fun handleIntent(navController: NavHostController, intent: Intent) {
        if (intent.action != ACTION_OPEN_RECORD) return

        val meetingId = intent.getIntExtra(AudioRecordService.EXTRA_MEETING_ID, -1)
        if (meetingId == -1) return

        navController.navigate("record/$meetingId") {
            launchSingleTop = true
            restoreState = true
        }
    }
}
