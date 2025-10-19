//package com.commcrete.aiaudio.activites
//
//import android.annotation.SuppressLint
//import android.os.Bundle
//import android.view.MotionEvent
//import android.widget.*
//import androidx.appcompat.app.AppCompatActivity
//import androidx.constraintlayout.widget.ConstraintLayout
//import androidx.core.content.ContextCompat
//import androidx.core.view.ViewCompat
//import androidx.core.view.WindowInsetsCompat
//import androidx.core.view.updatePadding
//import androidx.lifecycle.lifecycleScope
//import com.commcrete.aiaudio.R
//import com.commcrete.aiaudio.bluetooth.BleManager
//import com.commcrete.aiaudio.bluetooth.BleStatus
//import com.commcrete.stardust.ai.codec.PttSendManager
//import com.example.chunkrecorder.AudioRecorder
//import com.example.chunkrecorder.AudioRecorderAI
//import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.launch
//
//class PttModeActivity : AppCompatActivity() {
//
//    private val TAG = "PttModeActivity"
//
//    private lateinit var pttButton: Button
//    private lateinit var statusText: TextView
//    private lateinit var codecGroup: RadioGroup
//    private lateinit var rbTokenizer: RadioButton
//    private lateinit var rbCodec2: RadioButton
//    private lateinit var bleStatusText: TextView
//
//    private var isRecording = false
//    private var recorder: AudioRecorder? = null
//
//    private enum class CodecMode { TOKENIZER, CODEC2 }
//    private var currentCodec: CodecMode = CodecMode.TOKENIZER
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_ptt_mode)
//
//        pttButton = findViewById(R.id.pttButton)
//        statusText = findViewById(R.id.statusText)
//        codecGroup = findViewById(R.id.codecGroup)
//        rbTokenizer = findViewById(R.id.rbTokenizer)
//        rbCodec2 = findViewById(R.id.rbCodec2)
//        bleStatusText = findViewById(R.id.bleStatusText)
//
//        // Apply window insets so BLE status sits above system navigation bar
//        val root: ConstraintLayout = findViewById(R.id.rootPtt)
//        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
//            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            val initialBottom = v.tag as? Int ?: v.paddingBottom
//            v.tag = initialBottom
//            v.updatePadding(bottom = initialBottom + sysBars.bottom)
//            insets
//        }
//
//        codecGroup.setOnCheckedChangeListener { _, checkedId ->
//            currentCodec = if (checkedId == R.id.rbCodec2) CodecMode.CODEC2 else CodecMode.TOKENIZER
//            if (!isRecording) updateIdleStatus()
//            PttSendManager.isCodec2 = (currentCodec == CodecMode.CODEC2)
//        }
//
//        setupPttButton()
//        startBleStatusCollector()
//    }
//
//    @SuppressLint("ClickableViewAccessibility")
//    private fun setupPttButton() {
//        pttButton.setOnTouchListener { _, event ->
//            when (event.action) {
//                MotionEvent.ACTION_DOWN -> {
//                    if (!isRecording) {
//                        startRecording()
//                    }
//                    true
//                }
//                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
//                    if (isRecording) stopRecording()
//                    true
//                }
//                else -> false
//            }
//        }
//    }
//
//    private fun startRecording() {
//        isRecording = true
//        pttButton.text = "RECORDING..."
//        // Use circular recording background instead of setBackgroundColor to avoid losing oval shape
//        pttButton.setBackgroundResource(R.drawable.ptt_button_background_recording)
//        statusText.text = when (currentCodec) {
//            CodecMode.TOKENIZER -> "Tokenizer: streaming every 500 ms"
//            CodecMode.CODEC2 -> "Codec2: streaming every 880 ms"
//        }
//
//        // Build a new recorder per session with appropriate chunk duration
//        recorder = createRecorderForCurrentCodec().also { it.start() }
//
//        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
//    }
//
//    private fun stopRecording() {
//        isRecording = false
//        recorder?.stop()
//        recorder = null
//        pttButton.text = "HOLD TO TALK"
//        // Restore original circular idle background
//        pttButton.setBackgroundResource(R.drawable.ptt_button_background)
//        updateIdleStatus()
//        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
//    }
//
//    private fun updateIdleStatus() {
//        statusText.text = when (currentCodec) {
//            CodecMode.TOKENIZER -> "Tokenizer selected - hold to stream 500ms chunks"
//            CodecMode.CODEC2 -> "Codec2 selected - hold to stream 880ms chunks"
//        }
//    }
//
//    private fun createRecorderForCurrentCodec(): AudioRecorderAI {
//        val chunkMs = when (currentCodec) {
//            CodecMode.TOKENIZER -> 500L
//            CodecMode.CODEC2 -> 880L
//        }
//        return AudioRecorderAI(
//            filesDirProvider = { getExternalFilesDir("ptt_chunks") ?: filesDir },
//            chunkDurationMs = chunkMs,
//            context = this
//        ).apply {
//            onChunkReady = { pcmArray, index ->
//                PttSendManager.addNewFrame(pcmArray)
//            }
//            onPartialFinalChunk = { pcmArray, index ->
//                PttSendManager.addNewFrame(pcmArray)
//            }
//            onError = { t ->
//                t.printStackTrace()
//                runOnUiThread {
//                    Toast.makeText(this@PttModeActivity, "Recorder error: ${t.message}", Toast.LENGTH_LONG).show()
//                    if (isRecording) stopRecording()
//                }
//            }
//        }
//    }
//
//    private fun startBleStatusCollector() {
//        lifecycleScope.launch {
//            BleManager.connectionStatus.collectLatest { status ->
//                updateBleStatus(status)
//            }
//        }
//    }
//
//    private fun updateBleStatus(status: BleStatus) {
//        when (status) {
//            BleStatus.CONNECTED -> {
//                bleStatusText.text = "BLE: Connected"
//                bleStatusText.setBackgroundResource(R.drawable.bg_ble_status_connected)
//                bleStatusText.setTextColor(ContextCompat.getColor(this, R.color.ble_connected_text))
//            }
//            BleStatus.DISCONNECTED -> {
//                bleStatusText.text = "BLE: Disconnected"
//                bleStatusText.setBackgroundResource(R.drawable.bg_ble_status_disconnected)
//                bleStatusText.setTextColor(ContextCompat.getColor(this, R.color.ble_disconnected_text))
//            }
//            BleStatus.NO_REGISTERED -> {
//                bleStatusText.text = "BLE: Not Registered"
//                bleStatusText.setBackgroundResource(R.drawable.bg_ble_status_neutral)
//                bleStatusText.setTextColor(ContextCompat.getColor(this, R.color.ble_not_registered_text))
//            }
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        recorder?.stop()
//        recorder = null
//    }
//}
