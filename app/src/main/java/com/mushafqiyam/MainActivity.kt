package com.mushafqiyam

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var engine: MushafEngine
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        engine = MushafEngine()
        extractAssetsAndInitEngine()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isEngineReady by remember { mutableStateOf(false) }
                    // UI Code...
                    MushafScreen(
                        isEngineReady = isEngineReady,
                        onStartRecording = {
                            val intent = Intent(this, AudioCaptureService::class.java)
                            intent.action = AudioCaptureService.ACTION_START
                            startService(intent)
                        },
                        onStopRecording = {
                            val intent = Intent(this, AudioCaptureService::class.java)
                            intent.action = AudioCaptureService.ACTION_STOP
                            startService(intent)
                        }
                    )
                }
            }
        }
    }

    private fun extractAssetsAndInitEngine() {
        CoroutineScope(Dispatchers.IO).launch {
            val encoderPath = copyAsset("encoder_model.onnx")
            val decoderPath = copyAsset("decoder_model_merged.onnx")
            val vadPath = copyAsset("silero_vad.onnx")
            val tokenizerPath = copyAsset("tokenizer.json")
            
            withContext(Dispatchers.Main) {
                // We'd update a state here to show UI is ready
                // engine.initEngine(encoderPath, decoderPath, vadPath)
            }
        }
    }

    private fun copyAsset(filename: String): String {
        val file = File(filesDir, filename)
        if (!file.exists()) {
            try {
                assets.open(filename).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return ""
            }
        }
        return file.absolutePath
    }
}

@Composable
fun MushafScreen(isEngineReady: Boolean, onStartRecording: () -> Unit, onStopRecording: () -> Unit) {
    var isRecording by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ",
            fontSize = 32.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ (2) الرَّحْمَٰنِ الرَّحِيمِ (3) مَالِكِ يَوْمِ الدِّينِ (4)",
            fontSize = 24.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = {
                if (isRecording) {
                    onStopRecording()
                } else {
                    onStartRecording()
                }
                isRecording = !isRecording
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isRecording) "إيقاف الميكروفون" else "بدء الاستماع والتتبع")
        }
    }
}
