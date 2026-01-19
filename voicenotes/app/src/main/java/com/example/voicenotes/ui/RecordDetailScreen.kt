package com.example.voicenotes.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.voicenotes.service.AudioRecordService
import com.example.voicenotes.viewmodel.MeetingViewModel
import com.example.voicenotes.viewmodel.MeetingViewModel.SummaryState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetailScreen(
    meetingId: Int,
    viewModel: MeetingViewModel,
    navController: NavController
) {
    val timeFmt = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    val meetingState by viewModel.meeting.collectAsState()
    val transcripts by viewModel.transcripts.collectAsState()
    val summaryState by viewModel.summaryState.collectAsState()
    val context = LocalContext.current

    val internetAvailable by rememberInternetAvailable(context)

    val summaryGenerated = !meetingState?.summary.isNullOrBlank()

    // Tabs
    var selectedTab by remember { mutableStateOf(0) }

    // Service state from broadcasts
    var recordingStatus by remember { mutableStateOf(AudioRecordService.STATUS_RECORDING) }
    var warningText by remember { mutableStateOf<String?>(null) }
    var inputSource by remember { mutableStateOf("Built-in mic") }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var stopClicked by remember { mutableStateOf(false) }

    var summarizeAfterStop: Boolean = false


    // If service says stopped -> also consider stop clicked
    LaunchedEffect(recordingStatus) {
        if (recordingStatus == AudioRecordService.STATUS_STOPPED) {
            stopClicked = true
            selectedTab = 1
        }
    }

    // UI timer (ticks only while actively "Recording")
    var seconds by remember { mutableStateOf(0) }
    val shouldTick = !summaryGenerated && recordingStatus == AudioRecordService.STATUS_RECORDING

    LaunchedEffect(shouldTick) {
        if (shouldTick) {
            while (shouldTick) {
                delay(1000)
                seconds++
            }
        }
    }

    // Show snackbar for low storage stop
    LaunchedEffect(warningText) {
        val w = warningText ?: return@LaunchedEffect
        if (w == AudioRecordService.WARNING_LOW_STORAGE_STOPPED) {
            snackbarHostState.showSnackbar(message = w, withDismissAction = true)
        }
    }

    // Listen to service broadcasts (receiver flag safe)
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != AudioRecordService.ACTION_RECORDING_STATUS) return

                recordingStatus = intent.getStringExtra(AudioRecordService.EXTRA_STATUS)
                    ?: AudioRecordService.STATUS_RECORDING

                warningText = intent.getStringExtra(AudioRecordService.EXTRA_WARNING)
                inputSource = intent.getStringExtra(AudioRecordService.EXTRA_INPUT_SOURCE) ?: inputSource
            }
        }

        val filter = IntentFilter(AudioRecordService.ACTION_RECORDING_STATUS)

        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (!summaryGenerated) {
                        when (recordingStatus) {
                            AudioRecordService.STATUS_PAUSED_PHONE_CALL -> Text("Paused - Phone call")
                            AudioRecordService.STATUS_PAUSED_AUDIO_FOCUS -> Text("Paused - Audio focus lost")
                            AudioRecordService.STATUS_STOPPED -> Text("Recording stopped")
                            else -> Text(
                                text = "Listening and taking notes...",
                                color = Color(0xFF0C4F75),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(meetingState?.title ?: "Meeting Notes")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!summaryGenerated) {
                        if (recordingStatus == AudioRecordService.STATUS_RECORDING) {
                            Text(
                                text = String.format("%02d:%02d", seconds / 60, seconds % 60),
                                color = Color.Red
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("●", color = Color.Red)
                            Spacer(Modifier.width(12.dp))
                        } else {
                            Text("Paused", color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                }
            )
        },
        bottomBar = {
            val showStop =
                !stopClicked &&
                        !summaryGenerated &&
                        (summaryState == SummaryState.Idle || summaryState == SummaryState.Loading)

            if (showStop) {
                Button(
                    onClick = {
                        // Hide button + jump to summary immediately
                        stopClicked = true
                        selectedTab = 1

                        // Stop service
                        val stopIntent = Intent(context, AudioRecordService::class.java).apply {
                            action = AudioRecordService.ACTION_STOP
                        }
                        context.startService(stopIntent)

                        // Trigger summary generation (only if online)
                        if (summaryState == SummaryState.Idle) {
                            if (internetAvailable) {
                                viewModel.generateSummary()
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = "No internet connection. Connect to generate summary.",
                                        withDismissAction = true
                                    )
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0C4F75),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) { Text("Stop") }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // Input source
            if (!summaryGenerated) {
                Text(
                    text = "Input: $inputSource",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Offline banner (recording screen)
            if (!summaryGenerated && !internetAvailable) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = "No internet connection",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Warning banner from service (silence / low storage)
            if (!warningText.isNullOrBlank() && !summaryGenerated) {
                val isLowStorage = warningText == AudioRecordService.WARNING_LOW_STORAGE_STOPPED
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isLowStorage)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text = warningText!!,
                        modifier = Modifier.padding(12.dp),
                        color = if (isLowStorage)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    selectedContentColor = Color(0xFF0C4F75),
                    unselectedContentColor = Color(0xFF0C4F75),
                    text = { Text("Transcript") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    selectedContentColor = Color(0xFF0C4F75),
                    unselectedContentColor = Color(0xFF0C4F75),
                    text = { Text("Summary") }
                )
            }

            when (selectedTab) {
                0 -> {
                    if (transcripts.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Live Transcript", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Your conversation will appear here with a 30-second delay.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            if (!summaryGenerated && recordingStatus != AudioRecordService.STATUS_RECORDING) {
                                Spacer(Modifier.height(12.dp))
                                Text(recordingStatus, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(transcripts) { t ->
                                Column {
                                    Text(
                                        text = timeFmt.format(Date(t.createdAtMillis)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = t.text, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(thickness = 1.dp, color = Color.LightGray)
                                }
                            }
                        }
                    }
                }

                1 -> {
                    val shouldShowGenerating = stopClicked && !summaryGenerated && internetAvailable

                    // Offline block only if summary not generated yet
                    if (!internetAvailable && !summaryGenerated) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "No internet connection",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "Connect to the internet to generate summary.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            if (internetAvailable) viewModel.generateSummary()
                                            else scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    message = "Still offline. Please connect to the internet.",
                                                    withDismissAction = true
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF0C4F75),
                                            contentColor = Color.White
                                        )
                                    ) { Text("Retry") }
                                }
                            }
                        }
                    } else if (!stopClicked && !summaryGenerated) {
                        // BEFORE stop: placeholder
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Summary", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Your AI summary will appear here after you hit Stop.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (shouldShowGenerating || summaryState is SummaryState.Loading || summaryState is SummaryState.Idle) {
                        // AFTER stop: loading
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text("Generating summary...", modifier = Modifier.padding(top = 8.dp))
                        }
                    } else {
                        when (summaryState) {
                            is SummaryState.Error -> {
                                val msg = (summaryState as SummaryState.Error).message ?: "Unknown error"
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Failed to generate summary.", color = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.height(6.dp))
                                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { viewModel.generateSummary() }) { Text("Retry") }
                                }
                            }

                            is SummaryState.Success -> {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(meetingState?.title ?: "Summary", style = MaterialTheme.typography.headlineSmall)
                                    Text(meetingState?.summary.orEmpty(), style = MaterialTheme.typography.bodyMedium)

                                    Text("Action Items", style = MaterialTheme.typography.titleMedium)
                                    val actions = meetingState?.actionItems.orEmpty().trim()
                                    if (actions.isNotEmpty()) {
                                        actions.split("\n").filter { it.isNotBlank() }.forEach { item ->
                                            Text("• $item", modifier = Modifier.padding(start = 8.dp))
                                        }
                                    } else {
                                        Text("None", modifier = Modifier.padding(start = 8.dp))
                                    }

                                    Text("Key Points", style = MaterialTheme.typography.titleMedium)
                                    val points = meetingState?.keyPoints.orEmpty().trim()
                                    if (points.isNotEmpty()) {
                                        points.split("\n").filter { it.isNotBlank() }.forEach { point ->
                                            Text("• $point", modifier = Modifier.padding(start = 8.dp))
                                        }
                                    } else {
                                        Text("None", modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            }

                            else -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Text("Generating summary...", modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun rememberInternetAvailable(context: Context): State<Boolean> {
    val cm = remember {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    fun hasInternetNow(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    val state = remember { mutableStateOf(hasInternetNow()) }

    DisposableEffect(cm) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = hasInternetNow() }
            override fun onLost(network: Network) { state.value = hasInternetNow() }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                state.value = hasInternetNow()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, callback)

        onDispose {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }

    return state
}
