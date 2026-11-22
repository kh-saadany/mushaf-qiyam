package com.mushafqiyam.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mushafqiyam.MushafPageRepository
import com.mushafqiyam.data.BookmarkRecord
import com.mushafqiyam.data.BookmarkRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val BgColor = Color(0xFFF7F6F2)
private val PrimaryEmerald = Color(0xFF0F5132)
private val DarkSlate = Color(0xFF1E293B)
private val GoldAccent = Color(0xFFB45309)
private val CardBg = Color(0xFFFFFFFF)
private val MutedText = Color(0xFF64748B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    savedBookmark: Triple<Int, Int, Int>,
    onNavigateBack: () -> Unit,
    onContinueRecitation: () -> Unit,
    onSelectBookmark: (page: Int, surah: Int, ayah: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val bookmarks by BookmarkRepository.bookmarks.collectAsState()
    val surahName = MushafPageRepository.getSurahName(savedBookmark.second)

    LaunchedEffect(Unit) {
        BookmarkRepository.loadAll(context)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "المرجعيات",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = DarkSlate
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = "رجوع",
                                tint = DarkSlate
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
                )
            },
            containerColor = BgColor
        ) { paddingValues ->
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Section 1: Auto-Resume "موضع القراءة الأخير" Quick Card
                item(key = "last_read_card") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = null,
                                    tint = GoldAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "موضع القراءة الأخير",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GoldAccent
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "$surahName — الآية ${savedBookmark.third}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkSlate
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = "صفحة ${savedBookmark.first} من مصحف المدينة",
                                fontSize = 13.sp,
                                color = MutedText
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = onContinueRecitation,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryEmerald)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "متابعة التلاوة من هذه الآية",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // Section 2: Header for Chronological User Bookmarks
                item(key = "bookmarks_header") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "المرجعيات المحفوظة",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkSlate
                        )
                        if (bookmarks.isNotEmpty()) {
                            Text(
                                text = "${bookmarks.size} مرجعية",
                                fontSize = 13.sp,
                                color = MutedText
                            )
                        }
                    }
                }

                // Empty State
                if (bookmarks.isEmpty()) {
                    item(key = "empty_state") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp, horizontal = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.BookmarkBorder,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "لا توجد مرجعيات محفوظة بعد",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkSlate
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "اضغط مطولاً على أي آية أثناء القراءة أو استخدم زر الحفظ في شريط الأدوات لإضافتها هنا.",
                                    fontSize = 13.sp,
                                    color = MutedText,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                } else {
                    // Chronological items (newest-first)
                    items(
                        items = bookmarks,
                        key = { it.id }
                    ) { record ->
                        BookmarkItemCard(
                            record = record,
                            onClick = {
                                onSelectBookmark(record.page, record.surah, record.ayah)
                            },
                            onDelete = {
                                coroutineScope.launch {
                                    BookmarkRepository.deleteBookmark(context, record.id)
                                    val result = snackbarHostState.showSnackbar(
                                        message = "تم حذف مرجعية: ${record.surahName} (${record.ayah})",
                                        actionLabel = "تراجع",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        BookmarkRepository.restoreBookmark(context, record)
                                    }
                                }
                            }
                        )
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun BookmarkItemCard(
    record: BookmarkRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = GoldAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${record.surahName} — الآية ${record.ayah}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkSlate
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFF1F5F9)
                    ) {
                        Text(
                            text = "ص ${record.page}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF475569),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "حذف المرجعية",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (record.ayahText.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = record.ayahText,
                    fontSize = 13.sp,
                    color = Color(0xFF334155),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 19.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatArabicRelativeTime(record.createdAt),
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )

                Text(
                    text = "اضغط للمتابعة ◀",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = PrimaryEmerald
                )
            }
        }
    }
}

/**
 * Formats a UNIX timestamp into a user-friendly Arabic relative time string.
 */
private fun formatArabicRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - timestamp).coerceAtLeast(0L)

    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        minutes < 1 -> "الآن"
        minutes == 1L -> "منذ دقيقة"
        minutes == 2L -> "منذ دقيقتين"
        minutes in 3L..10L -> "منذ $minutes دقائق"
        minutes < 60 -> "منذ $minutes دقيقة"
        hours == 1L -> "منذ ساعة"
        hours == 2L -> "منذ ساعتين"
        hours in 3L..10L -> "منذ $hours ساعات"
        hours < 24 -> "منذ $hours ساعة"
        days == 1L -> "أمس"
        days == 2L -> "منذ يومين"
        days in 3L..10L -> "منذ $days أيام"
        else -> {
            val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
