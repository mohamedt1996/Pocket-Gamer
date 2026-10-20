// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Building blocks shared by the screens: artwork with fallbacks, library cards
// with their ambient light and press, badges, section headers, glass pills,
// grouped settings rows and the QR code.

package org.pocketarcade.madeira.ui

import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.util.Locale

/** Artwork that tries each candidate in turn until one loads; `fallback` shows meanwhile. */
@Composable
fun Artwork(urls: List<String>, modifier: Modifier = Modifier, file: String? = null, placeholderIcon: ImageVector = Icons.Filled.SportsEsports) {
    var index by remember(urls, file) { mutableIntStateOf(0) }
    val colors = Madeira.colors
    Box(modifier.background(colors.fill), contentAlignment = Alignment.Center) {
        Icon(placeholderIcon, null, tint = colors.tertiary, modifier = Modifier.size(36.dp))
        val model: Any? = file?.let { File(it) } ?: urls.getOrNull(index)
        if (model != null) {
            AsyncImage(
                model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { if (file == null && index < urls.size) index++ },
            )
        }
    }
}

/**
 * The ambient light a TV's LED strip throws on the wall, for a card's artwork: the art
 * itself, larger and blurred, breathing slowly behind the card (Android 12+).
 */
@Composable
fun AmbientGlow(urls: List<String>, file: String?, seed: Int, dimmed: Boolean, pressed: Boolean, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < 31 || !LocalAmbient.current) return
    val t = rememberInfiniteTransition(label = "glow")
    val phase = (seed % 7) * 300
    val breathe by t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(3200 + phase, delayMillis = 0), RepeatMode.Reverse), label = "breathe")
    val sway by t.animateFloat(-1.5f, 1.5f, infiniteRepeatable(tween(7000 + phase * 2), RepeatMode.Reverse), label = "sway")
    val closed by animateFloatAsState(if (pressed) 0f else 1f, if (pressed) tween(550) else tween(1600), label = "closed")
    val strength = (if (Madeira.colors.dark) 0.9f else 0.65f) * (if (dimmed) 0.3f else 1f)
    var index by remember(urls, file) { mutableIntStateOf(0) }
    val model: Any? = file?.let { File(it) } ?: urls.getOrNull(index)
    if (model == null) return
    AsyncImage(
        model = model, contentDescription = null, contentScale = ContentScale.Crop,
        onError = { if (file == null && index < urls.size) index++ },
        modifier = modifier
            .graphicsLayer {
                val s = 1.06f + 0.06f * breathe * closed
                scaleX = s; scaleY = s; rotationZ = sway
                alpha = strength * breathe * closed
            }
            .blur(28.dp),
    )
}

/** A library grid card: artwork 2:3 with its glow, shrinking while pressed; name and pills below. */
@Composable
fun GameCard(
    title: String,
    urls: List<String>,
    file: String?,
    seed: Int,
    dimmed: Boolean,
    badges: List<String>,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = if (pressed) 0.86f else 0.58f, stiffness = if (pressed) 600f else 220f), label = "press")
    val colors = Madeira.colors
    Column(modifier.clickable(interaction, indication = null, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            AmbientGlow(urls, file, seed, dimmed, pressed, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().scale(scale).clip(RoundedCornerShape(12.dp))) {
                Artwork(urls, Modifier.fillMaxSize().alpha(if (dimmed) 0.55f else 1f), file)
                if (dimmed && progress == null) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(28.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.FileDownload, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Color.White, trackColor = Color(0x55FFFFFF))
                }
            }
        }
        if (showTitle) {
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badges.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Badges(badges)
            }
        }
    }
}

/** A list row: small artwork, title and pills. */
@Composable
fun GameRow(title: String, urls: List<String>, file: String?, dimmed: Boolean, badges: List<String>, progress: Float?, dense: Boolean, onClick: () -> Unit) {
    val colors = Madeira.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = if (dense) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(urls, Modifier.width(if (dense) 36.dp else 52.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).alpha(if (dimmed) 0.55f else 1f), file)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badges.isNotEmpty() && !dense) { Spacer(Modifier.height(4.dp)); Badges(badges) }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary)
    }
}

@Composable
fun Badges(items: List<String>) {
    val colors = Madeira.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in items.take(4)) {
            Text(b, style = MaterialTheme.typography.labelSmall, color = colors.secondary, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.fill).padding(horizontal = 5.dp, vertical = 3.dp))
        }
    }
}

/** A section title with its count; with `collapsed`, tapping it folds the section. */
@Composable
fun SectionHeader(title: String, count: Int?, collapsed: Boolean? = null, onToggle: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val colors = Madeira.colors
    val rotation by animateFloatAsState(if (collapsed == true) 0f else 90f, tween(200), label = "chevron")
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).let { if (onToggle != null) it.clickable(interaction, null, onClick = onToggle) else it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.label)
            if (count != null && count > 0) { Spacer(Modifier.width(8.dp)); Text("$count", style = MaterialTheme.typography.bodyMedium, color = colors.secondary) }
            if (collapsed != null) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.secondary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation })
            }
        }
        trailing()
    }
}

/** A round glass button with an icon, as the library's bar buttons. */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Madeira.colors
    Box(modifier.size(40.dp).glass(colors, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = colors.label, modifier = Modifier.size(20.dp))
    }
}

/** A capsule glass button with text. */
@Composable
fun GlassPill(text: String, onClick: () -> Unit, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val colors = Madeira.colors
    Row(modifier.height(40.dp).glass(colors).clickable(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = colors.label, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.label)
    }
}

/** The Desktop button: liquid metal with its label. */
@Composable
fun DesktopButton(onClick: () -> Unit) {
    LiquidMetal(Modifier.height(40.dp).width(116.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DesktopWindows, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Desktop", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

/** A grouped settings section (iOS insetGrouped): optional header and footer, rows inside a rounded card. */
@Composable
fun SettingsSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = Madeira.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
        if (header != null) Text(header.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelMedium, color = colors.secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (colors.dark) Color(0xFF1C1C1E) else Color.White), content = content)
        if (footer != null) Text(footer, style = MaterialTheme.typography.bodySmall, color = colors.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Madeira.colors.separator)

@Composable
fun SettingsRow(title: String, value: String? = null, subtitle: String? = null, tint: Color? = null, icon: ImageVector? = null, chevron: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = Madeira.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = tint ?: colors.accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint ?: colors.label)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.secondary, maxLines = 1)
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary)
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = Madeira.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) colors.label else colors.tertiary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
        }
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = colors.green, checkedThumbColor = Color.White))
    }
}

/** A QR code for Steam's sign-in challenge. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
    }
    Canvas(modifier.background(Color.White).padding(10.dp)) {
        val n = matrix.width
        val cell = minOf(size.width, size.height) / n
        for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1_000_000_000 -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1e9).let { if (bytes < 100_000_000) String.format(Locale.getDefault(), "%.0f MB", bytes / 1e6) else it }
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9)
}

/** "12.5 hrs played · Last played Yesterday". */
fun playtimeText(minutes: Int, lastPlayed: Long): String? {
    val parts = ArrayList<String>()
    if (minutes > 0) parts += if (minutes < 60) "$minutes min played" else String.format(Locale.getDefault(), "%.1f hrs played", minutes / 60.0)
    if (lastPlayed > 0) {
        val days = ((System.currentTimeMillis() / 1000 - lastPlayed) / 86400).toInt()
        parts += "Last played " + when {
            days <= 0 -> "today"
            days == 1 -> "yesterday"
            days < 7 -> "$days days ago"
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(lastPlayed * 1000))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
