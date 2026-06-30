package com.mushafqiyam

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MushafQiyam"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "=== onCreate started ===")
        Log.i(TAG, "App Version: 3.0.0 (Harness)")
        Log.i(TAG, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        Log.i(TAG, "Android API: ${android.os.Build.VERSION.SDK_INT}")

        try {
            setContent {
                Log.i(TAG, "setContent composing...")
                MushafQiyamTheme {
                    HarnessScreen()
                }
            }
            Log.i(TAG, "setContent completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: setContent failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
    }
}

@Composable
fun MushafQiyamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFF4D03F),
            onPrimary = Color(0xFF0D3B66),
            surface = Color(0xFF0D3B66),
            onSurface = Color(0xFFF5F5F5),
            background = Color(0xFF0A2744),
            onBackground = Color(0xFFF5F5F5),
        ),
        content = content
    )
}

@Composable
fun HarnessScreen() {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "مصحف القيام",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "الإصدار 3.0.0 — البنية التحتية",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "حالة النظام",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))

                StatusRow(label = "واجهة المستخدم (Compose)", status = "✅ يعمل")
                StatusRow(label = "نموذج التعرف الصوتي", status = "⏳ لم يُضَف بعد")
                StatusRow(label = "كاشف الصوت (VAD)", status = "⏳ لم يُضَف بعد")
                StatusRow(label = "التقاط الصوت (Mic)", status = "⏳ لم يُفعَّل بعد")
                StatusRow(label = "مطابقة الآيات (Matcher)", status = "✅ جاهز")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Test Verses Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "نموذج عرض الآيات",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))

                val testVerses = listOf(
                    "ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَٰلَمِينَ ﴿٢﴾",
                    "ٱلرَّحْمَٰنِ ٱلرَّحِيمِ ﴿٣﴾",
                    "مَٰلِكِ يَوْمِ ٱلدِّينِ ﴿٤﴾",
                    "إِيَّاكَ نَعْبُدُ وَإِيَّاكَ نَسْتَعِينُ ﴿٥﴾",
                    "ٱهْدِنَا ٱلصِّرَٰطَ ٱلْمُسْتَقِيمَ ﴿٦﴾",
                    "صِرَٰطَ ٱلَّذِينَ أَنْعَمْتَ عَلَيْهِمْ غَيْرِ ٱلْمَغْضُوبِ عَلَيْهِمْ وَلَا ٱلضَّآلِّينَ ﴿٧﴾"
                )

                testVerses.forEach { verse ->
                    Text(
                        text = verse,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "هذه نسخة البنية التحتية فقط.\nنموذج التعرف الصوتي سيُضاف في تحديث لاحق.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun StatusRow(label: String, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = status,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
