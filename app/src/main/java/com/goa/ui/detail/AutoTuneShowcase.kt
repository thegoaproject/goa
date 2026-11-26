package com.goa.ui.detail

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goa.data.repository.GamePatchProfile
import com.goa.engine.input.ControllerActions
import com.goa.BuildConfig
import com.goa.ui.theme.RetroAmber
import kotlinx.coroutines.delay

enum class AutoTuneRowKind { CONTROLS, SOUND, CONFIG, SOURCE }

data class AutoTuneRow(val kind: AutoTuneRowKind, val title: String, val detail: String)

/** What GOA configured for a game, derived from the patch profiles that matched it. */
data class AutoTuneSummary(
    val rows: List<AutoTuneRow>,
    val isConfident: Boolean
) {
    /** Number of settings GOA applied, excluding the "where this came from" row. */
    val tunedCount: Int get() = rows.count { it.kind != AutoTuneRowKind.SOURCE }
}

/**
 * Builds the showcase content from matched profiles, using the same precedence the engine applies:
 * the first non-universal profile supplies hardware settings, the first profile with controls supplies the layout.
 * Returns null when no game-specific profile matched, so the showcase never overstates generic defaults.
 */
fun buildAutoTuneSummary(profiles: List<GamePatchProfile>, customControls: Boolean = false): AutoTuneSummary? {
    // Engine profiles (evidence "engine") are universal: they bring the layout, never the hardware settings. A game only
    // an engine profile matched still gets its card, and the source row then names the engine.
    val controlsProfile = profiles.firstOrNull { it.controlOverrides != null }
    val primary = profiles.firstOrNull { !it.universal } ?: controlsProfile?.takeIf { it.evidence == "engine" } ?: return null
    val source = if (!customControls && controlsProfile?.evidence == "engine") controlsProfile else primary
    val rows = mutableListOf<AutoTuneRow>()

    if (customControls) {
        // The player's own mappings replace the profile's layout, so the card must not present the profile's as the game's
        rows += AutoTuneRow(AutoTuneRowKind.CONTROLS, "Custom controls", "Your own button mappings")
    } else controlsProfile?.controlOverrides?.let { controls ->
        val bindings = listOf("btn_a" to "A", "btn_b" to "B", "btn_x" to "X").mapNotNull { (key, label) ->
            controls.buttonBindings?.get(key)?.let { ControllerActions.findById(it) }
                ?.let { "$label · ${controls.buttonLabels?.get(key) ?: it.name}" }
        }
        rows += AutoTuneRow(
            AutoTuneRowKind.CONTROLS,
            stickModeLabel(controls.stickMode),
            bindings.joinToString("   ").ifEmpty { "Gamepad and touch layout mapped" }
        )
    }

    soundRow(primary)?.let { rows += it }

    val patchedSettings = profiles.sumOf { profile -> profile.configRules.sumOf { it.entries.size } }
    if (patchedSettings > 0) {
        rows += AutoTuneRow(
            AutoTuneRowKind.CONFIG,
            "$patchedSettings game settings pre-patched",
            "Written straight into the game's own config files · tap to see them"
        )
    }

    if (rows.isEmpty()) return null

    val tier = com.goa.domain.controls.ControlsTier.ofProfile(source.evidence, source.confidence, source.id)
    val confident = tier == com.goa.domain.controls.ControlsTier.EXACT || tier == com.goa.domain.controls.ControlsTier.ENGINE
    rows += when {
        primary.id.startsWith("published-") -> AutoTuneRow(AutoTuneRowKind.SOURCE, "Community-tuned default", "Settings most players chose for this game")
        source.evidence == null -> AutoTuneRow(AutoTuneRowKind.SOURCE, "Hand-tuned by the GOA team", "Curated profile: ${source.name}")
        else -> AutoTuneRow(
            AutoTuneRowKind.SOURCE,
            tier.label,
            when (tier) {
                com.goa.domain.controls.ControlsTier.CUSTOM, com.goa.domain.controls.ControlsTier.EXACT ->
                    if (source.evidence == "readme" || source.evidence == "config") "Read from the game's own readme and setup files"
                    else "Keys taken from the game's manual or documentation"
                com.goa.domain.controls.ControlsTier.ENGINE -> "${tier.blurb}: ${source.name}"
                com.goa.domain.controls.ControlsTier.GENRE -> "${tier.blurb} · fine-tune them in Controls"
                com.goa.domain.controls.ControlsTier.GUESS -> "Set up from ${source.name}'s own files · fine-tune them in Controls"
            }
        )
    }
    return AutoTuneSummary(rows, confident)
}

