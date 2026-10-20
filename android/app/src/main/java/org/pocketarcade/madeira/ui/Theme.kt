// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Madeira's look on Android: system-style dark and light palettes, large bold
// titles, glass pills, the liquid-metal fill (LiquidMetal.metal, ported to
// AGSL on Android 13 and later) and the ambient light around game artwork
// (AmbientGlow in Library.swift).

package org.pocketarcade.madeira.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors the screens use beyond Material's scheme. */
data class MadeiraColors(
    val dark: Boolean,
    val background: Color,
    val grouped: Color,
    val card: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val separator: Color,
    val accent: Color,
    val glass: Color,
    val glassBorder: Color,
    val fill: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
)

val DarkPalette = MadeiraColors(
    dark = true,
    background = Color(0xFF000000), grouped = Color(0xFF000000), card = Color(0xFF1C1C1E),
    label = Color.White, secondary = Color(0x99EBEBF5), tertiary = Color(0x4DEBEBF5), separator = Color(0x33FFFFFF),
    accent = Color(0xFF0A84FF), glass = Color(0x99242428), glassBorder = Color(0x2EFFFFFF), fill = Color(0x2E787880),
    green = Color(0xFF30D158), red = Color(0xFFFF453A), orange = Color(0xFFFF9F0A),
)

val LightPalette = MadeiraColors(
    dark = false,
    background = Color(0xFFFFFFFF), grouped = Color(0xFFF2F2F7), card = Color(0xFFFFFFFF),
    label = Color.Black, secondary = Color(0x993C3C43), tertiary = Color(0x4D3C3C43), separator = Color(0x293C3C43),
    accent = Color(0xFF007AFF), glass = Color(0xB3FFFFFF), glassBorder = Color(0x1F000000), fill = Color(0x1F787880),
    green = Color(0xFF34C759), red = Color(0xFFFF3B30), orange = Color(0xFFFF9500),
)

val LocalMadeira = staticCompositionLocalOf { DarkPalette }
val LocalLiquidMetal = staticCompositionLocalOf { true }
val LocalAmbient = staticCompositionLocalOf { true }

object Madeira {
    val colors: MadeiraColors @Composable get() = LocalMadeira.current
}

private fun scheme(p: MadeiraColors): ColorScheme = if (p.dark) darkColorScheme(
    primary = p.accent, onPrimary = Color.White, background = p.background, onBackground = p.label,
    surface = p.card, onSurface = p.label, surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = p.secondary,
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E), surfaceContainerHighest = Color(0xFF3A3A3C),
    surfaceContainerLow = Color(0xFF121214), secondaryContainer = Color(0xFF2C2C2E), onSecondaryContainer = p.label,
    outline = p.separator, outlineVariant = p.separator, error = p.red,
) else lightColorScheme(
    primary = p.accent, onPrimary = Color.White, background = p.grouped, onBackground = p.label,
    surface = p.card, onSurface = p.label, surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = p.secondary,
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF2F2F7), surfaceContainerHighest = Color(0xFFE5E5EA),
    surfaceContainerLow = Color(0xFFF7F7FA), secondaryContainer = Color(0xFFE5E5EA), onSecondaryContainer = p.label,
    outline = p.separator, outlineVariant = p.separator, error = p.red,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.25.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun MadeiraTheme(theme: String, liquidMetal: Boolean, ambient: Boolean, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val palette = if (dark) DarkPalette else LightPalette
    androidx.compose.runtime.CompositionLocalProvider(LocalMadeira provides palette, LocalLiquidMetal provides liquidMetal, LocalAmbient provides ambient) {
        MaterialTheme(colorScheme = scheme(palette), typography = typography, content = content)
    }
}

/** A glass surface: translucent fill with a hairline rim, as Madeira's bar pills. */
fun Modifier.glass(colors: MadeiraColors, shape: Shape = RoundedCornerShape(50)): Modifier =
    this.clip(shape).background(colors.glass, shape).border(0.75.dp, colors.glassBorder, shape)

// MARK: Liquid metal

/**
 * Madeira's liquid-chrome fill (LiquidMetal.metal): a rippling capsule whose domed,
 * flowing surface reflects a studio of light bands at six wavelengths, splitting each
 * highlight's edges into rainbows. AGSL, Android 13+.
 */
