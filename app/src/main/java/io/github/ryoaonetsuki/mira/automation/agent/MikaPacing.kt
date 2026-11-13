/*
 * Ported from Mika (github.com/wangzan101/Mika), Apache License 2.0.
 * Copyright 2026 wangzan101. Original code used with attribution.
 * Adaptations: renamed package, inlined Pace helpers, combined TypingPacing + GesturePacing.
 */
package io.github.ryoaonetsuki.mira.automation.agent

import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

// ── Inlined Pace helpers ────────────────────────────────────────────────────

/** Pace.pick equivalent for LongRange */
private fun Random.pick(range: LongRange): Long = nextLong(range.first, range.last + 1)

/** Pace.pick equivalent for IntRange */
private fun Random.pick(range: IntRange): Int = nextInt(range.first, range.last + 1)

/** Pace.bow: perpendicular offset for the arc, capped at maxBowPx. */
private fun bow(r: Random, length: Double, bowFraction: Double, maxBowPx: Double): Double {
    val mag = minOf(length * bowFraction, maxBowPx)
    return r.nextDouble(-mag, mag)
}

/**
 * Pace.control: quadratic Bézier control point that bends the line from [start] to [end]
 * perpendicularly by [offset] units (positive = left of travel direction).
 */
private fun control(
    start: Pair<Double, Double>,
    end: Pair<Double, Double>,
    offset: Double,
): Pair<Double, Double> {
    val mx = (start.first + end.first) / 2.0
    val my = (start.second + end.second) / 2.0
    val dx = end.first - start.first
    val dy = end.second - start.second
    val len = hypot(dx, dy).takeIf { it > 0 } ?: return mx to my
    // perpendicular unit vector (rotated 90° counter-clockwise)
    val px = -dy / len
    val py = dx / len
    return mx + px * offset to my + py * offset
}

/** Pace.bezier: point on quadratic Bézier (start, control, end) at parameter t ∈ [0,1]. */
private fun bezier(
    start: Pair<Double, Double>,
    c: Pair<Double, Double>,
    end: Pair<Double, Double>,
    t: Double,
): Pair<Double, Double> {
    val u = 1 - t
    return (u * u * start.first + 2 * u * t * c.first + t * t * end.first) to
            (u * u * start.second + 2 * u * t * c.second + t * t * end.second)
}

// ── TypingPacing ─────────────────────────────────────────────────────────────

/**
 * 往输入框里打字的节奏(纯逻辑,不碰输入框):人打字是一个字一个字出来的,中日韩文字经输入法一个词一个词地上屏,
 * 字与字之间快慢不一,标点、空格之后偶尔停一下。[plan] 把一段字排成一串越来越长的前缀和每一段之前等多久;
 * 最后一段总是全文。
 *
 * [Instant] 一次写入全文、不等待(「快」、测试、不需要节奏的调用方用它)。
 */
class TypingPacing(private val random: Random?, val limits: Limits = Limits()) {
    /** 时长单位毫秒。 */
    data class Limits(
        val latinMs: LongRange = 60L..170L,
        val cjkMs: LongRange = 160L..380L,
        val cjkGroup: IntRange = 1..3,
        val pauseChance: Double = 0.05,
        val pauseMs: LongRange = 300L..900L,
        val maxSteps: Int = 40,
        val maxTotalMs: Long = 6_000L,
    ) {
        companion object {
            /** 限制浏览快慢的 App(和「像人」时所有 App):打得更慢、停得更多,长消息至多 12 秒。 */
            val CAREFUL = Limits(
                latinMs = 90L..230L, cjkMs = 220L..520L, pauseChance = 0.1, pauseMs = 500L..1_400L, maxSteps = 60, maxTotalMs = 12_000L,
            )
        }
    }

    /** 一段:等 [delayMs] 之后,输入框里是全文的前 [end] 个 UTF-16 单位。 */
    data class Step(val end: Int, val delayMs: Long)

    /** 有节奏(不是 [Instant])。 */
    val paced: Boolean get() = random != null