private fun stickModeLabel(stickMode: String?): String = when (stickMode) {
    "fps_dual_stick" -> "Dual-stick FPS controls"
    "classic_keyboard" -> "Arrow-key controls on the stick"
    "native_gameport" -> "Analog joystick (PC gameport)"
    "virtual_mouse" -> "Tap-to-point mouse controls"
    "keys_pointer" -> "Walk on the stick, aim with the pointer"
    else -> "Custom controller layout"
}

private fun soundRow(profile: GamePatchProfile): AutoTuneRow? {
    val overrides = profile.dosboxOverrides
    val sfx = profile.soundPreset?.sfx ?: overrides.sbtype
    val card = when (sfx) {
        "sb16" -> "Sound Blaster 16"
        "sbpro2", "sbpro1" -> "Sound Blaster Pro"
        "sb2", "sb1" -> "Sound Blaster"
        "gus" -> "Gravis Ultrasound"
        "pas16" -> "Pro Audio Spectrum 16"
        "pcspeaker" -> "PC Speaker"
        "tandy" -> "Tandy 3-voice sound"
        "covox" -> "Covox Speech Thing"
        "cms", "gb" -> "Creative Music System"
        "opl" -> "AdLib FM"
        else -> null
    } ?: return null
    val music = when (profile.soundPreset?.music) {
        "opl2" -> "AdLib FM music"
        "opl3" -> "OPL3 FM music"
        "digital" -> "Digital music"
        "gus" -> "Gravis wavetable music"
        "tandy" -> "Tandy music"
        "cms" -> "CMS music"
        else -> null
    }
    val usesBlaster = sfx.startsWith("sb") || sfx == "opl"
    val port = if (usesBlaster) "Port ${overrides.sbbase} · IRQ ${overrides.irq} · DMA ${overrides.dma}" else null
    return AutoTuneRow(AutoTuneRowKind.SOUND, card, listOfNotNull(music, port).joinToString(" · ").ifEmpty { "48 kHz audio" })
}

private const val UI_STATE_PREFS = "goa_ui_state"

private fun revealKey(gameId: Long) = "autotune_revealed_$gameId"

/** The full reveal plays once per game, and never when the system has animations turned off. */
private fun shouldPlayReveal(context: Context, gameId: Long): Boolean {
    val animatorScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    if (animatorScale == 0f) return false
    return !context.getSharedPreferences(UI_STATE_PREFS, Context.MODE_PRIVATE).getBoolean(revealKey(gameId), false)
}

private fun markRevealSeen(context: Context, gameId: Long) {
    context.getSharedPreferences(UI_STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(revealKey(gameId), true).apply()
}

// GOA's dark espresso card; in GOA OSS the card is a Material You secondary container instead
private val oss = BuildConfig.OSS_EDITION
private val CardTop: Color @Composable @ReadOnlyComposable get() = if (oss) MaterialTheme.colorScheme.secondaryContainer else Color(0xFF2D2320)
private val CardBottom: Color @Composable @ReadOnlyComposable get() = if (oss) MaterialTheme.colorScheme.secondaryContainer else Color(0xFF1A1310)
private val TextPrimary: Color @Composable @ReadOnlyComposable get() = if (oss) MaterialTheme.colorScheme.onSecondaryContainer else Color(0xFFFFF8F2)
private val TextSecondary: Color @Composable @ReadOnlyComposable get() =
    if (oss) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f) else Color(0xFFCFC3BA)
