package com.mushafqiyam.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mushafqiyam.MushafPageRepository

private val BgColor = Color(0xFFF7F6F2)
private val PrimaryEmerald = Color(0xFF0F5132)
private val DarkSlate = Color(0xFF1E293B)
private val GoldAccent = Color(0xFFB45309)
private val CardBg = Color(0xFFFFFFFF)

@Composable
fun WelcomeScreen(
    lastPage: Int,
    lastSurah: Int,
    lastAyah: Int,
    appVersion: String,
    onStartRecitation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBookmarks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val surahName = MushafPageRepository.getSurahName(lastSurah)

    val scrollState = rememberScrollState()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(BgColor)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 500.dp)
                    .verticalScroll(scrollState)
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // App Branding Icon & Title
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = PrimaryEmerald,
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "مصحف القيام",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkSlate,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "متابعة التلاوة وتظليل الآيات وتقليب الصفحات آلياً بالصوت دون لمس الجهاز",
                    fontSize = 14.sp,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Last Read Bookmark Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = null,
                                tint = GoldAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "موضع القراءة الأخير",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = GoldAccent
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "$surahName — الآية $lastAyah",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkSlate
                        )

                        Text(
                            text = "صفحة $lastPage من مصحف المدينة",
                            fontSize = 13.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Row 1: Primary Action: "بدء التلاوة"
                Button(
                    onClick = onStartRecitation,
                    modifier = Modifier
                        .fillMaxWidth(0.68f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryEmerald),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "بدء التلاوة",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Row 2: Secondary Actions: "البحث" (Right) & "المرجعيات" (Left)
                Row(
                    modifier = Modifier.fillMaxWidth(0.88f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenSearch,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, PrimaryEmerald),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryEmerald),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = PrimaryEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "البحث",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryEmerald,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenBookmarks,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, PrimaryEmerald),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryEmerald),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = PrimaryEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "المرجعيات",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryEmerald,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Row 3: Tertiary Action: "الإعدادات" (Centered)
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, PrimaryEmerald),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryEmerald),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = PrimaryEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "الإعدادات",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryEmerald,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Version Indicator
                Text(
                    text = "الإصدار: $appVersion",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}
