package com.nononsenseapps.feeder.ui.compose.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing and corner sizes shared with the web app (web/src/styles.css, the design tokens at the
 * top of :root). Text sizes come from MaterialTheme.typography, never from a raw fontSize:
 *
 * | web token   | Compose role                      |
 * |-------------|-----------------------------------|
 * | --fs-2xs 11 | labelSmall (badges)               |
 * | --fs-xs 12  | bodySmall / labelMedium (meta)    |
 * | --fs-sm 13  | titleSmall (section labels)       |
 * | --fs-md 14  | bodyMedium / labelLarge (buttons) |
 * | --fs-base 15| bodyLarge (body)                  |
 * | --fs-lg 18  | titleMedium / titleLarge          |
 * | --fs-xl 22  | titleLarge (screen titles)        |
 *
 * Section labels are sentence case (no uppercase), selected chips and segmented buttons use
 * secondaryContainer, and every action keeps a visible label.
 */
object FeederTokens {
    object Space {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
    }

    object Radius {
        val xs = 4.dp
        val sm = 8.dp // buttons, inputs
        val md = 12.dp // cards, rows
        val lg = 16.dp // panels
        val xl = 24.dp // dialogs, sheets
    }

    /** Buttons, inputs and segmented buttons */
    val controlHeight = 36.dp

    /** Filter chips */
    val chipHeight = 32.dp
}
