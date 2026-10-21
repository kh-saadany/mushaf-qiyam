package com.mushafqiyam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun PrayerSetupDialog(
    savedBookmark: Triple<Int, Int, Int>?, // (page, surah, ayah)
    onDismiss: () -> Unit,
    onStartPrayer: (PrayerSessionConfig) -> Unit
) {
    var isQiyamMode by remember { mutableStateOf(true) }
    var totalRakahs by remember { mutableIntStateOf(2) }

    // Plans for rakahs 1 and 2
    var r1Type by remember { mutableStateOf(RakahPlanType.CONTINUE_PREVIOUS) }
    var r1Surah by remember { mutableIntStateOf(savedBookmark?.second ?: 2) }
    var r1Ayah by remember { mutableIntStateOf(savedBookmark?.third ?: 1) }

    var r2Type by remember { mutableStateOf(RakahPlanType.CONTINUE_PREVIOUS) }
    var r2Surah by remember { mutableIntStateOf(2) }
    var r2Ayah by remember { mutableIntStateOf(1) }

    val allSurahs = remember { MushafPageRepository.getSurahList() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight()
                .padding(vertical = 20.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFFCFDF9),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "الإعدادات",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "اضبط وضع الصلاة وخطة التلاوة التي ستقرؤها بعد سورة الفاتحة",
                        fontSize = 13.sp,
                        color = Color(0xFF546E7A),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // 1. Selector for Prayer Mode & Number of Rak'ahs
                    Text(
                        text = "نوع الصلاة وعدد الركعات:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF263238),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Prominent option for صلاة القيام
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isQiyamMode) Color(0xFF1B5E20) else Color(0xFFE8F5E9))
                            .clickable {
                                isQiyamMode = true
                                totalRakahs = 2
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🕌 صلاة القيام (شفع تلو شفع بتتابع مستمر)",
                            fontSize = 14.sp,
                            fontWeight = if (isQiyamMode) FontWeight.Bold else FontWeight.Medium,
                            color = if (isQiyamMode) Color.White else Color(0xFF1B5E20)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 3, 4).forEach { count ->
                            val isSelected = (!isQiyamMode && totalRakahs == count)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF1B5E20) else Color(0xFFF1F8E9))
                                    .clickable {
                                        isQiyamMode = false
                                        totalRakahs = count
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (count == 2) "2 (ركعتان)" else "$count",
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else Color(0xFF1B5E20)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // 2. Rak'ah Recitation Plans
                    Text(
                        text = "خطة التلاوة لكل ركعة:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF263238),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Rak'ah 1 Card
                    RakahPlanCard(
                        rakahIndex = 1,
                        isFirstRakah = true,
                        selectedType = r1Type,
                        onTypeChange = { r1Type = it },
                        surahNumber = r1Surah,
                        onSurahChange = { r1Surah = it; r1Ayah = 1 },
                        ayahNumber = r1Ayah,
                        onAyahChange = { r1Ayah = it },
                        savedBookmark = savedBookmark,
                        allSurahs = allSurahs
                    )

                    if (isQiyamMode || totalRakahs >= 2) {
                        Spacer(modifier = Modifier.height(12.dp))
                        RakahPlanCard(
                            rakahIndex = 2,
                            isFirstRakah = false,
                            selectedType = r2Type,
                            onTypeChange = { r2Type = it },
                            surahNumber = r2Surah,
                            onSurahChange = { r2Surah = it; r2Ayah = 1 },
                            ayahNumber = r2Ayah,
                            onAyahChange = { r2Ayah = it },
                            savedBookmark = savedBookmark,
                            allSurahs = allSurahs
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 3. Actions Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("إلغاء", fontSize = 15.sp, color = Color(0xFF455A64))
                        }

                        Button(
                            onClick = {
                                val plansList = mutableListOf<RakahPlan>()
                                plansList.add(RakahPlan(r1Type, r1Surah, r1Ayah))
                                if (isQiyamMode || totalRakahs >= 2) {
                                    plansList.add(RakahPlan(r2Type, r2Surah, r2Ayah))
                                }

                                onStartPrayer(
                                    PrayerSessionConfig(
                                        totalRakahs = if (isQiyamMode) 2 else totalRakahs,
                                        isQiyamMode = isQiyamMode,
                                        plans = plansList
                                    )
                                )
                            },
                            modifier = Modifier
                                .weight(1.5f)
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                        ) {
                            Text("بدء الصلاة", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RakahPlanCard(
    rakahIndex: Int,
    isFirstRakah: Boolean,
    selectedType: RakahPlanType,
    onTypeChange: (RakahPlanType) -> Unit,
    surahNumber: Int,
    onSurahChange: (Int) -> Unit,
    ayahNumber: Int,
    onAyahChange: (Int) -> Unit,
    savedBookmark: Triple<Int, Int, Int>?,
    allSurahs: List<MushafPageRepository.SurahInfo>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF1F8E9),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "الركعة $rakahIndex",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D32)
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isFirstRakah) {
                // Option 1: Continue from saved bookmark
                val bookmarkText = if (savedBookmark != null && savedBookmark.second > 1) {
                    val sName = MushafPageRepository.getSurahName(savedBookmark.second)
                    "البدء من آخر آية توقفت عندها [$sName آية ${savedBookmark.third} - ص ${savedBookmark.first}]"
                } else {
                    "البدء من آخر آية توقفت عندها [سورة البقرة آية 1 - ص 2]"
                }

                PlanOptionRow(
                    title = bookmarkText,
                    selected = (selectedType == RakahPlanType.CONTINUE_PREVIOUS),
                    onClick = { onTypeChange(RakahPlanType.CONTINUE_PREVIOUS) }
                )

                Spacer(modifier = Modifier.height(4.dp))

                PlanOptionRow(
                    title = "اختيار السورة والآية",
                    selected = (selectedType == RakahPlanType.CUSTOM_SURAH_AYAH),
                    onClick = { onTypeChange(RakahPlanType.CUSTOM_SURAH_AYAH) }
                )

                Spacer(modifier = Modifier.height(4.dp))

                PlanOptionRow(
                    title = "اكتشاف صوتي تلقائي",
                    selected = (selectedType == RakahPlanType.AUTO_DISCOVERY),
                    onClick = { onTypeChange(RakahPlanType.AUTO_DISCOVERY) }
                )
            } else {
                PlanOptionRow(
                    title = "استكمال تلقائي من حيث انتهت الركعة السابقة",
                    selected = (selectedType == RakahPlanType.CONTINUE_PREVIOUS),
                    onClick = { onTypeChange(RakahPlanType.CONTINUE_PREVIOUS) }
                )

                Spacer(modifier = Modifier.height(4.dp))

                PlanOptionRow(
                    title = "اختيار السورة والآية",
                    selected = (selectedType == RakahPlanType.CUSTOM_SURAH_AYAH),
                    onClick = { onTypeChange(RakahPlanType.CUSTOM_SURAH_AYAH) }
                )

                Spacer(modifier = Modifier.height(4.dp))

                PlanOptionRow(
                    title = "اكتشاف صوتي تلقائي",
                    selected = (selectedType == RakahPlanType.AUTO_DISCOVERY),
                    onClick = { onTypeChange(RakahPlanType.AUTO_DISCOVERY) }
                )
            }

            // If Custom Surah/Ayah selected, show dropdown/stepper
            if (selectedType == RakahPlanType.CUSTOM_SURAH_AYAH) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Surah Picker Trigger Box
                    var showSurahPicker by remember { mutableStateOf(false) }
                    val currentSurahInfo = allSurahs.firstOrNull { it.number == surahNumber } ?: allSurahs.firstOrNull()

                    Box(
                        modifier = Modifier
                            .weight(1.5f)
                            .height(46.dp)
                            .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showSurahPicker = true }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentSurahInfo?.name ?: "سورة $surahNumber",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }

                    if (showSurahPicker) {
                        SurahSelectionDialog(
                            allSurahs = allSurahs,
                            onDismiss = { showSurahPicker = false },
                            onSelectSurah = { sNum ->
                                onSurahChange(sNum)
                                showSurahPicker = false
                            }
                        )
                    }

                    // Ayah Number Direct Input + Stepper
                    val maxAyahs = currentSurahInfo?.verseCount ?: 286
                    Row(
                        modifier = Modifier
                            .weight(1.1f)
                            .height(46.dp)
                            .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(8.dp))
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = { if (ayahNumber > 1) onAyahChange(ayahNumber - 1) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text("-", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }

                        var textVal by remember(ayahNumber) { mutableStateOf(ayahNumber.toString()) }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("آية ", fontSize = 11.sp, color = Color.Gray)
                            BasicTextField(
                                value = textVal,
                                onValueChange = { newStr ->
                                    val digits = newStr.filter { it.isDigit() }
                                    textVal = digits
                                    val parsed = digits.toIntOrNull()
                                    if (parsed != null && parsed in 1..maxAyahs) {
                                        onAyahChange(parsed)
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    color = Color(0xFF1B5E20)
                                ),
                                modifier = Modifier.width(36.dp)
                            )
                        }

                        IconButton(
                            onClick = { if (ayahNumber < maxAyahs) onAyahChange(ayahNumber + 1) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1B5E20)),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            color = if (selected) Color(0xFF1B5E20) else Color(0xFF37474F),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun SurahSelectionDialog(
    allSurahs: List<MushafPageRepository.SurahInfo>,
    onDismiss: () -> Unit,
    onSelectSurah: (Int) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredSurahs = remember(searchQuery, allSurahs) {
        if (searchQuery.isBlank()) {
            allSurahs
        } else {
            val cleanQ = FuzzyMatcher.normalizeArabic(searchQuery)
            allSurahs.filter { s ->
                FuzzyMatcher.normalizeArabic(s.name).contains(cleanQ) ||
                    s.number.toString() == searchQuery.trim()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .height(480.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFFCFDF9),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📖 اختر السورة",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20)
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.Gray)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("ابحث باسم السورة أو رقمها...", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredSurahs, key = { it.number }) { surah ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectSurah(surah.number) },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${surah.number}. ${surah.name}",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = Color(0xFF1B5E20)
                                    )
                                    Text(
                                        text = "${surah.verseCount} آية (ص ${surah.startPage})",
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
    }
}