private val CheckGreen: Color @Composable @ReadOnlyComposable get() = if (oss) MaterialTheme.colorScheme.tertiary else Color(0xFF9CCB7A)
private val RowTint: Color @Composable @ReadOnlyComposable get() = if (oss) MaterialTheme.colorScheme.onSecondaryContainer else Color.White
private val BadgeColor: Color @Composable @ReadOnlyComposable get() = if (oss) MaterialTheme.colorScheme.tertiary else Color(0xFF5C8A3C)
private val OnBadge: Color @Composable @ReadOnlyComposable get() = if (oss) MaterialTheme.colorScheme.onTertiary else Color.White

/**
 * "Auto-tuned by GOA" card. On a game's first view it scans, reveals each tuned setting with a check mark,
 * then pulses the call to action; afterwards it renders in its final state.
 */
@Composable
fun AutoTuneShowcaseCard(
    summary: AutoTuneSummary,
    gameId: Long,
    onViewControls: () -> Unit,
    modifier: Modifier = Modifier,
    /** Rows with more to show (pre-patched settings, AI fixes) open details when tapped. */
    onRowClick: ((AutoTuneRow) -> Unit)? = null
) {
    val context = LocalContext.current
    val playReveal = remember(gameId) { shouldPlayReveal(context, gameId) }
    var scanning by remember(gameId) { mutableStateOf(playReveal) }
    var revealedRows by remember(gameId) { mutableIntStateOf(if (playReveal) 0 else summary.rows.size) }
    val cardEntrance = remember(gameId) { Animatable(if (playReveal) 0f else 1f) }
    val ctaPulse = remember(gameId) { Animatable(0f) }

    LaunchedEffect(gameId) {
        if (!playReveal) return@LaunchedEffect
        cardEntrance.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        delay(700)
        scanning = false
        repeat(summary.rows.size) {
            delay(240)
            revealedRows++
        }
        markRevealSeen(context, gameId)
        delay(200)
        repeat(3) {
            ctaPulse.animateTo(1f, tween(420))
            ctaPulse.animateTo(0f, tween(420))
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, RetroAmber.copy(alpha = 0.55f)),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = cardEntrance.value.coerceIn(0f, 1f)
                val scale = 0.94f + 0.06f * cardEntrance.value
                scaleX = scale
                scaleY = scale
                translationY = (1f - cardEntrance.value) * 24.dp.toPx()
            }
    ) {
        Box(modifier = Modifier.background(Brush.verticalGradient(listOf(CardTop, CardBottom)))) {
            Column(modifier = Modifier.padding(18.dp)) {
                ShowcaseHeader(scanning = scanning, summary = summary)
                Spacer(modifier = Modifier.height(14.dp))

                summary.rows.forEachIndexed { index, row ->
                    AnimatedVisibility(
                        visible = index < revealedRows,
                        enter = fadeIn(tween(260)) + expandVertically(tween(260)) +
                            slideInHorizontally(tween(320)) { -it / 5 }
                    ) {
                        val tappable = onRowClick != null && row.kind == AutoTuneRowKind.CONFIG
                        TunedSettingRow(
                            row = row,
                            popCheck = playReveal,
                            modifier = if (tappable) Modifier.clickable { onRowClick?.invoke(row) } else Modifier
                        )
                    }
                }

                AnimatedVisibility(
                    visible = revealedRows >= summary.rows.size,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300))
                ) {
                    ViewControlsButton(
                        confident = summary.isConfident,
                        pulse = ctaPulse.value,
                        onClick = onViewControls,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
            if (scanning) {
                ScanSweepOverlay(modifier = Modifier.matchParentSize())
            }
        }
    }
}

