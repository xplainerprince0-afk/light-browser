package com.rg.webloom.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Human-gesture point math for `b gesture` (pure Kotlin, no Android deps).
 * Everything is CSS px — scaling to view px lives only in deliverTouch.
 *
 * Flow the user asked for: record a real finger path once (recorder captures
 * touchmove trails as op=gesture), then `b gesture replay <rec> [--seed N]`
 * replays it with a random human-like transform so the agent never repeats
 * identical coordinates (bot walls flag exact repeats).
 */
object GestureGen {
    const val MAX_PTS = 64

    /** Circle polyline (closed): n segments, starts at 3 o'clock, clockwise. */
    fun circle(cx: Float, cy: Float, r: Float, n: Int = 28): List<Pair<Float, Float>> {
        val segs = n.coerceIn(8, MAX_PTS)
        val rr = r.coerceIn(4f, 4000f)
        return (0..segs).map { i ->
            val a = 2 * PI * i / segs
            (cx + rr * cos(a)).toFloat() to (cy + rr * sin(a)).toFloat()
        }
    }

    /** Drunk walk inside a box: human scribble/doodle. Seeded for replayability. */
    fun scribble(
        x1: Float, y1: Float, x2: Float, y2: Float,
        steps: Int = 24, seed: Long = System.nanoTime()
    ): List<Pair<Float, Float>> {
        val rnd = java.util.Random(seed)
        val lx = minOf(x1, x2).coerceAtLeast(0f); val rx = maxOf(x1, x2)
        val ty = minOf(y1, y2).coerceAtLeast(0f); val by = maxOf(y1, y2)
        if (rx - lx < 8f || by - ty < 8f) return listOf(lx to ty, rx to by)
        var x = lx + rnd.nextFloat() * (rx - lx)
        var y = ty + rnd.nextFloat() * (by - ty)
        val pts = mutableListOf(x to y)
        val n = steps.coerceIn(4, MAX_PTS - 1)
        repeat(n) {
            val ang = rnd.nextFloat() * 2 * PI
            val len = (8 + rnd.nextFloat() * 36).toFloat()
            x = (x + len * cos(ang)).toFloat().coerceIn(lx, rx)
            y = (y + len * sin(ang)).toFloat().coerceIn(ty, by)
            pts.add(x to y)
        }
        return pts
    }

    /** Parse `"x1,y1 x2,y2 …"` (also accepts `x1 y1 x2 y2 …`). Caps at MAX_PTS. */
    fun parsePath(raw: String): List<Pair<Float, Float>>? {
        return try {
            val nums = raw.replace(",", " ").trim().split(Regex("\\s+"))
                .mapNotNull { it.toFloatOrNull() }
            if (nums.size < 4 || nums.size % 2 != 0) return null
            val pts = nums.chunked(2).map { it[0] to it[1] }
                .filter { it.first.isFinite() && it.second.isFinite() }
                .filter { kotlin.math.abs(it.first) <= 20000f && kotlin.math.abs(it.second) <= 20000f }
            if (pts.size < 2) return null
            pts.take(MAX_PTS)
        } catch (_: Exception) { null }
    }

    fun formatPath(pts: List<Pair<Float, Float>>): String =
        pts.joinToString(" ") { (x, y) -> "${Math.round(x)},${Math.round(y)}" }

    /**
     * Human-like variation of a recorded path: translate ±jitter px, uniform
     * scale 0.92–1.08, rotate ±7°, tempo 0.9–1.15×. Same seed ⇒ same output
     * (log the seed so agent runs are reproducible). Coords clamped ≥ 0.
     */
    fun humanize(
        pts: List<Pair<Float, Float>>, ms: Long, seed: Long,
        jitter: Float = 36f
    ): Pair<List<Pair<Float, Float>>, Long> {
        if (pts.size < 2) return pts to ms
        return try {
            val rnd = java.util.Random(seed)
            val cx = (pts.sumOf { it.first.toDouble() } / pts.size).toFloat()
            val cy = (pts.sumOf { it.second.toDouble() } / pts.size).toFloat()
            val dx = (rnd.nextFloat() - 0.5f) * 2 * jitter
            val dy = (rnd.nextFloat() - 0.5f) * 2 * jitter
            val sc = 0.92f + rnd.nextFloat() * 0.16f
            val ra = Math.toRadians((rnd.nextFloat() - 0.5f) * 14.0)
            val cosA = cos(ra).toFloat(); val sinA = sin(ra).toFloat()
            val out = pts.map { (x, y) ->
                val lx = (x - cx) * sc; val ly = (y - cy) * sc
                val rx = (lx * cosA - ly * sinA + cx + dx).coerceAtLeast(0f)
                val ry = (lx * sinA + ly * cosA + cy + dy).coerceAtLeast(0f)
                rx to ry
            }
            // Drop accidental duplicates (Chromium may drop zero-length MOVEs mid-stroke).
            val dedup = out.filterIndexed { i, p -> i == 0 || p != out[i - 1] }
            val tempo = 0.9 + rnd.nextFloat() * 0.25
            (if (dedup.size >= 2) dedup else out) to (ms * tempo).toLong().coerceIn(100, 5000)
        } catch (_: Exception) { pts to ms }
    }
}
