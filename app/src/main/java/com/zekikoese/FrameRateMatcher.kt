package com.zekikoese

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/** Anzeigemodus des Fernsehers (Auflösung + Bildwiederholrate). */
data class DisplayModeSpec(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/** Gängige Video-Bildraten, auf die gemessene Werte eingerastet werden. */
private val STANDARD_FRAME_RATES = floatArrayOf(
    23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f
)

/**
 * Rastet eine gemessene/gemeldete Bildrate auf den nächsten Standardwert ein (Toleranz 1,5 %).
 * Null, wenn der Wert zu keinem Standard passt — dann wird der Anzeigemodus nicht angetastet.
 */
fun snapFrameRate(fps: Float): Float? {
    if (fps <= 0f || fps.isNaN()) return null
    val nearest = STANDARD_FRAME_RATES.minBy { abs(it - fps) }
    return nearest.takeIf { abs(it - fps) / it <= 0.015f }
}

/**
 * Wählt den Anzeigemodus, dessen Bildwiederholrate ein ganzzahliges Vielfaches von [fps] ist
 * (gleiche Auflösung wie [current]). Bevorzugt werden exakte Treffer, dann das kleinste
 * Vielfache (50 Hz vor 100 Hz für 25 fps). Null, wenn [current] schon passt oder nichts passt.
 */
fun pickDisplayMode(modes: List<DisplayModeSpec>, current: DisplayModeSpec, fps: Float): Int? {
    data class Candidate(val mode: DisplayModeSpec, val multiple: Int, val error: Float)

    fun candidateFor(mode: DisplayModeSpec): Candidate? {
        val ratio = mode.refreshRate / fps
        val multiple = ratio.roundToInt()
        if (multiple < 1) return null
        val error = abs(ratio - multiple) / multiple
        return if (error <= 0.0015f) Candidate(mode, multiple, error) else null
    }

    val best = modes
        .filter { it.width == current.width && it.height == current.height }
        .mapNotNull(::candidateFor)
        // Exakte Treffer (<0,05 %: 23,976 statt 24 Hz) zuerst, dann kleinstes Vielfaches.
        .sortedWith(compareBy({ if (it.error < 0.0005f) 0 else 1 }, { it.multiple }, { it.error }))
        .firstOrNull() ?: return null
    val currentFits = candidateFor(current)
    if (currentFits != null && currentFits.multiple <= best.multiple && currentFits.error <= best.error + 1e-6f) return null
    return best.mode.id.takeIf { it != current.id }
}

/**
 * Misst die Bildrate aus den Präsentationszeitstempeln der ersten Frames (IPTV-TS-Streams
 * melden sie oft nicht im Format). Median der Abstände — robust gegen einzelne Aussetzer.
 */
class FrameRateEstimator(private val samplesNeeded: Int = 40) {
    private var lastPtsUs = Long.MIN_VALUE
    private val deltas = ArrayList<Long>(samplesNeeded)

    fun reset() {
        lastPtsUs = Long.MIN_VALUE
        deltas.clear()
    }

    /** Liefert einmalig die geschätzte Bildrate, sobald genug Frames gesehen wurden. */
    fun onFrame(presentationTimeUs: Long): Float? {
        if (deltas.size >= samplesNeeded) return null
        val last = lastPtsUs
        lastPtsUs = presentationTimeUs
        if (last == Long.MIN_VALUE) return null
        val delta = presentationTimeUs - last
        if (delta in 1..200_000) deltas.add(delta) // >200 ms: Sprung/Seek, ignorieren
        if (deltas.size < samplesNeeded) return null
        val median = deltas.sorted()[deltas.size / 2]
        return 1_000_000f / median
    }
}

/**
 * Schaltet den Anzeigemodus des Fernsehers passend zur Bildrate um (Fire TV / Android TV
 * unterstützen preferredDisplayModeId) und stellt beim Verlassen des Players den vorherigen
 * Wunschmodus wieder her.
 */
class FrameRateController(private val activity: Activity) {
    private val originalPreferredMode = activity.window.attributes.preferredDisplayModeId
    private var appliedFor: Float? = null

    fun onFrameRate(rawFps: Float) {
        val fps = snapFrameRate(rawFps) ?: return
        if (appliedFor == fps) return
        appliedFor = fps
        val display = currentDisplay() ?: return
        val modes = display.supportedModes.map { it.toSpec() }
        val target = pickDisplayMode(modes, display.mode.toSpec(), fps) ?: return
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = target
        activity.window.attributes = attributes
    }

    fun restore() {
        appliedFor = null
        val attributes = activity.window.attributes
        if (attributes.preferredDisplayModeId != originalPreferredMode) {
            attributes.preferredDisplayModeId = originalPreferredMode
            activity.window.attributes = attributes
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay

    private fun Display.Mode.toSpec() = DisplayModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)
}