@Composable
private fun ShowcaseHeader(scanning: Boolean, summary: AutoTuneSummary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(RetroAmber.copy(alpha = 0.18f), CircleShape)
        ) {
            Crossfade(targetState = scanning, label = "headerIcon") { isScanning ->
                if (isScanning) {
                    val spin = rememberInfiniteTransition(label = "spin")
                    val rotation by spin.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                        label = "rotation"
                    )
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        tint = RetroAmber,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { rotationZ = rotation }
                    )
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = RetroAmber, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            // Slide the old title out before the new one lands so the two never overlap mid-transition
            AnimatedContent(
                targetState = scanning,
                transitionSpec = {
                    (slideInVertically(tween(280)) { it } + fadeIn(tween(280, delayMillis = 80)))
                        .togetherWith(slideOutVertically(tween(200)) { -it } + fadeOut(tween(120)))
                },
                label = "headerTitle"
            ) { isScanning ->
                Text(
                    text = when {
                        isScanning -> "Analyzing game files…"
                        summary.isConfident -> "Auto-tuned for this device"
                        else -> "Set up and ready to play"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    modifier = Modifier.semantics { heading() }
                )
            }
            Text(
                text = when {
                    scanning -> "Checking controls, sound card and config"
                    summary.tunedCount == 1 -> "GOA set this up so you can just press Play"
                    else -> "GOA set up ${summary.tunedCount} things so you can just press Play"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun TunedSettingRow(row: AutoTuneRow, popCheck: Boolean, modifier: Modifier = Modifier) {
    val checkScale = remember { Animatable(if (popCheck) 0f else 1f) }
    LaunchedEffect(Unit) {
        checkScale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .background(RowTint.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
        ) {
            Icon(rowIcon(row.kind), contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = row.detail,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        // Keeps the last word of a long detail line off the check mark
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Applied",
            tint = CheckGreen,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = checkScale.value
                    scaleY = checkScale.value
                }
        )
    }
}

private fun rowIcon(kind: AutoTuneRowKind): ImageVector = when (kind) {
    AutoTuneRowKind.CONTROLS -> Icons.Default.Gamepad
    AutoTuneRowKind.SOUND -> Icons.Default.GraphicEq
    AutoTuneRowKind.CONFIG -> Icons.Default.Tune
    AutoTuneRowKind.SOURCE -> Icons.AutoMirrored.Filled.MenuBook
}

@Composable
private fun ViewControlsButton(confident: Boolean, pulse: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val glow = RetroAmber
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = RetroAmber, contentColor = if (oss) MaterialTheme.colorScheme.onPrimary else Color(0xFF2D2320)
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .drawBehind {
                if (pulse > 0f) {
                    val spread = 10.dp.toPx() * pulse
                    drawRoundRect(
                        color = glow.copy(alpha = 0.45f * (1f - pulse * 0.5f)),
                        topLeft = Offset(-spread, -spread),
                        size = Size(size.width + spread * 2, size.height + spread * 2),
                        cornerRadius = CornerRadius(14.dp.toPx() + spread)
                    )
                }
            }
            .graphicsLayer {
                val scale = 1f + 0.035f * pulse
                scaleX = scale
                scaleY = scale
            }
    ) {
        Icon(Icons.Default.Gamepad, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(10.dp))
        com.goa.ui.menu.OneLineLabel(
            text = if (confident) "See your tuned controls" else "Review controls",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ScanSweepOverlay(modifier: Modifier = Modifier) {
    val sweep = rememberInfiniteTransition(label = "scanSweep")
    val position by sweep.animateFloat(
        initialValue = -0.25f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Restart),
        label = "scanPosition"
    )
    val sweepColor = RetroAmber.copy(alpha = 0.28f)
    Box(
        modifier = modifier.drawWithContent {
            val center = size.width * position
            val halfWidth = size.width * 0.18f
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, sweepColor, Color.Transparent),
                    startX = center - halfWidth,
                    endX = center + halfWidth
                )
            )
        }
    )
}

/** Hero chip announcing that GOA tuned this game. Pops in once per composition of the detail pane. */
@Composable
fun AutoTunedBadge(confident: Boolean, modifier: Modifier = Modifier) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = BadgeColor,
        modifier = modifier.graphicsLayer {
            scaleX = entrance.value
            scaleY = entrance.value
            alpha = entrance.value.coerceIn(0f, 1f)
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(Icons.Default.Bolt, contentDescription = null, tint = OnBadge, modifier = Modifier.size(12.dp))
            Text(
                text = if (confident) "AUTO-TUNED" else "AUTO-SETUP",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = OnBadge,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
