package com.mushafqiyam.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mushafqiyam.MushafPageRepository
import com.mushafqiyam.QuranVocabularyFilter
import com.mushafqiyam.search.QuranSearchEngine
import com.mushafqiyam.search.SearchResultItem
import kotlinx.coroutines.delay

private val BgColor = Color(0xFFF7F6F2)
private val PrimaryEmerald = Color(0xFF0F5132)
private val DarkSlate = Color(0xFF1E293B)
private val CardBg = Color(0xFFFFFFFF)
private val HighlightYellow = Color(0xFFFFF59D)

sealed interface SearchDisplayItem {
    val globalIndex: Int
    val surah: Int
    val ayah: Int
    val page: Int
    val surahName: String
    val uthmaniText: String
    val isExactMatch: Boolean

    data class TextMatch(
        val item: SearchResultItem
    ) : SearchDisplayItem {
        override val globalIndex: Int get() = item.entry.globalIndex
        override val surah: Int get() = item.entry.surah
        override val ayah: Int get() = item.entry.ayah
        override val page: Int get() = item.entry.page
        override val surahName: String get() = item.entry.surahName
        override val uthmaniText: String get() = item.entry.uthmaniText
        override val isExactMatch: Boolean get() = item.matchTier == 0
    }

    data class VoiceMatch(
        val match: QuranVocabularyFilter.VoiceVerseMatch
    ) : SearchDisplayItem {
        override val globalIndex: Int get() = match.verse.globalIndex
        override val surah: Int get() = match.verse.surah
        override val ayah: Int get() = match.verse.ayah
        override val page: Int get() = match.verse.page
        override val surahName: String get() = match.surahName
        override val uthmaniText: String get() = match.verse.text
        override val isExactMatch: Boolean get() = match.matchTier == 0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateBack: () -> Unit,
    onSelectVerse: (page: Int, surah: Int, ayah: Int) -> Unit,
    isVoiceListening: Boolean,
    voiceStreamingText: String,
    onStartVoiceListening: () -> Unit,
    onStopVoiceListening: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var displayedResults by remember { mutableStateOf<List<SearchDisplayItem>>(emptyList()) }
    var totalMatchesCount by remember { mutableIntStateOf(0) }
    var searchElapsedMs by remember { mutableLongStateOf(0L) }
    var isVoiceModeActive by remember { mutableStateOf(false) }

    // Ensure QuranSearchEngine index is ready
    LaunchedEffect(Unit) {
        if (!QuranSearchEngine.isReady) {
            QuranSearchEngine.ensureIndexed(MushafPageRepository.getAllVerses())
        }
    }

    // Debounced Text Search (120ms)
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            if (isVoiceListening) {
                onStopVoiceListening()
            }
            isVoiceModeActive = false
            delay(120) // Debounce typing
            val response = QuranSearchEngine.search(searchQuery, limit = 150)
            displayedResults = response.results.map { SearchDisplayItem.TextMatch(it) }
            totalMatchesCount = response.totalMatches
            searchElapsedMs = response.elapsedMs
        } else if (!isVoiceModeActive) {
            displayedResults = emptyList()
            totalMatchesCount = 0
            searchElapsedMs = 0L
        }
    }

    // Live Voice Search Updates from AudioRecognizer
    LaunchedEffect(voiceStreamingText, isVoiceListening) {
        if (isVoiceListening || (isVoiceModeActive && voiceStreamingText.isNotBlank())) {
            isVoiceModeActive = true
            if (voiceStreamingText.isNotBlank()) {
                val voiceResponse = QuranVocabularyFilter.searchAllMatchingVerses(voiceStreamingText, limit = 150)
                if (voiceResponse.results.isNotEmpty()) {
                    displayedResults = voiceResponse.results.map { SearchDisplayItem.VoiceMatch(it) }
                    totalMatchesCount = voiceResponse.totalMatches
                    searchElapsedMs = voiceResponse.elapsedMs
                }
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "البحث في القرآن الكريم",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = DarkSlate
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isVoiceListening) onStopVoiceListening()
                            onNavigateBack()
                        }) {
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
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Search Input Box with Mic trailing button
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = if (isVoiceListening) "جاري الاستماع للتلاوة..." else "ابحث بكلمة أو آية بالرسم الإملائي أو العثماني...",
                            fontSize = 14.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = PrimaryEmerald
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "مسح",
                                        tint = Color(0xFF64748B)
                                    )
                                }
                            }

                            // Voice Search Button
                            IconButton(
                                onClick = {
                                    if (isVoiceListening) {
                                        onStopVoiceListening()
                                    } else {
                                        searchQuery = "" // Clear text query when starting voice
                                        onStartVoiceListening()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isVoiceListening) Icons.Default.GraphicEq else Icons.Outlined.Mic,
                                    contentDescription = "البحث الصوتي",
                                    tint = if (isVoiceListening) Color(0xFF00E676) else PrimaryEmerald,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CardBg,
                        unfocusedContainerColor = CardBg,
                        focusedBorderColor = if (isVoiceListening) Color(0xFF00E676) else PrimaryEmerald,
                        unfocusedBorderColor = if (isVoiceListening) Color(0xFF00E676) else Color(0xFFCBD5E1)
                    ),
                    singleLine = true
                )

                // Voice Listening Active Banner
                if (isVoiceListening) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        border = BorderStroke(1.dp, Color(0xFF81C784))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = PrimaryEmerald,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "جاري الإنصات لتلاوتك...",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryEmerald
                                    )
                                    if (voiceStreamingText.isNotBlank()) {
                                        Text(
                                            text = "المقروء: $voiceStreamingText",
                                            fontSize = 12.sp,
                                            color = Color(0xFF2E7D32),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = onStopVoiceListening,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إيقاف", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Results Counter & Stats Badge
                if (displayedResults.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val countText = if (totalMatchesCount > displayedResults.size) {
                            "عرض ${displayedResults.size} من أصل $totalMatchesCount نتيجة"
                        } else {
                            "تم العثور على $totalMatchesCount آية مطابقة"
                        }
                        Text(
                            text = countText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF455A64)
                        )
                        if (searchElapsedMs > 0) {
                            Text(
                                text = "${searchElapsedMs}ms",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Results List or Placeholder/Empty State
                if (displayedResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(displayedResults, key = { it.globalIndex }) { result ->
                            SearchResultCard(
                                item = result,
                                onClick = {
                                    if (isVoiceListening) onStopVoiceListening()
                                    onSelectVerse(result.page, result.surah, result.ayah)
                                }
                            )
                        }
                    }
                } else if (searchQuery.isNotBlank() || (isVoiceModeActive && voiceStreamingText.isNotBlank() && !isVoiceListening)) {
                    // Empty Results State
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .padding(16.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBg)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "لم يتم العثور على نتائج مطابقة",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkSlate
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "تأكد من كتابة الكلمات بدقة، أو ابحث بجزء أصغر من الآية.",
                                    fontSize = 13.sp,
                                    color = Color(0xFF64748B),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else if (!isVoiceListening) {
                    // Initial State: Suggested Quick Searches
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "عمليات بحث مقترحة",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        val suggestions = listOf(
                            "الصراط المستقيم",
                            "الرحمن الرحيم",
                            "السماوات والأرض",
                            "الصلاة والزكاة",
                            "ذلك الكتاب",
                            "الم",
                            "قل هو الله أحد"
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            suggestions.forEach { suggestion ->
                                OutlinedButton(
                                    onClick = { searchQuery = suggestion },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(0.85f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryEmerald),
                                    border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                                ) {
                                    Text(
                                        text = suggestion,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
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
private fun SearchResultCard(
    item: SearchDisplayItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header: Surah Name & Ayah, Page Pill, Match badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${item.surahName} — آية ${item.ayah}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryEmerald
                    )
                    if (item.isExactMatch) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE8F5E9), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "تطابق تام",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryEmerald
                            )
                        }
                    }
                }

                // Page badge
                Box(
                    modifier = Modifier
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "ص ${item.page}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF455A64)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Uthmani Verse Text with clean highlighting
            val styledText = remember(item) {
                if (item is SearchDisplayItem.TextMatch && item.item.highlightRanges.isNotEmpty()) {
                    buildHighlightedUthmaniString(item.item.entry.uthmaniText, item.item.highlightRanges)
                } else {
                    AnnotatedString(item.uthmaniText)
                }
            }

            Text(
                text = styledText,
                fontSize = 17.sp,
                color = DarkSlate,
                lineHeight = 28.sp,
                textAlign = TextAlign.Start
            )
        }
    }
}

private fun buildHighlightedUthmaniString(
    fullText: String,
    highlightRanges: List<IntRange>
): AnnotatedString {
    return buildAnnotatedString {
        append(fullText)
        for (range in highlightRanges) {
            val start = range.first.coerceIn(0, fullText.length)
            val end = (range.last + 1).coerceIn(start, fullText.length)
            if (start < end) {
                addStyle(
                    style = SpanStyle(
                        background = HighlightYellow,
                        color = PrimaryEmerald,
                        fontWeight = FontWeight.Bold
                    ),
                    start = start,
                    end = end
                )
            }
        }
    }
}
