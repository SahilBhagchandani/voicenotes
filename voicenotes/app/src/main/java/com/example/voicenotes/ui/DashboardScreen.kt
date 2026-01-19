package com.example.voicenotes.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.voicenotes.service.AudioRecordService
import com.example.voicenotes.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.voicenotes.R


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    val meetingsList by viewModel.meetings.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        val newId = viewModel.createNewMeeting()

                        val intent = Intent(context, AudioRecordService::class.java).apply {
                            putExtra(AudioRecordService.EXTRA_MEETING_ID, newId)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }

                        navController.navigate("record/$newId")
                    }
                },
                containerColor = Color(0xFF0C4F75),
                contentColor = Color.White,
                text = {
                    Text(
                        text = "Capture Notes",
                        color = Color.White
                    )
                },
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_mic),
                        contentDescription = "Mic",
                        tint = Color.White
                    )
                }
            )
        }
    )
    { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(
                text = "Welcome back, do you want me to capture notes for you",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp),
                color = Color(0xFF0C4F75),
                fontWeight = FontWeight.Bold
            )

//            if (meetingsList.isEmpty()) {
//                Text("No notes yet. Tap 'Capture Notes' to start a new meeting.")
//            } else {
//                LazyColumn(
//                    verticalArrangement = Arrangement.spacedBy(8.dp)
//                ) {
//                    items(meetingsList) { meeting ->
//                        val title = meeting.title ?: formatDate(meeting.startTime)
//
//                        Card(
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .clickable { navController.navigate("record/${meeting.id}") }
//                        ) {
//                            Column(modifier = Modifier.padding(16.dp)) {
//                                Text(title, style = MaterialTheme.typography.titleMedium)
//                                Spacer(Modifier.height(4.dp))
//                                Text(
//                                    formatDate(meeting.startTime),
//                                    style = MaterialTheme.typography.bodySmall
//                                )
//                            }
//                        }
//                    }
//                }
//            }
        }
    }
}

fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