    /** 把 [text] 排成一段一段;[Instant]、空串时只有一段(全文、不等)。 */
    fun plan(text: String): List<Step> {
        val r = random ?: return listOf(Step(text.length, 0))
        val units = units(text)
        if (units.size <= 1) return listOf(Step(text.length, r.pick(limits.latinMs)))
        val per = ceil(units.size.toDouble() / limits.maxSteps).toInt().coerceAtLeast(1)
        val steps = mutableListOf<Step>()
        var i = 0
        while (i < units.size) {
            val group = if (isCjk(text, units[i])) {
                val want = r.pick(limits.cjkGroup)
                var k = 1
                while (k < want && i + k < units.size && isCjk(text, units[i + k])) k++
                k
            } else 1
            val upto = minOf(i + maxOf(group, per), units.size)
            var wait = 0L
            for (j in i until upto) wait += r.pick(if (isCjk(text, units[j])) limits.cjkMs else limits.latinMs)
            val end = if (upto == units.size) text.length else units[upto]
            val last = text.codePointBefore(end)
            val boundary = Character.isWhitespace(last) || isPunctuation(last)
            if (r.nextDouble() < limits.pauseChance * (if (boundary) 2 else 1)) wait += r.pick(limits.pauseMs)
            steps += Step(end, wait)
            i = upto
        }
        val total = steps.sumOf { it.delayMs }
        if (total <= limits.maxTotalMs) return steps
        val scale = limits.maxTotalMs.toDouble() / total
        val scaled = steps.map { it.copy(delayMs = (it.delayMs * scale).roundToLong()) }
        // Per-step rounding can push the sum a few ms over the cap (upstream Mika
        // has the same rounding); shave the overage off the largest step so the
        // total never exceeds maxTotalMs.
        val over = scaled.sumOf { it.delayMs } - limits.maxTotalMs
        if (over <= 0) return scaled
        val idx = scaled.indices.maxByOrNull { scaled[it].delayMs } ?: return scaled
        return scaled.toMutableList().also { it[idx] = it[idx].copy(delayMs = (it[idx].delayMs - over).coerceAtLeast(0)) }
    }

    /**
     * 对这个输入框用的节奏:密码框([secret])不分段;要粘贴才收得到输入的网页框([web])不分段。
     */
    fun forField(web: Boolean, secret: Boolean): TypingPacing = if (web || secret) Instant else this

    /**
     * 按 [plan] 一段一段把 [text] 的前缀写进 [field],全文那一段留给调用方。
     * 写完了所有前缀返回 true;不分段([Instant]、只有一个字)时什么也不写,返回 false。
     */
    suspend fun leadIn(field: TextField, text: String, sleep: suspend (Long) -> Unit = { delay(it) }): Boolean {
        val steps = plan(text)
        if (steps.size < 2) return false
        for ((k, s) in steps.dropLast(1).withIndex()) {
            sleep(s.delayMs)
            val prefix = text.substring(0, s.end)
            if (!field.write(prefix)) return false
            if (k == 0) {
                val back = field.read() ?: return false
                if (back.filterNot { it.isWhitespace() } != prefix.filterNot { it.isWhitespace() }) return false
            }
        }
        sleep(steps.last().delayMs)
        return true
    }

    companion object {
        /** 一次写入全文。 */
        val Instant = TypingPacing(null)

        /** 像人打字的节奏;[random] 可换成固定种子(测试)。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = TypingPacing(random, limits)

        /** [text] 里每个字的起点(UTF-16 下标)。 */
        internal fun units(text: String): List<Int> {
            val out = mutableListOf<Int>()
            var i = 0
            var joinNext = false
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val attach = out.isNotEmpty() && (joinNext || attaches(cp))
                if (!attach) out += i
                joinNext = cp == ZWJ
                i += Character.charCount(cp)
            }
            return out
        }

        private const val ZWJ = 0x200D

        private fun attaches(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> cp == ZWJ || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF
        }

        private fun isCjk(text: String, at: Int): Boolean = when (Character.UnicodeScript.of(text.codePointAt(at))) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
            else -> false
        }

        private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }
}

// ── GesturePacing ─────────────────────────────────────────────────────────────

/** Double-tap gap used by [GesturePacing.Instant]. */
private const val DOUBLE_TAP_GAP_MS = 120L

