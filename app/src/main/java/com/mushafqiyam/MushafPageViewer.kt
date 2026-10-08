package com.mushafqiyam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MushafPageViewer(
    currentPage: Int,
    activeSurah: Int,
    activeAyah: Int,
    isScrollMode: Boolean,
    onPageChanged: (Int) -> Unit,
    onAyahTapped: (page: Int, surah: Int, ayah: Int) -> Unit,
    onFiveTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (isScrollMode) {
            val lazyListState = rememberLazyListState()
            var viewportHeight by remember { mutableIntStateOf(0) }
            var itemHeight by remember { mutableIntStateOf(0) }

            LaunchedEffect(currentPage, activeSurah, activeAyah) {
                if (itemHeight > 0 && viewportHeight > 0) {
                    val boxes = MushafPageRepository.getAyahBoxesOnPage(currentPage, activeSurah, activeAyah)
                    if (boxes.isNotEmpty()) {
                        val box = boxes.first()
                        val yInPage = box.normTop * itemHeight
                        val offset = (yInPage - (viewportHeight / 2)).toInt()
                        lazyListState.animateScrollToItem(currentPage - 1, offset)
                    }
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { viewportHeight = it.size.height }
                ) {
                    items(604) { index ->
                        val pageNum = index + 1
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { itemHeight = it.size.height }
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                MushafSinglePageCanvas(
                                    pageNumber = pageNum,
                                    activeSurah = activeSurah,
                                    activeAyah = activeAyah,
                                    onTapAyah = { s, a -> onAyahTapped(pageNum, s, a) },
                                    onFiveTap = onFiveTap
                                )
                            }
                        }
                    }
                }
            }
        } else {
            val pagerState = rememberPagerState(
                initialPage = (currentPage - 1).coerceIn(0, 603),
                pageCount = { 604 }
            )

            LaunchedEffect(currentPage) {
                val targetIdx = (currentPage - 1).coerceIn(0, 603)
                if (pagerState.currentPage != targetIdx) {
                    pagerState.animateScrollToPage(targetIdx)
                }
            }

            LaunchedEffect(pagerState.settledPage) {
                val newPage = pagerState.settledPage + 1
                if (newPage != currentPage) {
                    onPageChanged(newPage)
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HorizontalPager(
                    state = pagerState,
                    beyondBoundsPageCount = 1,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    val pageNumber = pageIndex + 1
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        MushafSinglePageCanvas(
                            pageNumber = pageNumber,
                            activeSurah = activeSurah,
                            activeAyah = activeAyah,
                            onTapAyah = { s, a -> onAyahTapped(pageNumber, s, a) },
                            onFiveTap = onFiveTap
                        )
                    }
                }
            }
            
            // Top Peek Overlay
            var peekText by remember { mutableStateOf("") }
            val pageVerses = remember(currentPage, MushafPageRepository.isInitialized) {
                if (MushafPageRepository.isInitialized) MushafPageRepository.getPageVerses(currentPage) else emptyList()
            }
            val isLastVerseActive = pageVerses.lastOrNull()?.let { it.surah == activeSurah && it.ayah == activeAyah } ?: false

            LaunchedEffect(isLastVerseActive, currentPage) {
                if (isLastVerseActive && currentPage < 604) {
                    val nextVerses = MushafPageRepository.getPageVerses(currentPage + 1)
                    val nextVerse = nextVerses.firstOrNull()
                    if (nextVerse != null) {
                        val words = nextVerse.text.split(" ").take(5).joinToString(" ")
                        peekText = words + "..."
                    } else {
                        peekText = ""
                    }
                } else {
                    peekText = ""
                }
            }

            if (peekText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Text(
                            text = peekText,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MushafSinglePageCanvas(
    pageNumber: Int,
    activeSurah: Int,
    activeAyah: Int,
    onTapAyah: (surah: Int, ayah: Int) -> Unit,
    onFiveTap: () -> Unit
) {
    val context = LocalContext.current
    var pageBitmap by remember(pageNumber) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(pageNumber) { mutableStateOf(true) }

    LaunchedEffect(pageNumber, MushafPageRepository.isInitialized) {
        isLoading = true
        pageBitmap = withContext(Dispatchers.IO) {
            MushafPageRepository.loadPageBitmap(context, pageNumber)
        }
        isLoading = false
    }

    val activeBoxes = remember(pageNumber, activeSurah, activeAyah, MushafPageRepository.isInitialized) {
        if (MushafPageRepository.isInitialized) {
            MushafPageRepository.getAyahBoxesOnPage(pageNumber, activeSurah, activeAyah)
        } else {
            emptyList()
        }
    }

    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1200f / 1941f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFFFFDF5))
                .border(1.dp, Color(0xFFD7CCC8), RoundedCornerShape(8.dp))
                .pointerInput(pageNumber) {
                    detectTapGestures { tapOffset ->
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 500) {
                            tapCount++
                        } else {
                            tapCount = 1
                        }
                        lastTapTime = now

                        if (tapCount >= 5) {
                            tapCount = 0
                            onFiveTap()
                        }

                        if (size.width > 0 && size.height > 0) {
                            val normX = (tapOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            val normY = (tapOffset.y / size.height.toFloat()).coerceIn(0f, 1f)
                            val hit = MushafPageRepository.findTappedAyah(pageNumber, normX, normY)
                            if (hit != null) {
                                onTapAyah(hit.first, hit.second)
                            }
                        }
                    }
                }
        ) {
            val bmp = pageBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "صفحة المصحف $pageNumber",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    val strokeWidth = 1.5.dp.toPx()

                    for (box in activeBoxes) {
                        val left = box.normLeft * w
                        val top = box.normTop * h
                        val boxW = (box.normRight - box.normLeft) * w
                        val boxH = (box.normBottom - box.normTop) * h

                        drawRoundRect(
                            color = Color(0x384CAF50),
                            topLeft = Offset(left, top),
                            size = Size(boxW, boxH),
                            cornerRadius = cornerRadius
                        )

                        drawRoundRect(
                            color = Color(0xAA2E7D32),
                            topLeft = Offset(left, top),
                            size = Size(boxW, boxH),
                            cornerRadius = cornerRadius,
                            style = Stroke(width = strokeWidth)
                        )
                    }
                }
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF2E7D32))
                }
            } else {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "⚠️ صورة الصفحة $pageNumber غير متوفرة على الجهاز",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC62828),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "يرجى تثبيت النسخة الكاملة [full] مرة واحدة لنسخ صفحات المصحف الـ 604 إلى ذاكرة الجهاز.",
                            fontSize = 13.sp,
                            color = Color.DarkGray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SurahPagePickerDialog(
    currentPage: Int,
    onDismiss: () -> Unit,
    onSelectPage: (page: Int, surah: Int, ayah: Int) -> Unit
) {
    var pageInput by remember { mutableStateOf(currentPage.toString()) }
    var searchQuery by remember { mutableStateOf("") }
    val surahs = remember { MushafPageRepository.getSurahList() }

    val filteredSurahs = remember(searchQuery, surahs) {
        if (searchQuery.isBlank()) {
            surahs
        } else {
            val cleanQ = FuzzyMatcher.normalizeArabic(searchQuery)
            surahs.filter { s ->
                FuzzyMatcher.normalizeArabic(s.name).contains(cleanQ) ||
                    s.number.toString() == searchQuery.trim()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق")
            }
        },
        title = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Text(
                    text = "📖 الانتقال السريع إلى سورة أو صفحة",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        text = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = pageInput,
                            onValueChange = { pageInput = it.filter { ch -> ch.isDigit() }.take(3) },
                            label = { Text("رقم الصفحة (1 - 604)", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val target = pageInput.toIntOrNull()?.coerceIn(1, 604) ?: currentPage
                                val firstVerse = MushafPageRepository.getPageVerses(target).firstOrNull()
                                onSelectPage(
                                    target,
                                    firstVerse?.surah ?: 1,
                                    firstVerse?.ayah ?: 1
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Text("انتقال")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("بحث باسم السورة أو رقمها...", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredSurahs) { surah ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectPage(surah.startPage, surah.number, 1)
                                    },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${surah.number}. ${surah.name}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color(0xFF1B5E20)
                                    )
                                    Text(
                                        text = "ص ${surah.startPage} (${surah.verseCount} آية)",
                                        fontSize = 12.sp,
                                        color = Color(0xFF558B2F)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}