private const val LIQUID_METAL_AGSL = """
uniform float2 size;
uniform float time;
uniform float scale;
uniform float light;

float roundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}
float shape(float2 p, float2 halfSize, float radius, float t, float ripple) {
    float w = 0.55 * sin(p.x * 0.40 + t * 1.10) + 0.30 * sin(p.x * 0.72 - t * 0.80 + p.y * 0.25) + 0.15 * sin(p.y * 0.60 + t * 0.60 + p.x * 0.15);
    return roundBox(p, halfSize, radius) - ripple * w;
}
float heightAt(float2 q, float t) {
    float2 w = q;
    w += 0.38 * float2(sin(w.y * 1.3 + t * 0.35), cos(w.x * 1.1 - t * 0.28));
    w += 0.22 * float2(sin(w.y * 2.1 - t * 0.23 + 1.3), cos(w.x * 1.7 + t * 0.31 + 0.7));
    return 0.65 * sin(w.x * 1.2 + w.y * 0.7) + 0.35 * sin(w.y * 1.6 - w.x * 0.5 + 1.0);
}
float bandPhase(float2 q, float t, float e) {
    float h0 = heightAt(q, t);
    float2 n = -float2(heightAt(q + float2(e, 0.0), t) - h0, heightAt(q + float2(0.0, e), t) - h0) / e * 0.5;
    return dot(n, float2(0.80, 0.60)) * 3.4;
}
float studio(float2 n, float shiftA, float shiftB) {
    float a = dot(n, float2(0.80, 0.60)) * 3.4 + shiftA;
    float b = dot(n, float2(-0.55, 0.83)) * 2.3 + 1.2 - shiftB * 0.5;
    float v = 0.50 + 0.42 * sin(a) + 0.22 * sin(b);
    return mix(smoothstep(-0.05, 1.0, v), smoothstep(0.48, 0.60, v), 0.5);
}
half4 main(float2 position) {
    float ripple = 0.32 * scale;
    float2 halfSize = size * 0.5 - ripple - 0.5;
    float radius = min(halfSize.x, halfSize.y);
    float px = 1.0;
    float2 p = position - size * 0.5;
    float t = time;
    float d = shape(p / scale, halfSize / scale, radius / scale, t, 0.32) * scale;
    float alpha = clamp(0.5 - d / px, 0.0, 1.0);
    if (alpha <= 0.0) { return half4(0.0); }
    float unit = size.y;
    float2 q = p / unit * 1.4;
    float e = 0.012;
    float hx = heightAt(q + float2(e, 0.0), t) - heightAt(q - float2(e, 0.0), t);
    float hy = heightAt(q + float2(0.0, e), t) - heightAt(q - float2(0.0, e), t);
    float2 n = -float2(hx, hy) / (2.0 * e) * 0.5;
    float aimCentre = bandPhase(float2(0.0), t, e);
    float aimColumn = bandPhase(float2(p.x, 0.0) / unit * 1.4, t, e);
    float sway = 0.32 * sin(t * 0.19) + 0.18 * sin(t * 0.31 + 2.0);
    float target = light > 0.5 ? 1.570796 : 4.712389;
    float aim = target - mix(aimCentre, aimColumn, 0.6) + sway;
    float2 across = p / halfSize;
    n += float2(across.x * abs(across.x) * 0.35, across.y * abs(across.y) * 0.85);
    float h = 0.25 * scale;
    float2 outward = float2(roundBox(p + float2(h, 0.0), halfSize, radius) - roundBox(p - float2(h, 0.0), halfSize, radius),
                            roundBox(p + float2(0.0, h), halfSize, radius) - roundBox(p - float2(0.0, h), halfSize, radius));
    outward = normalize(outward + 1e-5);
    float inside = -d;
    float bevel = clamp(size.y * 0.12, 2.0 * scale, 6.0 * scale);
    float k = clamp(1.0 - inside / bevel, 0.0, 1.0);
    n += outward * (k * k * 1.7);
    float drift = t * 0.15;
    float dispersion = 0.26 + 0.25 * k;
    float3 rgb = float3(0.0);
    rgb += float3(0.95, 0.10, 0.25) * studio(n, aim + dispersion * -1.0, drift + dispersion * -1.0);
    rgb += float3(1.00, 0.45, 0.00) * studio(n, aim + dispersion * -0.6, drift + dispersion * -0.6);
    rgb += float3(0.85, 0.85, 0.00) * studio(n, aim + dispersion * -0.2, drift + dispersion * -0.2);
    rgb += float3(0.10, 0.90, 0.35) * studio(n, aim + dispersion * 0.2, drift + dispersion * 0.2);
    rgb += float3(0.05, 0.40, 1.00) * studio(n, aim + dispersion * 0.6, drift + dispersion * 0.6);
    rgb += float3(0.45, 0.05, 0.95) * studio(n, aim + dispersion * 1.0, drift + dispersion * 1.0);
    rgb /= float3(3.40, 2.75, 2.55);
    float lum = dot(rgb, float3(0.299, 0.587, 0.114));
    rgb = lum + (rgb - lum) * 2.7;
    float3 navy = float3(0.03, 0.04, 0.085);
    rgb = navy + (1.0 - navy) * clamp(rgb, 0.0, 1.0);
    rgb += float3(-0.03, 0.0, 0.07) * (lum * (1.0 - lum) * 4.0);
    float line = clamp(1.0 - inside / (2.2 * px + 0.5 * scale), 0.0, 1.0);
    rgb = mix(rgb, float3(1.0), line * 0.75);
    rgb = clamp(rgb, 0.0, 1.0);
    if (light < 0.5) { rgb -= 0.2 * rgb * rgb; }
    return half4(half3(rgb * alpha), half(alpha));
}
"""

/** A liquid-metal capsule behind `content`; a chrome gradient below Android 13. */
@Composable
fun LiquidMetal(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = Madeira.colors
    if (Build.VERSION.SDK_INT >= 33 && LocalLiquidMetal.current) {
        val shader = remember { RuntimeShader(LIQUID_METAL_AGSL) }
        val brush = remember(shader) { ShaderBrush(shader) }
        val time = remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time.floatValue = ((it - start) / 1e9f) % 3600f }
        }
        Box(modifier.drawBehind {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("time", time.floatValue)
            shader.setFloatUniform("scale", density)
            shader.setFloatUniform("light", if (colors.dark) 0f else 1f)
            drawRect(brush)
        }, contentAlignment = androidx.compose.ui.Alignment.Center, content = content)
    } else {
        val t = rememberInfiniteTransition(label = "chrome")
        val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
        val chrome = Brush.linearGradient(
            listOf(Color(0xFF0B1024), Color(0xFF8E9BB4), Color.White, Color(0xFF6D7A96), Color(0xFF141A33)),
            start = Offset(0f, 0f), end = Offset(400f * (0.5f + shift), 120f),
        )
        Box(modifier.clip(RoundedCornerShape(50)).background(chrome), contentAlignment = androidx.compose.ui.Alignment.Center, content = content)
    }
}

/** Padding that matches iOS's insetGrouped lists. */
val Gutter: Dp = 16.dp

fun Modifier.gutter() = this.padding(horizontal = Gutter)