/**
 * 手机手势的节奏:手指落下前停一下、点按按住的时长有长有短、落点在元素中心附近略有偏差、
 * 滑动沿一小段弧线走、先加速后减速、时长在给定值上下浮动。
 * [Instant] 不加任何停顿、偏差和弧度,时长和以前一样。
 */
class GesturePacing(private val random: Random?, val limits: Limits = Limits()) {
    /**
     * 上下限。时长单位毫秒,距离单位像素。
     */
    data class Limits(
        val approachMs: LongRange = 30L..120L,
        val pressMs: LongRange = 50L..120L,
        val doublePressMs: LongRange = 40L..90L,
        val doubleGapMs: LongRange = 80L..140L,
        val jitterFraction: Double = 0.2,
        val maxJitterPx: Int = 12,
        val minJitterSpanPx: Int = 24,
        val pointJitterPx: Int = 3,
        val durationJitter: Double = 0.08,
        val holdStretch: Double = 0.12,
        val stillPx: Double = 3.0,
        val bowFraction: Double = 0.06,
        val maxBowPx: Double = 30.0,
        val sampleMs: LongRange = 8L..16L,
        val maxPoints: Int = 600,
        val dragMs: Long = 800L,
        val flingEndSpeed: ClosedFloatingPointRange<Double> = 0.9..1.0,
    )

    /** 一笔里的一个点:相对落下时刻 [t] 毫秒时手指在([x],[y])。 */
    data class Point(val x: Double, val y: Double, val t: Long)

    /** 一笔:从按下到抬起的点(第一个 t 为 0,最后一个是抬起的时刻和位置)。 */
    data class Stroke(val points: List<Point>) {
        val durationMs: Long get() = points.last().t
    }

    /** 有节奏(不是 [Instant])。 */
    val paced: Boolean get() = random != null

    fun approach(): Long = pickMs(limits.approachMs, 0L)
    fun press(): Long = pickMs(limits.pressMs, TAP_MS)
    fun doublePress(): Long = pickMs(limits.doublePressMs, TAP_MS)
    fun doubleGap(): Long = pickMs(limits.doubleGapMs, DOUBLE_TAP_GAP_MS)

    private fun pickMs(r: LongRange, instant: Long): Long = random?.pick(r) ?: instant

