package com.example.voicenotes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.voicenotes.MainActivity
import com.example.voicenotes.data.repository.MeetingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import androidx.work.*
import com.example.voicenotes.worker.SummaryWorker
import java.util.concurrent.TimeUnit



@AndroidEntryPoint
class AudioRecordService : Service() {

    @Inject lateinit var repository: MeetingRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mediaRecorder: MediaRecorder? = null
    private var chunkIndex: Int = 1
    private var meetingId: Int = -1

    private var isRunning: Boolean = false
    private var isActuallyRecording: Boolean = false
    private var pauseReason: PauseReason = PauseReason.NONE



    // IMPORTANT: Bump channel id to force Android to re-create channel with lockscreenVisibility
    private val CHANNEL_ID = "RecordingChannel_v3"
    private val NOTIF_ID = 1

    // Call state
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    // Audio focus
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.w(TAG, "Audio focus lost: $change")
                onAudioFocusLost()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                onAudioFocusGained()
            }
        }
    }

    // Timer + warnings
    private var elapsedSeconds: Int = 0
    private var timerJob: Job? = null

    private var silentSeconds: Int = 0
    private var warningMessage: String? = null
    private var warningActive: Boolean = false

    // Source tracking
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentInputLabel: String = "Built-in mic"
    private var btConnected: Boolean = false
    private var scoOn: Boolean = false
    private var wiredPlugged: Boolean = false

    private var lastSourceFlash: String? = null
    private var clearFlashRunnable: Runnable? = null

    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var headsetPlugReceiver: BroadcastReceiver? = null
    private var btReceiver: BroadcastReceiver? = null

    private var summarizeAfterStop: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        registerCallStateListener()
        registerAudioDeviceCallbacks()
        registerHeadsetPlugReceiver()
        registerBluetoothReceivers()

        // push initial status (helps UI and debugging)
        refreshInputAndNotify(eventFlash = "Service started")


    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")

        unregisterCallStateListener()
        abandonAudioFocus()

        unregisterAudioDeviceCallbacks()
        unregisterHeadsetPlugReceiver()
        unregisterBluetoothReceivers()

        timerJob?.cancel()
        serviceScope.coroutineContext.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand(action=${intent?.action}, startId=$startId)")
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received")
                stopRecording(userInitiated = true, summarize = false)
                return START_NOT_STICKY
            }
            ACTION_STOP_AND_SUMMARIZE -> {
                stopRecording(userInitiated = true, summarize = false)
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                Log.d(TAG, "ACTION_PAUSE received")
                userPause()
                return START_STICKY
            }
            ACTION_RESUME -> {
                Log.d(TAG, "ACTION_RESUME received")
                userResume()
                return START_STICKY
            }
        }

        meetingId = intent.getIntExtra(EXTRA_MEETING_ID, -1)
        if (meetingId == -1) {
            Log.e(TAG, "Missing meetingId; stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Low storage check BEFORE starting
        if (!hasEnoughStorageToStart()) {
            warningMessage = WARNING_LOW_STORAGE_STOPPED
            warningActive = true
            isRunning = false
            isActuallyRecording = false
            pauseReason = PauseReason.NONE

            Log.w(TAG, "Low storage BEFORE start. freeBytes=${freeBytes()}")
            startForeground(NOTIF_ID, buildNotification(currentStatus(), 0, warningMessage))
            logNotif("startForeground(low-storage-before-start)", currentStatus(), 0, warningMessage)
            broadcastStatus(currentStatus(), warningMessage, currentInputLabel)
            stopSelf()
            return START_NOT_STICKY
        }

        // Request audio focus
        val focusOk = requestAudioFocus()
        if (!focusOk) {
            isRunning = true
            isActuallyRecording = false
            pauseReason = PauseReason.AUDIO_FOCUS

            Log.w(TAG, "Audio focus NOT granted. Showing paused notification.")
            startForeground(NOTIF_ID, buildNotification(currentStatus(), 0, null))
            logNotif("startForeground(focus-not-granted)", currentStatus(), 0, null)
            refreshInputAndNotify(eventFlash = "Audio focus lost")
            startNotificationTimer()
            return START_STICKY
        }

        isRunning = true
        isActuallyRecording = true
        pauseReason = PauseReason.NONE

        elapsedSeconds = 0
        silentSeconds = 0
        warningMessage = null
        warningActive = false

        startForeground(NOTIF_ID, buildNotification(currentStatus(), 0, null))
        logNotif("startForeground(recording-start)", currentStatus(), 0, null)

        beginRecordingNewChunk()
        startNotificationTimer()

        refreshInputAndNotify(eventFlash = "Recording started")
        return START_STICKY
    }

    /** Start a new chunk. */
    private fun beginRecordingNewChunk() {
        try {
            if (!hasEnoughStorageToContinue()) {
                Log.w(TAG, "Low storage before chunk start. freeBytes=${freeBytes()}")
                stopDueToLowStorage()
                return
            }

            mediaRecorder?.release()
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)

                val outputDir = File(applicationContext.filesDir, "recordings").apply { mkdirs() }
                val chunkFile = File(outputDir, "meeting_${meetingId}_chunk$chunkIndex.m4a")
                setOutputFile(chunkFile.absolutePath)

                setMaxDuration(CHUNK_DURATION_MS)
                setOnInfoListener { mr, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.d(TAG, "Chunk $chunkIndex reached 30s -> rotate")

                        try { mr.stop() } catch (e: Exception) { Log.w(TAG, "mr.stop() failed: ${e.message}") }
                        try { mr.release() } catch (_: Exception) {}

                        val finishedIdx = chunkIndex
                        val finishedPath = chunkFilePath(finishedIdx)

                        serviceScope.launch {
                            try {
                                repository.transcribeAudioChunk(meetingId, finishedPath, finishedIdx)
                            } catch (e: Exception) {
                                Log.e(TAG, "Transcription failed for chunk $finishedIdx: ${e.message}")
                            }
                        }

                        if (!hasEnoughStorageToContinue()) {
                            Log.w(TAG, "Low storage before next chunk. freeBytes=${freeBytes()}")
                            stopDueToLowStorage()
                            return@setOnInfoListener
                        }

                        chunkIndex++
                        beginRecordingNewChunk()

                        if (!isActuallyRecording && pauseReason != PauseReason.NONE) safelyPauseRecorder()
                    }
                }

                prepare()
                start()
            }

            Log.d(TAG, "Recorder started. meetingId=$meetingId chunkIndex=$chunkIndex")
            if (!isActuallyRecording && pauseReason != PauseReason.NONE) safelyPauseRecorder()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recorder: ${e.message}", e)
            isRunning = false
            isActuallyRecording = false
            stopSelf()
        }
    }

    // -------------------- Phone calls --------------------

    private fun onPhoneCallStartedOrRinging() {
        if (!isRunning) return
        if (pauseReason == PauseReason.PHONE_CALL) return

        Log.d(TAG, "Phone call started/ringing -> pause")
        pauseReason = PauseReason.PHONE_CALL
        if (isActuallyRecording) {
            isActuallyRecording = false
            safelyPauseRecorder()
        }
        refreshInputAndNotify(eventFlash = "Paused - Phone call")
        updateNotificationStatus(currentStatus())
    }

    private fun onPhoneCallEnded() {
        if (!isRunning) return
        if (pauseReason != PauseReason.PHONE_CALL) return

        Log.d(TAG, "Phone call ended -> try resume (unless user paused)")
        val focusOk = requestAudioFocus()
        if (!focusOk) {
            pauseReason = PauseReason.AUDIO_FOCUS
            isActuallyRecording = false
            refreshInputAndNotify(eventFlash = "Audio focus lost")
            updateNotificationStatus(currentStatus())
            return
        }

        pauseReason = PauseReason.NONE
        isActuallyRecording = true
        try { mediaRecorder?.resume() } catch (_: Exception) {}
        refreshInputAndNotify(eventFlash = "Resumed")
        updateNotificationStatus(currentStatus())
    }

    // -------------------- User Pause/Resume (Lock screen actions) --------------------

    private fun userPause() {
        if (!isRunning) return
        if (pauseReason == PauseReason.PHONE_CALL) return
        if (!isActuallyRecording) return

        Log.d(TAG, "User pause")
        pauseReason = PauseReason.USER
        isActuallyRecording = false
        safelyPauseRecorder()

        refreshInputAndNotify(eventFlash = "Paused")
        updateNotificationStatus(currentStatus())
    }

    private fun userResume() {
        if (!isRunning) return
        if (pauseReason == PauseReason.PHONE_CALL) return

        Log.d(TAG, "User resume")
        val focusOk = requestAudioFocus()
        if (!focusOk) {
            pauseReason = PauseReason.AUDIO_FOCUS
            isActuallyRecording = false
            refreshInputAndNotify(eventFlash = "Audio focus lost")
            updateNotificationStatus(currentStatus())
            return
        }

        pauseReason = PauseReason.NONE
        isActuallyRecording = true
        try { mediaRecorder?.resume() } catch (_: Exception) {}
        refreshInputAndNotify(eventFlash = "Resumed")
        updateNotificationStatus(currentStatus())
    }

    // -------------------- Audio focus --------------------

    private fun onAudioFocusLost() {
        if (!isRunning) return
        if (pauseReason == PauseReason.PHONE_CALL) return

        Log.d(TAG, "Pause due to audio focus loss")
        pauseReason = PauseReason.AUDIO_FOCUS
        if (isActuallyRecording) {
            isActuallyRecording = false
            safelyPauseRecorder()
        }

        refreshInputAndNotify(eventFlash = "Paused - Audio focus lost")
        updateNotificationStatus(currentStatus())
    }

    private fun onAudioFocusGained() {
        if (!isRunning) return
        if (pauseReason != PauseReason.AUDIO_FOCUS) return

        Log.d(TAG, "Auto-resume due to audio focus gain")
        pauseReason = PauseReason.NONE
        isActuallyRecording = true
        try { mediaRecorder?.resume() } catch (_: Exception) {}
        refreshInputAndNotify(eventFlash = "Resumed")
        updateNotificationStatus(currentStatus())
    }

    private fun safelyPauseRecorder() {
        try { mediaRecorder?.pause() } catch (e: Exception) {
            Log.w(TAG, "pause() failed: ${e.message}")
        }
    }

    // -------------------- Stop / low storage --------------------

    private fun stopRecording(userInitiated: Boolean, summarize: Boolean) {
        Log.d(TAG, "stopRecording(userInitiated=$userInitiated, summarize=$summarize)")

        if (!isRunning && userInitiated) {
            stopSelf()
            return
        }

        isRunning = false
        isActuallyRecording = false
        pauseReason = PauseReason.NONE
        timerJob?.cancel()
        abandonAudioFocus()

        try { mediaRecorder?.stop() } catch (e: Exception) { Log.w(TAG, "stop() failed: ${e.message}") }
        mediaRecorder?.release()
        mediaRecorder = null

        // Show stopped notification immediately (optional)
        startForeground(NOTIF_ID, buildNotification(STATUS_STOPPED, elapsedSeconds, warningMessage))

        val finishedPath = chunkFilePath(chunkIndex)

        serviceScope.launch {
            // 1) Final transcription (only if file exists)
            try {
                val f = File(finishedPath)
                if (f.exists() && f.length() > 0) {
                    Log.d(TAG, "Final chunk transcription start path=$finishedPath size=${f.length()}")
                    repository.transcribeAudioChunk(meetingId, finishedPath, chunkIndex)
                    Log.d(TAG, "Final chunk transcription done")
                } else {
                    Log.w(TAG, "Final chunk missing/empty. exists=${f.exists()} size=${f.length()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Final chunk transcription failed: ${e.message}", e)
            }

            // 2) Enqueue summary if requested
            if (summarize) {
                try {
                    Log.d(TAG, "Enqueue summary in 800ms meetingId=$meetingId")
                    delay(800)
                    enqueueSummaryWork(meetingId)
                    Log.d(TAG, "enqueueSummaryWork OK meetingId=$meetingId")
                } catch (e: Exception) {
                    Log.e(TAG, "enqueueSummaryWork failed: ${e.message}", e)
                }
            }

            // 3) NOW stop service safely
            stopSelf()
        }
    }


    private fun stopAndSummarize() {
        Log.d(TAG, "stopAndSummarize() meetingId=$meetingId")
        summarizeAfterStop = true
        stopRecording(userInitiated = true, summarize = false)
    }
    private fun enqueueSummaryWork(meetingId: Int) {
        Log.d(TAG, "Enqueue summary worker meetingId=$meetingId")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setInputData(workDataOf(SummaryWorker.KEY_MEETING_ID to meetingId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                "summary_$meetingId",
                ExistingWorkPolicy.REPLACE,
                work
            )
    }


    private fun stopDueToLowStorage() {
        Log.w(TAG, "stopDueToLowStorage() freeBytes=${freeBytes()}")
        warningMessage = WARNING_LOW_STORAGE_STOPPED
        warningActive = true

        isRunning = false
        isActuallyRecording = false
        pauseReason = PauseReason.NONE
        timerJob?.cancel()

        try { mediaRecorder?.stop() } catch (_: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null

        broadcastStatus(currentStatus(), warningMessage, currentInputLabel)
        startForeground(NOTIF_ID, buildNotification(currentStatus(), elapsedSeconds, warningMessage))
        logNotif("startForeground(low-storage-stopped)", currentStatus(), elapsedSeconds, warningMessage)
        stopSelf()
    }

    // -------------------- Timer + silence + lock screen updates --------------------

    private fun startNotificationTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isRunning) {
                delay(1000)

                // Stop if storage runs out while recording
                if (isActuallyRecording && !hasEnoughStorageToContinue()) {
                    stopDueToLowStorage()
                    return@launch
                }

                // Timer pauses when recording paused
                if (isActuallyRecording) elapsedSeconds++

                // Silent detection while actively recording
                if (isActuallyRecording) {
                    val amp = try { mediaRecorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                    val silent = amp <= SILENCE_AMPLITUDE_THRESHOLD
                    if (silent) {
                        silentSeconds++
                        if (!warningActive && silentSeconds >= SILENCE_SECONDS_TO_WARN) {
                            warningActive = true
                            warningMessage = WARNING_NO_AUDIO
                            Log.w(TAG, "Silent audio detected for ${silentSeconds}s -> warning shown")
                            broadcastStatus(currentStatus(), warningMessage, currentInputLabel)
                        }
                    } else {
                        silentSeconds = 0
                        if (warningActive && warningMessage == WARNING_NO_AUDIO) {
                            warningActive = false
                            warningMessage = null
                            Log.d(TAG, "Audio input back -> warning cleared")
                            broadcastStatus(currentStatus(), null, currentInputLabel)
                        }
                    }
                } else {
                    silentSeconds = 0
                }

                // Foreground notif updates every second (this is the lockscreen live status)
                val status = currentStatus()
                startForeground(NOTIF_ID, buildNotification(status, elapsedSeconds, warningMessage))
                logNotif("tick", status, elapsedSeconds, warningMessage)
            }
        }
    }

    private fun currentStatus(): String {
        return when {
            !isRunning -> STATUS_STOPPED
            pauseReason == PauseReason.PHONE_CALL -> STATUS_PAUSED_PHONE_CALL
            pauseReason == PauseReason.AUDIO_FOCUS -> STATUS_PAUSED_AUDIO_FOCUS
            pauseReason == PauseReason.USER -> STATUS_PAUSED_USER
            else -> STATUS_RECORDING
        }
    }

    private fun logNotif(tagSuffix: String, status: String, sec: Int, warning: String?) {
        // Keep logs readable: every tick prints one line
        Log.d(
            TAG,
            "NOTIF[$tagSuffix] status=$status t=${fmt(sec)} " +
                    "lockscreen=PUBLIC input='$currentInputLabel' " +
                    "flash='${lastSourceFlash ?: ""}' warning='${warning ?: ""}'"
        )
    }

    // -------------------- Notification (lock screen: timer + status + actions + icon) --------------------

    private fun buildNotification(status: String, elapsedSec: Int, warning: String?): Notification {
        val title = when (status) {
            STATUS_PAUSED_PHONE_CALL -> "Paused - Phone call"
            STATUS_PAUSED_AUDIO_FOCUS -> "Paused - Audio focus lost"
            STATUS_PAUSED_USER -> "Paused"
            STATUS_STOPPED -> "Recording stopped"
            STATUS_RESUMED -> "Resumed"
            else -> "Recording"
        }


        val baseLine = when (status) {
            STATUS_PAUSED_PHONE_CALL -> "Paused - Phone call  ${fmt(elapsedSec)}"
            STATUS_PAUSED_AUDIO_FOCUS -> "Paused - Audio focus lost  ${fmt(elapsedSec)}"
            STATUS_PAUSED_USER -> "Paused  ${fmt(elapsedSec)}"
            STATUS_STOPPED -> "Stopped  ${fmt(elapsedSec)}"
            STATUS_RESUMED -> "Resumed  ${fmt(elapsedSec)}"
            else -> "Recording...  ${fmt(elapsedSec)}"
        }

        val inputLine = "Input: $currentInputLabel"
        val flashLine = lastSourceFlash?.let { "Source: $it" }
        val warnLine = warning?.let { "Warning: $it" }

        val bigText = buildString {
            append(baseLine)
            append("\n")
            append(inputLine)
            flashLine?.let { append("\n"); append(it) }
            warnLine?.let { append("\n"); append(it) }
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_RECORD
            putExtra(AudioRecordService.EXTRA_MEETING_ID, meetingId)

            // ✅ Prevent new instance / reset
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )


        val stopIntent = Intent(this, AudioRecordService::class.java).apply {
            action = ACTION_STOP_AND_SUMMARIZE
        }

        val stopPending = PendingIntent.getService(
            this,
            100,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )


        val pauseIntent = Intent(this, AudioRecordService::class.java).apply { action = ACTION_PAUSE }
        val pausePending = PendingIntent.getService(this, 2, pauseIntent, PendingIntent.FLAG_IMMUTABLE)

        val resumeIntent = Intent(this, AudioRecordService::class.java).apply { action = ACTION_RESUME }
        val resumePending = PendingIntent.getService(this, 3, resumeIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(baseLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            // Visual indicator (recording icon)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(isRunning && status != STATUS_STOPPED)
            .setOnlyAlertOnce(true)
            // ✅ must be public to show timer/status on lock screen
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Lock screen actions
        when (status) {
            STATUS_RECORDING -> {
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePending)
                builder.addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            }
            STATUS_PAUSED_USER, STATUS_PAUSED_AUDIO_FOCUS -> {
                builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePending)
                builder.addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            }
            STATUS_PAUSED_PHONE_CALL -> {
                builder.addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            }
        }

        return builder.build()

    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for audio recording in progress"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID lockscreen=PUBLIC importance=LOW")
        }
    }

    private fun fmt(sec: Int) = "%02d:%02d".format(sec / 60, sec % 60)

    // -------------------- Storage helpers --------------------

    private fun freeBytes(): Long {
        val stat = StatFs(applicationContext.filesDir.absolutePath)
        return stat.availableBytes
    }

    private fun hasEnoughStorageToStart(): Boolean = freeBytes() >= MIN_FREE_BYTES_TO_START
    private fun hasEnoughStorageToContinue(): Boolean = freeBytes() >= MIN_FREE_BYTES_TO_CONTINUE

    // -------------------- Audio focus helpers --------------------

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .setAcceptsDelayedFocusGain(false)
                .build()
            focusRequest = req
            val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.d(TAG, "requestAudioFocus(O+) granted=$granted")
            granted
        } else {
            @Suppress("DEPRECATION")
            val granted = am.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.d(TAG, "requestAudioFocus(pre-O) granted=$granted")
            granted
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
            Log.d(TAG, "abandonAudioFocus(O+)")
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusChangeListener)
            Log.d(TAG, "abandonAudioFocus(pre-O)")
        }
    }

    // -------------------- Wired + Bluetooth detection --------------------

    private fun registerAudioDeviceCallbacks() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                Log.d(TAG, "AudioDevicesAdded: ${addedDevices.joinToString { it.type.toString() }}")
                refreshInputAndNotify(eventFlash = "Audio device connected")
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                Log.d(TAG, "AudioDevicesRemoved: ${removedDevices.joinToString { it.type.toString() }}")
                refreshInputAndNotify(eventFlash = "Audio device disconnected")
            }
        }
        audioDeviceCallback = cb
        am.registerAudioDeviceCallback(cb, mainHandler)
        Log.d(TAG, "AudioDeviceCallback registered")
    }

    private fun unregisterAudioDeviceCallbacks() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        audioDeviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
        Log.d(TAG, "AudioDeviceCallback unregistered")
    }

    private fun registerHeadsetPlugReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_HEADSET_PLUG) return
                val state = intent.getIntExtra("state", -1) // 0 unplug, 1 plug
                wiredPlugged = (state == 1)
                Log.d(TAG, "ACTION_HEADSET_PLUG state=$state wiredPlugged=$wiredPlugged")
                refreshInputAndNotify(eventFlash = if (wiredPlugged) "Wired headset plugged" else "Wired headset unplugged")
            }
        }
        headsetPlugReceiver = r

        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(r, filter)
        }
        Log.d(TAG, "Headset plug receiver registered")
    }

    private fun unregisterHeadsetPlugReceiver() {
        headsetPlugReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        headsetPlugReceiver = null
        Log.d(TAG, "Headset plug receiver unregistered")
    }

    private fun registerBluetoothReceivers() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        btConnected = (state == BluetoothProfile.STATE_CONNECTED)
                        Log.d(TAG, "BT_HEADSET_CONNECTION_STATE_CHANGED state=$state btConnected=$btConnected")
                        refreshInputAndNotify(eventFlash = if (btConnected) "Bluetooth headset connected" else "Bluetooth headset disconnected")
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        Log.d(TAG, "BT_ACL_CONNECTED")
                        refreshInputAndNotify(eventFlash = "Bluetooth device connected")
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        Log.d(TAG, "BT_ACL_DISCONNECTED")
                        btConnected = false
                        scoOn = false
                        refreshInputAndNotify(eventFlash = "Bluetooth device disconnected")
                    }
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                        val newSco = (state == AudioManager.SCO_AUDIO_STATE_CONNECTED)
                        if (newSco != scoOn) {
                            scoOn = newSco
                            Log.d(TAG, "SCO_STATE_UPDATED scoOn=$scoOn")
                            refreshInputAndNotify(eventFlash = if (scoOn) "Bluetooth SCO ON" else "Bluetooth SCO OFF")
                        } else {
                            refreshInputAndNotify(eventFlash = null)
                        }
                    }
                }
            }
        }
        btReceiver = r

        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(r, filter)
        }
        Log.d(TAG, "Bluetooth receivers registered")
    }

    private fun unregisterBluetoothReceivers() {
        btReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        btReceiver = null
        Log.d(TAG, "Bluetooth receivers unregistered")
    }

    private fun refreshInputAndNotify(eventFlash: String?) {
        currentInputLabel = computeInputLabel()

        if (!eventFlash.isNullOrBlank()) {
            setFlash(eventFlash)
        }

        broadcastStatus(currentStatus(), warningMessage, currentInputLabel)
        updateNotificationStatus(currentStatus())

        Log.d(TAG, "refreshInputAndNotify status=${currentStatus()} input='$currentInputLabel' flash='${eventFlash ?: ""}'")
    }

    private fun computeInputLabel(): String {
        val am = audioManager
        if (scoOn || (am?.isBluetoothScoOn == true)) return "Bluetooth mic (SCO)"
        if (btConnected) return "Built-in mic (Bluetooth connected)"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am != null) {
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val wiredMic = inputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            val usbMic = inputs.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            val builtinMic = inputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

            return when {
                wiredMic -> "Wired headset mic"
                usbMic -> "USB mic"
                wiredPlugged -> "Wired headphones (mic unknown)"
                builtinMic -> "Built-in mic"
                else -> "Built-in mic"
            }
        }
        return if (wiredPlugged) "Wired headphones (mic unknown)" else "Built-in mic"
    }

    private fun setFlash(msg: String) {
        lastSourceFlash = msg

        clearFlashRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            lastSourceFlash = null
            updateNotificationStatus(currentStatus())
        }
        clearFlashRunnable = r
        mainHandler.postDelayed(r, 4000)
    }

    private fun updateNotificationStatus(status: String) {
        startForeground(NOTIF_ID, buildNotification(status, elapsedSeconds, warningMessage))
    }

    // -------------------- Broadcast to UI --------------------

    private fun broadcastStatus(status: String, warning: String?, inputSource: String) {
        val i = Intent(ACTION_RECORDING_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MEETING_ID, meetingId)
            putExtra(EXTRA_WARNING, warning)
            putExtra(EXTRA_INPUT_SOURCE, inputSource)
        }
        sendBroadcast(i)
    }

    // -------------------- Phone call listener --------------------

    private fun registerCallStateListener() {
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING,
                        TelephonyManager.CALL_STATE_OFFHOOK -> onPhoneCallStartedOrRinging()
                        TelephonyManager.CALL_STATE_IDLE -> onPhoneCallEnded()
                    }
                }
            }
            telephonyCallback = cb
            try { telephonyManager?.registerTelephonyCallback(mainExecutor, cb) }
            catch (_: SecurityException) {}
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING,
                        TelephonyManager.CALL_STATE_OFFHOOK -> onPhoneCallStartedOrRinging()
                        TelephonyManager.CALL_STATE_IDLE -> onPhoneCallEnded()
                    }
                }
            }
            phoneStateListener = listener
            try {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            } catch (_: SecurityException) {}
        }
    }

    private fun unregisterCallStateListener() {
        val tm = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { try { tm.unregisterTelephonyCallback(it) } catch (_: Exception) {} }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                @Suppress("DEPRECATION")
                tm.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            phoneStateListener = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private enum class PauseReason { NONE, PHONE_CALL, AUDIO_FOCUS, USER }

    private fun chunkFilePath(index: Int): String {
        val outputDir = File(applicationContext.filesDir, "recordings")
        return File(outputDir, "meeting_${meetingId}_chunk$index.m4a").absolutePath
    }

    companion object {
        private const val TAG = "AudioRecordService"

        const val ACTION_STOP = "com.example.voicenotes.ACTION_STOP_RECORDING"
        const val ACTION_PAUSE = "com.example.voicenotes.ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME = "com.example.voicenotes.ACTION_RESUME_RECORDING"
        const val EXTRA_MEETING_ID = "meeting_id"

        private const val CHUNK_DURATION_MS = 30000

        const val ACTION_STOP_AND_SUMMARIZE = "com.example.voicenotes.ACTION_STOP_AND_SUMMARIZE"



        // Status broadcast
        const val ACTION_RECORDING_STATUS = "com.example.voicenotes.RECORDING_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_WARNING = "warning"
        const val EXTRA_INPUT_SOURCE = "input_source"

        // Status values
        const val STATUS_RECORDING = "Recording"
        const val STATUS_PAUSED_PHONE_CALL = "Paused - Phone call"
        const val STATUS_PAUSED_AUDIO_FOCUS = "Paused - Audio focus lost"
        const val STATUS_PAUSED_USER = "Paused"
        const val STATUS_RESUMED = "Resumed"
        const val STATUS_STOPPED = "Stopped"

        // Silent detection
        private const val SILENCE_SECONDS_TO_WARN = 10
        private const val SILENCE_AMPLITUDE_THRESHOLD = 500
        const val WARNING_NO_AUDIO = "No audio detected - Check microphone"

        // Low storage thresholds
        private const val MIN_FREE_BYTES_TO_START: Long = 50L * 1024 * 1024   // 50MB
        private const val MIN_FREE_BYTES_TO_CONTINUE: Long = 10L * 1024 * 1024 // 10MB
        const val WARNING_LOW_STORAGE_STOPPED = "Recording stopped - Low storage"
    }
}
