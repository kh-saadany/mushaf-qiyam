package com.mushafqiyam.ui

/**
 * AppScreen: Represents the primary top-level screen destinations
 * in Mushaf Qiyam, decoupling UI flows and ensuring lazy lifecycle
 * for heavy audio/ASR resources.
 */
sealed interface AppScreen {
    object Welcome : AppScreen
    object Reading : AppScreen
    object Settings : AppScreen
    object Bookmarks : AppScreen
    object Search : AppScreen
}