    /**
     * 点元素([left],[top])–([right],[bottom])(右、下不含)时的落点。
     */
    fun within(left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int> {
        val cx = (left + right) shr 1
        val cy = (top + bottom) shr 1
        val r = random ?: return cx to cy
        fun reach(span: Int) = if (span < limits.minJitterSpanPx) 0 else minOf((span * limits.jitterFraction).toInt(), limits.maxJitterPx)
        return (cx + offset(r, reach(right - left))).coerceIn(left, maxOf(left, right - 1)) to
                (cy + offset(r, reach(bottom - top))).coerceIn(top, maxOf(top, bottom - 1))
    }

    /** 按坐标点时的落点:给定点附近 [Limits.pointJitterPx] 以内;坐标不小于 0。 */
    fun near(x: Int, y: Int): Pair<Int, Int> {
        val r = random ?: return x to y
        return (x + offset(r, limits.pointJitterPx)).coerceAtLeast(0) to (y + offset(r, limits.pointJitterPx)).coerceAtLeast(0)
    }

    // 两个均匀数之和(三角分布)
    private fun offset(r: Random, reach: Int): Int =
        if (reach <= 0) 0 else ((r.nextDouble() + r.nextDouble() - 1.0) * reach).roundToInt().coerceIn(-reach, reach)

    /** 几根手指一起动的手势的时长:给定值上下浮动 [Limits.durationJitter]。 */
    fun duration(durationMs: Long): Long {
        val r = random ?: return durationMs
        return (durationMs * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
    }

    /**
     * 一根手指沿 [path](至少两个点)走 [durationMs] 左右的一笔。
     * [Instant]:点就是 [path],按路径长短匀速分配时间,时长正是 [durationMs]。
     */
    fun stroke(path: List<Pair<Double, Double>>, durationMs: Long, bow: Boolean = false, bounds: Pair<Int, Int>? = null): Stroke {
        require(path.size >= 2) { "a stroke needs at least two points" }
        val total = durationMs.coerceAtLeast(1)
        val r = random ?: return Stroke(uniform(path, total))
        val start = path.first()
        val end = path.last()
        val length = lengthOf(path)
        if (length < limits.stillPx) {
            val hold = (total * (1 + r.nextDouble(0.0, limits.holdStretch))).roundToLong()
            return Stroke(listOf(Point(start.first, start.second, 0), Point(end.first, end.second, hold)))
        }
        val t = (total * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
        val shape = if (bow && path.size == 2) {
            val bowOffset = bow(r, length, limits.bowFraction, limits.maxBowPx)
            val c = control(start, end, bowOffset)
            (0..CURVE_SEGMENTS).map { bezier(start, c, end, it.toDouble() / CURVE_SEGMENTS) }
        } else path
        val endSpeed = if (t >= limits.dragMs) 0.0 else r.nextDouble(limits.flingEndSpeed.start, limits.flingEndSpeed.endInclusive)
        val step = r.pick(limits.sampleMs).coerceAtLeast(1)
        val n = ceil(t.toDouble() / step).toInt().coerceIn(1, limits.maxPoints - 1)
        val cum = cumulative(shape)
        val points = (0..n).map { i ->
            val tm = if (i == n) t else (t * i.toDouble() / n).roundToLong()
            val (x, y) = when (i) {
                0 -> start
                n -> end
                else -> at(shape, cum, cum.last() * progress(i.toDouble() / n, endSpeed))
            }
            Point(x, y, tm)
        }
        return Stroke(if (bounds == null) points else points.map { it.copy(x = it.x.coerceIn(0.0, bounds.first - 1.0), y = it.y.coerceIn(0.0, bounds.second - 1.0)) })
    }

    companion object {
        /** 不加节奏时点按按住的时长。 */
        const val TAP_MS = 50L

        /** 弧线拆成这么多小段再按时间取点。 */
        private const val CURVE_SEGMENTS = 32

        /** 不加停顿、偏差和弧度。 */
        val Instant = GesturePacing(null)

        /** 像人手的节奏;[random] 可换成固定种子(测试)。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = GesturePacing(random, limits)

        /**
         * 时间走到 [u](0..1)时走过的路程比例:三次 Hermite,起步速度 0,收尾速度为平均速度的 [endSpeed] 倍。
         */
        internal fun progress(u: Double, endSpeed: Double): Double {
            val u2 = u * u
            val u3 = u2 * u
            return (-2 * u3 + 3 * u2) + endSpeed * (u3 - u2)
        }

        private fun lengthOf(path: List<Pair<Double, Double>>): Double = cumulative(path).last()

        private fun cumulative(path: List<Pair<Double, Double>>): DoubleArray {
            val out = DoubleArray(path.size)
            for (i in 1 until path.size) out[i] = out[i - 1] + hypot(path[i].first - path[i - 1].first, path[i].second - path[i - 1].second)
            return out
        }

        /** 沿折线走过 [s] 时的位置。 */
        private fun at(path: List<Pair<Double, Double>>, cum: DoubleArray, s: Double): Pair<Double, Double> {
            var i = 1
            while (i < path.size - 1 && cum[i] < s) i++
            val seg = cum[i] - cum[i - 1]
            val f = if (seg <= 0) 1.0 else ((s - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
            val (x0, y0) = path[i - 1]
            val (x1, y1) = path[i]
            return x0 + (x1 - x0) * f to y0 + (y1 - y0) * f
        }

        /** 不加节奏时的一笔:就是给定的点,时间按路程匀速分配。 */
        private fun uniform(path: List<Pair<Double, Double>>, total: Long): List<Point> {
            val cum = cumulative(path)
            val length = cum.last()
            return path.mapIndexed { i, (x, y) ->
                val t = when {
                    i == path.lastIndex -> total
                    length <= 0 -> 0L
                    else -> (total * cum[i] / length).roundToLong()
                }
                Point(x, y, t)
            }
        }
    }
}
