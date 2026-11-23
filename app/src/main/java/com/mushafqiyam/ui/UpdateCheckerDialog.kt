package com.mushafqiyam.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mushafqiyam.update.UpdateCheckResult

@Composable
fun UpdateCheckerDialog(
    state: UpdateCheckResult,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    if (state is UpdateCheckResult.Idle) return

    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (state) {
                        is UpdateCheckResult.Checking -> {
                            CircularProgressIndicator(
                                color = Color(0xFF0F5132),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "جاري التحقق من وجود تحديث...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "الاتصال بخادم الإصدارات على GitHub...",
                                fontSize = 13.sp,
                                color = Color(0xFF64748B),
                                textAlign = TextAlign.Center
                            )
                        }

                        is UpdateCheckResult.UpToDate -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF0F5132),
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "أنت تستخدم أحدث إصدار",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "الإصدار المثبت (${state.currentVersion}) هو أحدث إصدار متوفر حالياً.",
                                fontSize = 13.sp,
                                color = Color(0xFF64748B),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F5132)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(0.5f)
                            ) {
                                Text("حسناً", fontWeight = FontWeight.Bold)
                            }
                        }

                        is UpdateCheckResult.UpdateAvailable -> {
                            val rel = state.release
                            Text(
                                text = "يتوفر تحديث جديد للتطبيق 🎉",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Version badges
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFFE8F5E9), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "v${state.currentVersion}",
                                    fontSize = 13.sp,
                                    color = Color(0xFF455A64)
                                )
                                Text(
                                    text = "  ←  ",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F5132)
                                )
                                Text(
                                    text = "v${rel.versionString}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F5132)
                                )
                            }

                            if (rel.selectedAsset != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                val sizeMb = "%.1f".format(rel.selectedAsset.sizeBytes / (1024.0 * 1024.0))
                                val typeLabel = if (rel.selectedAsset.isLite) "ترقية خفيفة" else "نسخة كاملة"
                                Text(
                                    text = "حجم التحديث: $sizeMb ميجابايت ($typeLabel)",
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B)
                                )
                            }

                            if (rel.body.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 160.dp)
                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = rel.body.trim(),
                                        fontSize = 12.sp,
                                        color = Color(0xFF334155),
                                        lineHeight = 18.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // Primary Action: Download APK
                            Button(
                                onClick = {
                                    val dlUrl = rel.selectedAsset?.downloadUrl ?: rel.htmlUrl
                                    openUrlSafely(context, dlUrl)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F5132)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("تحميل التحديث الآن", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Secondary Action: Release page on GitHub
                            TextButton(
                                onClick = {
                                    openUrlSafely(context, rel.htmlUrl)
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("عرض صفحة الإصدار على GitHub", fontSize = 13.sp, color = Color(0xFF0F5132))
                            }
                        }

                        is UpdateCheckResult.Error -> {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFC62828),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "تعذر التحقق من التحديثات",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.message,
                                fontSize = 13.sp,
                                color = Color(0xFF64748B),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(18.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onRetry,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("إعادة المحاولة", fontSize = 13.sp)
                                }

                                Button(
                                    onClick = {
                                        openUrlSafely(context, state.releasesPageUrl)
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F5132)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("صفحة الإصدارات", fontSize = 13.sp)
                                }
                            }
                        }

                        is UpdateCheckResult.Idle -> {}
                    }
                }
            }
        }
    }
}

private fun openUrlSafely(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "لم يتم العثور على متصفح لفتح الرابط", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "تعذر فتح الرابط: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
