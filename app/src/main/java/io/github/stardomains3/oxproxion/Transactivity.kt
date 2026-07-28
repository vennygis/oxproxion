package io.github.stardomains3.oxproxion

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.media.ToneGenerator
import android.media.AudioManager
import android.view.KeyEvent // Added for key interception
import android.view.View
import androidx.core.view.isVisible
import kotlin.time.Duration.Companion.milliseconds


class Transactivity : AppCompatActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private var mediaRecorder: MediaRecorder? = null
    private var voiceRecordFile: File? = null
    private var recordStartMs = 0L
    private var isRecording = false

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var btnAction: Button
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceRecording() // Start recording once permission is granted
        } else {
            tvStatus.text = "Permission denied. Cannot transcribe."
            btnAction.text = "Retry"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcription)

        val vm: ChatViewModel by viewModels()

        // Initialize UI elements
        tvStatus = findViewById(R.id.tv_live_transcription)
        btnAction = findViewById(R.id.btn_action)
        btnAction.visibility = View.GONE
        // Set up the button listener for the "Stop" action
        btnAction.setOnClickListener {
            if (isRecording) {
                stopVoiceRecording()
            } else {
                // This part is technically redundant now since it starts automatically,
                // but good to keep for manual control if needed.
                startVoiceRecording()
            }
        }

        // START AUTOMATICALLY
        // We use post to ensure the view is fully drawn before we try to use the Mic
        window.decorView.postDelayed({
            startVoiceRecording()
        }, 300) // 500ms delay to ensure smooth transition
    }

    // Intercept volume keys to stop recording
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isRecording && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            stopVoiceRecording()
            return true // Consume the event so the system volume slider doesn't pop up
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startVoiceRecording() {
        // Check permissions first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // If permission is missing, we can't auto-start.
            // We must ask the user.
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 140)
        try {
            voiceRecordFile = File(cacheDir, "transcription_${System.currentTimeMillis()}.opus")

            mediaRecorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setOutputFile(voiceRecordFile!!.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                prepare()
                start()
            }

            isRecording = true
            tvStatus.text = "Listening..."
            btnAction.visibility = View.VISIBLE
            btnAction.text = "Stop / Transcribe"



        } catch (e: Exception) {
            //Log.e("Transactivity", "Failed to start recording", e)
            tvStatus.text = "Error starting mic"
            btnAction.text = "Retry"
            btnAction.isVisible = true
        }
    }


    private fun stopVoiceRecording() {
        val duration = System.currentTimeMillis() - recordStartMs
        val recorder = mediaRecorder

        mediaRecorder = null
        isRecording = false
        btnAction.visibility = View.GONE

        var stopSuccessful = true

        try {
            if (duration >= 400) {
                recorder?.stop()
            } else {
                stopSuccessful = false
            }
        } catch (e: Exception) {
            // Log removed for F-Droid version
            stopSuccessful = false
        } finally {
            // GUARANTEED RELEASE
            try {
                recorder?.release()
            } catch (e: Exception) {
                // Log removed for F-Droid version
            }
        }

        val file = voiceRecordFile
        voiceRecordFile = null

        if (stopSuccessful && duration >= 400 && file != null && file.exists() && file.length() > 1024) {
            processVoiceRecording(file)
        } else {
            file?.delete()
            if (duration < 400) {
                tvStatus.text = "Recording was too short"
            } else {
                tvStatus.text = "Error: Empty or corrupted recording"
            }
            btnAction.text = "Try Again"
            btnAction.visibility = View.VISIBLE
        }
    }


    private fun processVoiceRecording(file: File) {
        lifecycleScope.launch {
            try {
                tvStatus.text = "Transcribing..."

                val audioBytes = withContext(Dispatchers.IO) {
                    file.readBytes()
                }

                val transcribedText = viewModel.transcribeAudioForInput(
                    audioBytes = audioBytes,
                    audioFormat = "opus",
                    fileName = file.name
                )

                if (!transcribedText.isNullOrBlank()) {
                    // 1. Show the result in the UI
                    tvStatus.text = transcribedText

                    // 2. Copy to clipboard
                    copyToClipboard(transcribedText)

                    // 3. HIDE THE BUTTON IMMEDIATELY
                    // This prevents the user from tapping "Stop" while the app is closing
                    btnAction.visibility = View.GONE

                    // 4. Wait for the user to actually see the text before closing
                    kotlinx.coroutines.delay(1400.milliseconds)
                    finish()
                } else {
                    tvStatus.text = "Could not understand audio."
                    btnAction.text = "Try Again"
                    btnAction.isVisible = true
                    btnAction.isEnabled = true // Re-enable so they can try again
                }
            } catch (e: Exception) {
                //Log.e("Transactivity", "Transcription error", e)
                tvStatus.text = "Error: ${e.message}"
                btnAction.text = "Try Again"
                btnAction.isVisible = true
                btnAction.isEnabled = true // Re-enable so they can try again
            } finally {
                file.delete()
                voiceRecordFile = null
            }
        }
    }



    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Transcribed Text", text)
        clipboard.setPrimaryClip(clip)
        // Note: Android 13+ shows its own system toast for clipboard,
        // so we don't need to add one manually.
    }
    override fun onPause() {
        super.onPause()
        if (isRecording) {
            // Stop recording and release mic if app goes to background
            try {
                mediaRecorder?.stop()
            } catch (_: Exception) {}
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            isRecording = false
        }
    }
}