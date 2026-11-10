package com.aismartmath.board.board

import com.aismartmath.board.math.angDeg
import com.aismartmath.board.math.dist
import com.aismartmath.board.model.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Question tab, phase 1: triangles. No AI and no network. The question text is read with patterns into facts,
 * the facts are solved with the angle sum, base angles, Pythagoras, sine and cosine rules, a figure is built from the
 * numbers and then CHECKED against the facts of the question. A figure that fails the check is thrown away and the
 * next way of drawing it is tried. Every stage writes a line to [QResult.steps] (the status bar).
 */

/** A figure ready for the board: three points (in the order of [names]) and what to show on it. */
/** One figure of a drawing: t = tri, quad or seg. nm = its letters. */
class QFigSpec(
    val t: String,
    val pts: List<Pt>,
    val nm: String?,
    val showSides: Set<Int> = emptySet(),
    val showAngles: Set<Int> = emptySet(),
    val ov: Set<String> = emptySet(),
)

class QPlan(
    val names: String,
    val pts: List<Pt>,
    val showSides: Set<Int>,
    val showAngles: Set<Int>,
    val extras: Set<String>,
    val title: String,
    /** when not empty these figures are drawn instead of the single triangle above (proofs with several lines) */
    val figs: List<QFigSpec> = emptyList(),
)

class QResult(
    val steps: List<String>,
    val answers: List<String>,
    val plan: QPlan?,
    val problem: String?,
    /** what the engine weighed before drawing (shown first, like thinking) */
    val thinking: List<String> = emptyList(),
    /** the solution, line by line, written on the board (typed maths: fractions are drawn by the maths view) */
    val board: List<String> = emptyList(),
)

/** k * x + c */
internal class Lin(val k: Double, val c: Double)

/** a0*t0 + a1*t1 + a2*t2 + a3*x = rhs  (t = the three angles, x = the unknown of the question). */
private class Eq(val a: DoubleArray, val rhs: Double, val why: String)

private class GSol(val ok: Boolean, val v: DoubleArray, val known: BooleanArray)

private class St(
    val ang: Array<Double?>,
    val side: Array<Double?>,
    var x: Double?,
    val steps: MutableList<String>,
    var bad: String?,
)

private class Facts(val names: String) {
    val angLin = arrayOfNulls<Lin>(3)
    val rel = mutableListOf<Triple<Int, Int, Double>>()
    var ratio: DoubleArray? = null
    val sideVal = arrayOfNulls<Double>(3) // by the vertex the side is opposite to
    val sideEq = mutableListOf<List<Int>>() // groups of equal sides (opposite vertices)
    var right = -1
    var acute = false
    var obtuse = false
    var wantX = false
    val goalAlt = mutableSetOf<Int>()
    val goalMed = mutableSetOf<Int>()
    val goalExt = mutableSetOf<Int>()
    var goalR = false
    var goalr = false
    val goalAng = mutableSetOf<Int>()
    val goalSide = mutableSetOf<Int>()
    var goalArea = false
    var goalPerimeter = false
    var goalAll = false
    var goalBase = false
    var goalApex = false
    var proof = false
    val extras = mutableSetOf<String>()
    val notes = mutableListOf<String>()

    fun an(i: Int) = "∠" + names[i]
    fun sn(o: Int): String {
        val i = (o + 1) % 3
        val j = (o + 2) % 3
        return names[minOf(i, j)].toString() + names[maxOf(i, j)].toString()
    }

    fun equations(): List<Eq> {
        val out = mutableListOf<Eq>()
        out.add(Eq(doubleArrayOf(1.0, 1.0, 1.0, 0.0), 180.0, an(0) + " + " + an(1) + " + " + an(2) + " = 180° (angle sum of a triangle)"))
        if (right >= 0) {
            val a = DoubleArray(4)
            a[right] = 1.0
            out.add(Eq(a, 90.0, an(right) + " = 90° (right angle)"))
        }
        for (i in 0..2) {
            val l = angLin[i] ?: continue
            val a = DoubleArray(4)
            a[i] = 1.0
            a[3] = -l.k
            out.add(Eq(a, l.c, an(i) + " = " + linText(l) + " (given)"))
        }
        for ((i, j, k) in rel) {
            val a = DoubleArray(4)
            a[i] = 1.0
            a[j] = a[j] - k
            out.add(Eq(a, 0.0, an(i) + " = " + (if (k == 1.0) "" else qf(k) + " × ") + an(j) + " (given)"))
        }
        for (g in sideEq) {
            for (n in 0 until g.size - 1) {
                val a = DoubleArray(4)
                a[g[n]] = 1.0
                a[g[n + 1]] = -1.0
                out.add(Eq(a, 0.0, sn(g[n]) + " = " + sn(g[n + 1]) + " ⇒ " + an(g[n]) + " = " + an(g[n + 1]) + " (equal sides lie opposite equal angles)"))
            }
        }
        val r = ratio
        if (r != null) {
            for (n in 0..1) {
                val a = DoubleArray(4)
                a[n] = r[n + 1]
                a[n + 1] = -r[n]
                out.add(Eq(a, 0.0, an(n) + " : " + an(n + 1) + " = " + qf(r[n]) + " : " + qf(r[n + 1]) + " (given ratio)"))
            }
        }
        return out
    }
}

/** 70 or 70.5, never 70.0000001 */
internal fun qf(v: Double): String {
    val r = round(v * 100) / 100
    return if (abs(r - round(r)) < 1e-9) round(r).toLong().toString() else r.toString()
}

internal fun linText(l: Lin): String {
    if (l.k == 0.0) return qf(l.c)
    val kx = (if (l.k == 1.0) "x" else if (l.k == -1.0) "-x" else qf(l.k) + "x")
    return when {
        l.c > 0 -> kx + " + " + qf(l.c)
        l.c < 0 -> kx + " − " + qf(-l.c)
        else -> kx
    }
}

internal fun parseLin(s0: String): Lin? {
    val s = s0.replace(" ", "").replace("°", "").replace("(", "").replace(")", "").replace("X", "x").replace("*", "")
    if (s.isEmpty()) return null
    var k = 0.0
    var c = 0.0
    val terms = Regex("[+-]?[^+-]+").findAll(s).map { it.value }.toList()
    if (terms.joinToString("") != s) return null
    for (t in terms) {
        if (t.endsWith("x")) {
            val co = t.dropLast(1)
            val cv = when (co) {
                "", "+" -> 1.0
                "-" -> -1.0
                else -> co.toDoubleOrNull() ?: return null
            }
            k += cv
        } else {
            c += t.toDoubleOrNull() ?: return null
        }
    }
    return Lin(k, c)
}

private fun gauss(eqs: List<Eq>): GSol {
    val n = 4
    val m = Array(eqs.size) { r -> DoubleArray(n + 1) { c -> if (c < n) eqs[r].a[c] else eqs[r].rhs } }
    val pivotCol = IntArray(m.size) { -1 }
    var row = 0
    for (col in 0 until n) {
        if (row >= m.size) break
        var best = -1
        var bv = 1e-9
        for (r in row until m.size) {
            if (abs(m[r][col]) > bv) {
                bv = abs(m[r][col])
                best = r
            }
        }
        if (best < 0) continue
        val tmp = m[row]
        m[row] = m[best]
        m[best] = tmp
        val pv = m[row][col]
        for (c in 0..n) m[row][c] = m[row][c] / pv
        for (r in m.indices) {
            if (r != row) {
                val f = m[r][col]
                if (abs(f) > 0.0) {
                    for (c in 0..n) m[r][c] = m[r][c] - f * m[row][c]
                }
            }
        }
        pivotCol[row] = col
        row++
    }
    var ok = true
    for (r in m.indices) {
        var allZero = true
        for (c in 0 until n) if (abs(m[r][c]) > 1e-9) allZero = false
        if (allZero && abs(m[r][n]) > 1e-6) ok = false
    }
    val v = DoubleArray(n)
    val known = BooleanArray(n)
    for (r in m.indices) {
        val pc = pivotCol[r]
        if (pc < 0) continue
        var alone = true
        for (c in 0 until n) if (c != pc && abs(m[r][c]) > 1e-9) alone = false
        if (alone) {
            v[pc] = m[r][n]
            known[pc] = true
        }
    }
    return GSol(ok, v, known)
}

object QuestionSolver {
    private val NUM = "\\d+(?:\\.\\d+)?"
    private val ANG = "(?:∠|(?i:angle)\\s+(?:(?i:at)\\s+)?)\\s*"

    private val nameRx = Regex("(?i:triangle)\\s+([A-Z]{3})(?![A-Za-z])")
    private val isRx = Regex("\\b([A-Z]{3})\\s+(?i:is)\\s+(?i:an?)\\s+(?i:isosceles|right|equilateral|scalene|acute|obtuse)")
    private val anyName = Regex("\\b([A-Z]{3})\\b")
    private val relRx = Regex(ANG + "([A-Z]{1,3})\\s*=\\s*(" + NUM + ")?\\s*\\*?\\s*" + ANG + "([A-Z]{1,3})")
    private val angEq = Regex(ANG + "([A-Z]{1,3})\\s*(?:=|(?i:is|equals|measures)\\s)\\s*([0-9xX(+\\-][0-9xX.+\\-\\s()]*)")
    private val chainRx = Regex("\\b[A-Z]{2}\\b(?:\\s*=\\s*(?:\\b[A-Z]{2}\\b|" + NUM + "(?:\\s*(?:cm|mm|m|units?))?))+")
    private val perpRx = Regex("\\b([A-Z]{2})\\s*(?:⊥|(?i:perpendicular\\s+to|perp\\s+to))\\s*([A-Z]{2})\\b")
    private val rightAt = Regex("(?i:right[- ]?angle[d]?)\\s*(?:(?i:at|in)\\s*)(?:(?i:vertex)\\s*)?([A-Z])\\b")
    private val hypRx = Regex("(?i:hypotenuse)\\s*(?:(?i:is|of|measures|=)\\s*)?(" + NUM + ")")
    private val legsRx = Regex("(?i:legs?|catheti)\\s*(?:(?i:are|of|=)\\s*)?(" + NUM + ")\\s*(?:cm|m|units?)?\\s*(?:(?i:and)|,)\\s*(" + NUM + ")")
    private val sidesRx = Regex("(?i:sides)\\s*(?:(?i:are|of|=)\\s*)?(" + NUM + ")\\s*(?:cm|m|units?)?\\s*(?:,|(?i:and))\\s*(" + NUM + ")\\s*(?:cm|m|units?)?\\s*(?:,|(?i:and))\\s*(" + NUM + ")")
    private val ratioRx = Regex("(?i:ratio)\\s*(?:(?i:of)\\s*)?(" + NUM + ")\\s*:\\s*(" + NUM + ")\\s*:\\s*(" + NUM + ")")
    private val findRx = Regex("(?i)\\b(?:find|calculate|determine|compute|evaluate|what\\s+is|what\\s+are)\\b")
    private val angTok = Regex(ANG + "([A-Z]{1,3})")
    private val sideTok = Regex("\\b([A-Z]{2})\\b")

    private val trigGiven = Regex("(?i:\\b(sin|cos|tan))\\s*(?:∠\\s*)?([A-Z])\\s*=\\s*([0-9.]+(?:\\s*/\\s*[0-9.]+)?|√\\s*\\d+(?:\\s*/\\s*\\d+)?|sqrt\\(\\d+\\)(?:\\s*/\\s*\\d+)?)")

    /**
     * The one entry point of the Question tab. variant 0 is the first reading; each time the person taps ✗ the variant
     * goes up and the solver changes its assumptions (which corner is the right angle, which sides are equal, the
     * example values, the way the figure is placed, the quadrant of an angle).
     */
    fun solve(raw: String, variant: Int = 0): QResult {
        if (ProofSolver.looksProof(raw)) {
            val pr = ProofSolver.solve(raw, variant)
            if (pr != null) return pr
        }
        if (PythagorasSolver.looks(raw)) {
            val py = PythagorasSolver.solve(raw, variant)
            if (py != null) return py
        }
        if (AngleSolver.looksAngle(raw)) {
            val ar = AngleSolver.solve(raw, variant)
            if (ar != null) return ar
        }
        val low = raw.lowercase()
        val triWord = low.contains("triangle") || low.contains("△") || low.contains("Δ") || low.contains("▲") ||
            low.contains("isosceles") || low.contains("equilateral") || low.contains("hypotenuse") ||
            Regex("∠|\\b[A-Z]{2}\\s*=").containsMatchIn(raw)
        if (!triWord && TrigSolver.looksTrig(raw)) {
            val r = TrigSolver.solve(raw, variant)
            if (r != null) return r
        }
        return solveTriangle(raw, variant)
    }

    private fun solveTriangle(raw: String, variant: Int): QResult {
        val steps = mutableListOf<String>()
        val answers = mutableListOf<String>()
        val assumed = mutableListOf<String>()
        var tried = 0
        fun fail(msg: String): QResult = QResult(steps, answers, null, msg)

        var t = raw.trim()
        if (t.isEmpty()) return fail("Type or paste a question first.")
        steps.add(if (variant > 0) "🔁 Rechecking — reading #" + (variant + 1) + " of your question…" else "📖 Reading the question…")
        t = t.replace("△", " triangle ").replace("Δ", " triangle ").replace("∆", " triangle ").replace("▲", " triangle ")
        t = t.replace("−", "-").replace("–", "-").replace("×", "*").replace("\n", " ")
        t = Regex("(?i)\\bdegrees?\\b|\\bdeg\\b").replace(t, "°")
        val low = t.lowercase()

        // ---- is this a triangle question at all? ----
        val quadWords = Regex("quadrilateral|parallelogram|rhombus|trapezium|trapezoid|rectangle|square|kite").containsMatchIn(low)
        val polyWords = Regex("polynomial|p\\(x\\)|zero of|zeroes|remainder theorem|factor theorem").containsMatchIn(low)
        val hasTri = low.contains("triangle") || Regex("\\bisosceles\\b|\\bequilateral\\b|\\bscalene\\b|\\bhypotenuse\\b").containsMatchIn(low)
        if (polyWords && !hasTri) return fail("This is a polynomial question — polynomials come in a later phase of the Question tab.")
        if (quadWords && !hasTri) return fail("This is a quadrilateral question — quadrilaterals come in a later phase of the Question tab. Triangles work now.")

        // ---- the name of the triangle ----
        var found: String? = nameRx.find(t)?.groupValues?.get(1) ?: isRx.find(t)?.groupValues?.get(1)
        if (found == null) {
            for (m in anyName.findAll(t)) {
                val s = m.groupValues[1]
                if (s.toSet().size == 3) {
                    found = s
                    break
                }
            }
        }
        val assumedName = found == null || found.toSet().size != 3
        val nm: String = if (assumedName) "ABC" else (found ?: "ABC")
        val f = Facts(nm)
        steps.add(if (assumedName) "△ABC — the question gives no letters, so I use A, B, C" else "△$nm found")
        fun vi(ch: String): Int = if (ch.length == 1) nm.indexOf(ch[0]) else -1
        fun angVertex(tok: String): Int = when (tok.length) {
            1 -> vi(tok)
            3 -> vi(tok.substring(1, 2))
            else -> -1
        }
        fun sideOpp(tok: String): Int {
            val i = vi(tok.substring(0, 1))
            val j = vi(tok.substring(1, 2))
            return if (i < 0 || j < 0 || i == j) -1 else 3 - i - j
        }

        // ---- angle statements ----
        val used = mutableListOf<IntRange>()
        for (m in relRx.findAll(t)) {
            val i = angVertex(m.groupValues[1])
            val j = angVertex(m.groupValues[3])
            val k = m.groupValues[2].toDoubleOrNull() ?: 1.0
            used.add(m.range)
            if (i >= 0 && j >= 0 && i != j) {
                f.rel.add(Triple(i, j, k))
                steps.add("Given: " + f.an(i) + " = " + (if (k == 1.0) "" else qf(k) + " × ") + f.an(j))
            }
        }
        for (m in angEq.findAll(t)) {
            if (used.any { m.range.first in it }) continue
            val i = angVertex(m.groupValues[1])
            val l = parseLin(m.groupValues[2].trim())
            if (i < 0 || l == null) continue
            if (f.angLin[i] != null) continue
            f.angLin[i] = l
            steps.add("Given: " + f.an(i) + " = " + linText(l) + (if (l.k == 0.0) "°" else ""))
            if (l.k == 0.0 && abs(l.c - 90.0) < 1e-9) f.right = i
        }
        rightAt.find(t)?.let { m ->
            val i = vi(m.groupValues[1])
            if (i >= 0) {
                f.right = i
                steps.add("Given: right angle at " + nm[i])
            }
        }
        perpRx.find(t)?.let { m ->
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            val shared = a.toSet().intersect(b.toSet())
            if (shared.size == 1) {
                val i = nm.indexOf(shared.first())
                if (i >= 0) {
                    f.right = i
                    steps.add("Given: " + a + " ⊥ " + b + " ⇒ right angle at " + nm[i])
                }
            }
        }

        for (m in trigGiven.findAll(t)) {
            val i = vi(m.groupValues[2])
            val tn = parseTN(m.groupValues[3]) ?: continue
            val v = tev(tn, 0.0)
            if (i < 0 || v.isNaN() || f.angLin[i] != null) continue
            val deg = when (m.groupValues[1].lowercase()) {
                "sin" -> applyFn("asin", v)
                "cos" -> applyFn("acos", v)
                else -> applyFn("atan", v)
            }
            if (deg.isNaN() || deg <= 0.0 || deg >= 180.0) continue
            f.angLin[i] = Lin(0.0, deg)
            steps.add("Given: " + m.groupValues[1].lowercase() + " " + nm[i] + " = " + m.groupValues[3].trim() + " ⇒ " + f.an(i) + " = " + qf(deg) + "°")
        }

        // ---- "angles are x, 2x and 3x" / "two angles are 50° and 60°" / ratio ----
        if (f.angLin.all { it == null } && f.rel.isEmpty()) {
            val listRx = Regex("(?i:angles?)[^.?]*?(?:\\bare\\b|:)\\s*([^.?]*)")
            val twoRx = Regex("(?i:two\\s+angles)[^.?]*?(?:\\bare\\b|:)\\s*([^.?]*)")
            val two = twoRx.find(t)
            val lst = listRx.find(t)
            val tail = (two ?: lst)?.groupValues?.get(1)
            if (tail != null) {
                val parts = tail.replace(Regex("(?i)\\s+and\\s+"), ",").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val want = if (two != null) 2 else 3
                val ls = parts.map { parseLin(it) }
                if (parts.size == want && ls.all { it != null }) {
                    for (i in 0 until want) f.angLin[i] = ls[i]
                    steps.add("Given angles: " + (0 until want).joinToString(", ") { f.an(it) + " = " + linText(ls[it]!!) })
                }
            }
        }
        ratioRx.find(t)?.let { m ->
            val r = doubleArrayOf(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDouble())
            if (low.contains("angle") && r.all { it > 0 }) {
                f.ratio = r
                steps.add("Given: angles in the ratio " + qf(r[0]) + " : " + qf(r[1]) + " : " + qf(r[2]))
            }
        }

        // ---- sides ----
        for (m in chainRx.findAll(t)) {
            val parts = m.value.split("=").map { it.trim() }
            val toks = parts.filter { Regex("^[A-Z]{2}$").matches(it) }
            val opps = toks.map { sideOpp(it) }
            if (opps.any { it < 0 } || opps.toSet().size != opps.size) continue
            val num = parts.firstOrNull { it[0].isDigit() }?.let { Regex(NUM).find(it)?.value?.toDoubleOrNull() }
            if (num != null) {
                for (o in opps) {
                    f.sideVal[o] = num
                }
                steps.add("Given: " + toks.joinToString(" = ") + " = " + qf(num))
            } else if (opps.size >= 2) {
                f.sideEq.add(opps)
                steps.add("Given: " + toks.joinToString(" = "))
            }
        }
        // sides of a different length typed as "AB = 5 cm" inside text are caught above; named sides in words below
        val sidesM = sidesRx.find(t)
        val hypM = hypRx.find(t)
        val legsM = legsRx.find(t)
        val wordRight = Regex("right[- ]?angled?\\s+(?:isosceles\\s+)?triangle|right\\s+triangle|isosceles\\s+right").containsMatchIn(low)
        if (f.right < 0 && (wordRight || hypM != null || legsM != null)) {
            f.right = intArrayOf(1, 0, 2)[variant % 3]
            val msg = "Right-angled triangle with no corner named — I put the right angle at " + nm[f.right] + (if (variant == 0) " (the usual choice)" else " (another choice)")
            steps.add(msg)
            assumed.add("right angle at " + nm[f.right])
        }
        if (hypM != null && f.right >= 0) {
            f.sideVal[f.right] = hypM.groupValues[1].toDouble()
            steps.add("Given: hypotenuse " + f.sn(f.right) + " = " + hypM.groupValues[1])
        }
        if (legsM != null && f.right >= 0) {
            f.sideVal[(f.right + 1) % 3] = legsM.groupValues[1].toDouble()
            f.sideVal[(f.right + 2) % 3] = legsM.groupValues[2].toDouble()
            steps.add("Given: legs " + f.sn((f.right + 1) % 3) + " = " + legsM.groupValues[1] + " and " + f.sn((f.right + 2) % 3) + " = " + legsM.groupValues[2])
        }
        if (sidesM != null && f.sideVal.all { it == null }) {
            val a = sidesM.groupValues[1].toDouble()
            val b = sidesM.groupValues[2].toDouble()
            val c = sidesM.groupValues[3].toDouble()
            if (f.right >= 0) {
                val s = listOf(a, b, c).sorted()
                f.sideVal[f.right] = s[2]
                f.sideVal[(f.right + 1) % 3] = s[0]
                f.sideVal[(f.right + 2) % 3] = s[1]
                steps.add("Given sides " + qf(a) + ", " + qf(b) + ", " + qf(c) + " — the longest is the hypotenuse")
            } else {
                f.sideVal[2] = a
                f.sideVal[0] = b
                f.sideVal[1] = c
                steps.add("Given sides: " + f.sn(2) + " = " + qf(a) + ", " + f.sn(0) + " = " + qf(b) + ", " + f.sn(1) + " = " + qf(c))
            }
        }

        // ---- kinds of triangle ----
        if (Regex("\\bequilateral\\b").containsMatchIn(low)) {
            f.sideEq.add(listOf(0, 1, 2))
            steps.add("Equilateral: all three sides equal, so every angle is 60°")
        } else if (Regex("\\bisosceles\\b").containsMatchIn(low) && f.sideEq.none { it.size >= 2 }) {
            val apex = if (f.right >= 0) f.right else intArrayOf(0, 1, 2)[variant % 3]
            f.sideEq.add(listOf((apex + 1) % 3, (apex + 2) % 3))
            assumed.add("equal sides " + f.sn((apex + 1) % 3) + " = " + f.sn((apex + 2) % 3))
            steps.add("Isosceles with no equal sides named — I take " + f.sn((apex + 1) % 3) + " = " + f.sn((apex + 2) % 3) + " (apex " + nm[apex] + ")")
        }
        if (Regex("acute[- ]?angled|acute\\s+triangle").containsMatchIn(low)) f.acute = true
        if (Regex("obtuse[- ]?angled|obtuse\\s+triangle").containsMatchIn(low)) f.obtuse = true

        // ---- extras to switch on ----
        val ex = f.extras
        if (low.contains("altitude") || low.contains("perpendicular from")) ex.add("alt")
        if (low.contains("median")) ex.add("med")
        if (low.contains("bisector")) ex.add("bis")
        if (low.contains("circumcircle") || low.contains("circumscribed") || low.contains("circumcentre") || low.contains("circumcenter")) ex.add("circ")
        if (low.contains("incircle") || low.contains("inscribed circle") || low.contains("incentre") || low.contains("incenter")) ex.add("inc")
        if (low.contains("centroid")) ex.add("cen")
        if (low.contains("orthocent")) ex.add("orth")
        if (low.contains("midpoint") || low.contains("mid-point")) ex.add("mid")
        if (ex.isNotEmpty()) steps.add("Extras to draw: " + ex.joinToString(", ") {
            when (it) {
                "alt" -> "altitudes"
                "med" -> "medians"
                "bis" -> "angle bisectors"
                "circ" -> "circumcircle"
                "inc" -> "incircle"
                "cen" -> "centroid"
                "orth" -> "orthocentre"
                else -> "midpoints"
            }
        })

        // ---- what is asked ----
        f.proof = Regex("\\bprove\\b|show that|\\bverify\\b").containsMatchIn(low)
        val fm = findRx.find(t)
        val goalText = if (fm != null) t.substring(fm.range.last + 1) else ""
        val gl = goalText.lowercase()
        if (Regex("\\bx\\b").containsMatchIn(goalText)) f.wantX = true
        for (m in angTok.findAll(goalText)) {
            val i = angVertex(m.groupValues[1])
            if (i >= 0) f.goalAng.add(i)
        }
        for (m in sideTok.findAll(goalText)) {
            val o = sideOpp(m.groupValues[1])
            if (o >= 0) f.goalSide.add(o)
        }
        if (gl.contains("hypotenuse") && f.right >= 0) f.goalSide.add(f.right)
        if (gl.contains("area")) f.goalArea = true
        if (gl.contains("perimeter")) f.goalPerimeter = true
        if (Regex("all (the )?angles|the angles|angles of").containsMatchIn(gl)) f.goalAll = true
        if (gl.contains("base angle")) f.goalBase = true
        if (gl.contains("apex") || gl.contains("vertex angle")) f.goalApex = true
        if (gl.contains("third angle")) {
            for (i in 0..2) if (f.angLin[i] == null) {
                f.goalAng.add(i)
                break
            }
        }
        for (m in Regex("(?i:altitude|height)\\s*(?:from\\s+)?(?:vertex\\s+)?([A-Z])").findAll(goalText)) {
            val i = vi(m.groupValues[1])
            if (i >= 0) f.goalAlt.add(i)
        }
        for (m in Regex("(?i:median)\\s*(?:from\\s+)?(?:vertex\\s+)?([A-Z])").findAll(goalText)) {
            val i = vi(m.groupValues[1])
            if (i >= 0) f.goalMed.add(i)
        }
        for (m in Regex("(?i:exterior\\s+angle)\\s*(?:(?i:at|of)\\s+)?(?:vertex\\s+)?([A-Z])").findAll(goalText)) {
            val i = vi(m.groupValues[1])
            if (i >= 0) f.goalExt.add(i)
        }
        if (gl.contains("circumradius") || Regex("radius of (the )?(circumcircle|circumscribed)").containsMatchIn(gl)) f.goalR = true
        if (gl.contains("inradius") || Regex("radius of (the )?(incircle|inscribed)").containsMatchIn(gl)) f.goalr = true
        if (f.goalAlt.isNotEmpty()) steps.add("Goal: altitude from " + f.goalAlt.sorted().joinToString(", ") { nm[it].toString() })
        if (f.goalMed.isNotEmpty()) steps.add("Goal: median from " + f.goalMed.sorted().joinToString(", ") { nm[it].toString() })
        if (f.goalExt.isNotEmpty()) steps.add("Goal: exterior angle at " + f.goalExt.sorted().joinToString(", ") { nm[it].toString() })
        if (f.goalR) steps.add("Goal: circumradius")
        if (f.goalr) steps.add("Goal: inradius")
        if (f.proof) steps.add("This is a proof question — proofs (congruence, extra lines) come in the next phase. I will draw the figure from the facts.")
        if (f.wantX) steps.add("Goal: find x")
        if (f.goalAng.isNotEmpty()) steps.add("Goal: find " + f.goalAng.sorted().joinToString(", ") { f.an(it) })
        if (f.goalSide.isNotEmpty()) steps.add("Goal: find " + f.goalSide.sorted().joinToString(", ") { f.sn(it) })
        if (f.goalArea) steps.add("Goal: area")
        if (f.goalPerimeter) steps.add("Goal: perimeter")
        if (f.goalAll) steps.add("Goal: all three angles")
        if (f.goalBase) steps.add("Goal: the base angles")
        if (f.goalApex) steps.add("Goal: the apex angle")

        for (w in listOf("produced", "parallel", "circle", "tangent", "similar")) {
            if (low.contains(w) && !(w == "circle" && (ex.contains("circ") || ex.contains("inc")))) {
                steps.add("⚠ I do not understand \"$w\" yet — I used the rest of the question")
                break
            }
        }

        val anyFact = f.angLin.any { it != null } || f.rel.isNotEmpty() || f.ratio != null || f.sideVal.any { it != null } ||
            f.sideEq.isNotEmpty() || f.right >= 0
        if (!anyFact && !hasTri) return fail("I could not find a triangle or any facts about one. Try: In triangle ABC, AB = AC and ∠A = 40°. Find ∠B.")

        // ---- planning ----
        steps.add("🧠 Turning the facts into equations:")
        for (e in f.equations()) steps.add("  • " + e.why)

        // ---- solving (and if the question leaves something free, try a value and check it) ----
        var st = derive(f, null)
        if (st.bad != null) return fail(st.bad!!)
        val givenAng = st.ang.map { it != null }
        val givenSide = st.side.map { it != null }
        var illustrative = false
        var fixNote: String? = null
        var accepted: St? = null
        if (st.ang.all { it != null } && validity(f, st) == null) {
            accepted = st
        } else if (st.ang.any { it == null }) {
            illustrative = true
            val free = freeIndex(f, st)
            steps.add("The question does not fix " + f.an(free) + " — I will try a value, draw it and check it.")
            val candBase = listOf(50.0, 70.0, 40.0, 100.0, 65.0, 35.0, 110.0)
            val rot = variant % candBase.size
            for (cand in candBase.drop(rot) + candBase.take(rot)) {
                tried++
                val s2 = derive(f, Pair(free, cand))
                val why = s2.bad ?: validity(f, s2) ?: (if (s2.ang.any { it == null }) "still not fixed" else null)
                if (why == null) {
                    accepted = s2
                    fixNote = f.an(free) + " = " + qf(cand) + "°"
                    steps.add("✓ " + f.an(free) + " = " + qf(cand) + "° works")
                    break
                } else {
                    steps.add("✗ " + f.an(free) + " = " + qf(cand) + "° fails (" + why + ") — trying another")
                }
            }
        } else {
            return fail(validity(f, st) ?: "These facts do not make a triangle.")
        }
        val fin = accepted ?: return fail("I could not find a triangle that fits all the facts. Check the question.")
        st = fin
        steps.addAll(fin.steps)

        // ---- build the figure and check it (the "drone") ----
        steps.add("✏️ Drawing the figure…")
        val ang = DoubleArray(3) { fin.ang[it] ?: 0.0 }
        val len = DoubleArray(3)
        var scaled = false
        if (fin.side.all { it != null }) {
            for (i in 0..2) len[i] = fin.side[i] ?: 0.0
        } else {
            val sn = DoubleArray(3) { sin(ang[it] * PI / 180) }
            val mx = max(sn[0], max(sn[1], sn[2]))
            for (i in 0..2) len[i] = 6.0 * sn[i] / mx
            scaled = true
        }
        val bases0 = baseChoices(f, ang, len)
        val brot = variant % bases0.size
        val bases = bases0.drop(brot) + bases0.take(brot)
        var plan: QPlan? = null
        var attempt = 0
        for (b in bases) {
            attempt++
            tried++
            val pts = build(len, b.first, b.second) ?: continue
            val bad = check(f, pts, steps, true)
            if (bad == null) {
                val showA = mutableSetOf<Int>()
                val showS = mutableSetOf<Int>()
                for (i in 0..2) {
                    if (givenAng[i] || !illustrative) showA.add(i)
                    if ((givenSide[i] || (!illustrative && !scaled)) && !scaled) showS.add((i + 1) % 3)
                }
                plan = QPlan(nm, pts, showS, showA, f.extras, "△" + nm)
                steps.add("✅ All facts hold — figure placed on the board" + (if (scaled) " (sizes chosen for a clear picture)" else ""))
                break
            } else {
                steps.add("✗ Check failed: " + bad + " — deleting this figure and drawing it another way")
            }
        }
        val finalPlan = plan ?: return fail("The figure I drew did not pass the check, so I did not place it.")

        // ---- the answers ----
        if (f.wantX) {
            val x = fin.x
            if (x != null) answers.add("x = " + qf(x)) else answers.add("x is not fixed by this question")
            if (x != null) {
                for (i in 0..2) {
                    val l = f.angLin[i] ?: continue
                    if (l.k != 0.0) answers.add(f.an(i) + " = " + linText(l) + " = " + qf(l.k * x + l.c) + "°")
                }
            }
        }
        val wantAng = mutableSetOf<Int>()
        wantAng.addAll(f.goalAng)
        if (f.goalAll) wantAng.addAll(listOf(0, 1, 2))
        val grp = f.sideEq.firstOrNull { it.size == 2 }
        if (f.goalBase) {
            if (grp != null) wantAng.addAll(grp) else wantAng.addAll(listOf(1, 2))
        }
        if (f.goalApex) {
            if (grp != null) wantAng.add(3 - grp[0] - grp[1]) else wantAng.add(0)
        }
        for (i in wantAng.sorted()) {
            val v = st.ang[i]
            if (v != null && (givenAng[i] || !illustrative)) answers.add(f.an(i) + " = " + qf(v) + "°")
        }
        if (illustrative) {
            val unfixed = wantAng.filter { !givenAng[it] }
            if (unfixed.isNotEmpty()) {
                if (grp != null && grp.containsAll(unfixed) && unfixed.size == 2) {
                    val k = 3 - grp[0] - grp[1]
                    answers.add(f.an(grp[0]) + " = " + f.an(grp[1]) + " = (180° − " + f.an(k) + ")/2")
                } else {
                    answers.add("The question does not fix " + unfixed.joinToString(", ") { f.an(it) } + " — it depends on the other angles.")
                }
            }
            if (fixNote != null) answers.add("(Example drawn with " + fixNote + ")")
        }
        for (o in f.goalSide.sorted()) {
            val v = fin.side[o]
            if (v != null && !scaled) answers.add(f.sn(o) + " = " + qf(v))
            else answers.add(f.sn(o) + " is not fixed by this question")
        }
        val areaLines = mutableListOf<String>()
        if (f.goalArea || f.goalPerimeter) {
            if (scaled || illustrative) {
                answers.add("Area and perimeter need at least one side length.")
            } else {
                val a = len[0]
                val b = len[1]
                val c = len[2]
                if (f.goalArea) {
                    val hs = (a + b + c) / 2
                    val area = sqrt(max(hs * (hs - a) * (hs - b) * (hs - c), 0.0))
                    if (f.right >= 0) {
                        val l1 = len[(f.right + 1) % 3]
                        val l2 = len[(f.right + 2) % 3]
                        areaLines.add("Area = ½ × " + qf(l1) + " × " + qf(l2) + " = " + qf(0.5 * l1 * l2) + " (the two legs are base and height)")
                    } else {
                        areaLines.add("Heron's formula: s = (" + qf(a) + " + " + qf(b) + " + " + qf(c) + ")/2 = " + qf(hs))
                        areaLines.add("Heron's formula: Area = sqrt(" + qf(hs) + " × " + qf(hs - a) + " × " + qf(hs - b) + " × " + qf(hs - c) + ") = " + qf(area))
                    }
                    answers.add("Area = " + qf(area))
                }
                if (f.goalPerimeter) answers.add("Perimeter = " + qf(a) + " + " + qf(b) + " + " + qf(c) + " = " + qf(a + b + c))
            }
        }
        // ---- more theorems: exterior angle, circumradius, inradius, altitude, median ----
        val extra = mutableListOf<String>()
        extra.addAll(areaLines)
        val sidesKnown = !scaled && !illustrative
        for (i in f.goalExt.sorted()) {
            val j = (i + 1) % 3
            val k = (i + 2) % 3
            if (givenAng[j] && givenAng[k]) {
                val a1 = ang[j]
                val a2 = ang[k]
                extra.add("Exterior angle at " + nm[i] + " = " + f.an(j) + " + " + f.an(k) + " = " + qf(a1) + "° + " + qf(a2) + "° = " + qf(a1 + a2) + "°  (exterior angle theorem)")
                answers.add("Exterior angle at " + nm[i] + " = " + qf(a1 + a2) + "°")
            } else {
                answers.add("The exterior angle at " + nm[i] + " needs the other two angles.")
            }
        }
        val needSides = f.goalR || f.goalr || f.goalAlt.isNotEmpty() || f.goalMed.isNotEmpty()
        if (needSides && !sidesKnown) {
            answers.add("Radius, altitude and median lengths need at least one side length.")
        } else if (needSides) {
            val sa = len[0]
            val sb = len[1]
            val sc = len[2]
            val hs = (sa + sb + sc) / 2
            val area = sqrt(max(hs * (hs - sa) * (hs - sb) * (hs - sc), 0.0))
            if (f.goalR) {
                val rr = sa / (2 * sin(ang[0] * PI / 180))
                extra.add("Extended sine rule: " + f.sn(0) + "/sin(" + qf(ang[0]) + "°) = 2R ⇒ R = " + qf(sa) + "/(2 × sin(" + qf(ang[0]) + "°)) = " + qf(rr))
                answers.add("Circumradius R = " + qf(rr))
            }
            if (f.goalr) {
                extra.add("Heron's formula: s = (" + qf(sa) + " + " + qf(sb) + " + " + qf(sc) + ")/2 = " + qf(hs) + ", Area = sqrt(" + qf(hs) + " × " + qf(hs - sa) + " × " + qf(hs - sb) + " × " + qf(hs - sc) + ") = " + qf(area))
                extra.add("Inradius r = Area/s = " + qf(area) + "/" + qf(hs) + " = " + qf(area / hs))
                answers.add("Inradius r = " + qf(area / hs))
            }
            for (i in f.goalAlt.sorted()) {
                val h = 2 * area / len[i]
                extra.add("Area = " + qf(area) + " (Heron's formula) and Area = ½ × " + f.sn(i) + " × h ⇒ h = 2 × " + qf(area) + "/" + qf(len[i]) + " = " + qf(h))
                answers.add("Altitude from " + nm[i] + " = " + qf(h))
            }
            for (i in f.goalMed.sorted()) {
                val j = (i + 1) % 3
                val k = (i + 2) % 3
                val m = 0.5 * sqrt(max(2 * len[j] * len[j] + 2 * len[k] * len[k] - len[i] * len[i], 0.0))
                extra.add("Apollonius' theorem: m^2 = (2 × " + qf(len[j]) + "^2 + 2 × " + qf(len[k]) + "^2 − " + qf(len[i]) + "^2)/4 ⇒ m = " + qf(m))
                answers.add("Median from " + nm[i] + " = " + qf(m))
            }
        }
        steps.addAll(extra)
        if (answers.isEmpty() && !f.proof) {
            for (i in 0..2) if (givenAng[i] || !illustrative) answers.add(f.an(i) + " = " + qf(ang[i]) + "°")
        }
        if (answers.isNotEmpty()) steps.add("🎯 Answer ready")

        // ---- what the engine weighed (shown first, like thinking) ----
        val think = mutableListOf<String>()
        val rawA = f.angLin.count { it != null } + (if (f.right >= 0 && f.angLin[f.right] == null) 1 else 0)
        val rawS = f.sideVal.count { it != null }
        val unk = f.sideVal.indexOfFirst { it == null }
        val pattern = when {
            rawS == 3 -> "three sides (SSS) — the cosine rule gives every angle"
            rawA >= 2 && rawS >= 1 -> "two angles and a side (ASA / AAS) — angle sum, then the sine rule"
            rawS == 2 && f.right >= 0 -> "right angle and two sides (RHS) — Pythagoras for the third side"
            rawS == 2 && unk >= 0 && (f.angLin[unk] != null || f.right == unk) -> "two sides and the angle between them (SAS) — cosine rule"
            rawS == 2 && rawA >= 1 -> "two sides and a different angle (SSA) — sine rule, watching for a second triangle"
            rawA >= 2 -> "two angles — the third comes from the angle sum"
            f.sideEq.isNotEmpty() -> "equal sides — the isosceles theorem links the angles"
            else -> "not enough to fix the shape — I will try example values and check them"
        }
        think.add("Read " + steps.count { it.startsWith("Given") } + " given fact(s) about △" + nm + (if (assumed.isNotEmpty()) " and made " + assumed.size + " assumption(s): " + assumed.joinToString("; ") else ""))
        think.add("Data pattern: " + pattern)
        val usedTxt = fin.steps.joinToString(" ")
        fun th(name: String, yes: Boolean, why: String) {
            think.add((if (yes) "✓ " else "– ") + name + " — " + why)
        }
        th("Angle sum of a triangle", true, "∠A + ∠B + ∠C = 180° holds for every triangle")
        th("Isosceles triangle theorem", f.sideEq.isNotEmpty(), if (f.sideEq.isNotEmpty()) "equal sides ⇒ equal opposite angles" else "no equal sides given")
        th("Pythagoras' theorem", usedTxt.contains("Pythagoras"), if (f.right >= 0) "right angle at " + nm[f.right] else "no right angle")
        th("Sine rule", usedTxt.contains("Sine rule"), if (usedTxt.contains("Sine rule")) "a side and the angle opposite it are known" else "not needed")
        th("Cosine rule", usedTxt.contains("Cosine rule"), if (usedTxt.contains("Cosine rule")) "sides and angles can be linked" else "not needed")
        if (f.goalExt.isNotEmpty()) th("Exterior angle theorem", true, "exterior angle = sum of the two far interior angles")
        if (f.goalr || f.goalAlt.isNotEmpty() || f.goalArea) th("Heron's formula and area", true, "area from the sides")
        if (f.goalMed.isNotEmpty()) th("Apollonius' theorem", true, "median length from the three sides")
        if (f.goalR) th("Extended sine rule", true, "a / sin A = 2R")
        val fnsUsed = listOfNotNull(
            if (usedTxt.contains("Sine rule") || f.goalArea || f.goalR) "sin" else null,
            if (usedTxt.contains("Cosine rule")) "cos" else null,
            if (trigGiven.containsMatchIn(t)) "sin / cos / tan (given)" else null,
        )
        think.add("Trig functions in play: " + (if (fnsUsed.isEmpty()) "none needed" else fnsUsed.joinToString(", ")))
        think.add("Conditions to satisfy: every angle between 0° and 180°, angles add to 180°, each side shorter than the other two together")
        think.add(if (illustrative) "Free value " + (fixNote ?: "") + ": tried " + tried + " possibilit" + (if (tried == 1) "y" else "ies") + " before one passed every check" else "Figures drawn and checked: " + tried)

        // ---- the solution as it will be written on the board: Given / To find / Solution, each line with its reason ----
        val board = mutableListOf<String>()
        board.add("Given:")
        for (line in steps) {
            if (line.startsWith("Given") || line.startsWith("Equilateral") || line.contains("I put the right angle") || line.startsWith("Isosceles with no")) {
                board.add("• " + line.removePrefix("Given: ").removePrefix("Given angles: "))
            }
        }
        val goalLines = steps.filter { it.startsWith("Goal: ") }.map { it.removePrefix("Goal: ") }
        board.add("To find: " + (if (goalLines.isEmpty()) "the unknown angles and sides" else goalLines.joinToString(", ")))
        board.add("Solution:")
        fun withReason(line: String): String {
            val why = when {
                line.contains("(exterior angle theorem)") || line.contains("(equal to") -> ""
                line.startsWith("Base angles:") -> "angles opposite equal sides are equal; angle sum of a triangle"
                line.startsWith("Apex angle:") -> "angle sum of a triangle, base angles are equal"
                line.startsWith("Pythagoras:") -> "Pythagoras' theorem"
                line.startsWith("Sine rule:") -> "sine rule"
                line.startsWith("Cosine rule:") -> "cosine rule"
                line.startsWith("Heron's formula:") -> "Heron's formula"
                line.startsWith("Extended sine rule:") -> "extended sine rule"
                line.startsWith("Apollonius'") -> "Apollonius' theorem"
                line.startsWith("Inradius") -> "inradius = area ÷ semi-perimeter"
                line.startsWith("Area =") -> "area of a triangle"
                line.startsWith("From the equations") -> "solving the equations made from the given angles"
                line.startsWith("∠") && line.contains("180° −") -> "angle sum of a triangle"
                line.startsWith("∠") -> "from the given facts and the angle sum"
                else -> ""
            }
            return if (why.isEmpty()) line else "$line    ($why)"
        }
        for (line in fin.steps + extra) board.add(withReason(line))
        for (a in answers) board.add("∴ " + a)
        if (variant > 0 && assumed.isEmpty()) steps.add("Nothing was assumed in this question, so I only redrew it in another position. If it still looks wrong, name the equal sides or the right angle in the question.")
        return QResult(steps, answers, finalPlan, null, think, board)
    }

    /** Which angle to try values for: the apex of an isosceles triangle if it is open, else the first open angle. */
    private fun freeIndex(f: Facts, st: St): Int {
        val g = f.sideEq.firstOrNull { it.size == 2 }
        if (g != null) {
            val apex = 3 - g[0] - g[1]
            if (st.ang[apex] == null) return apex
        }
        for (i in 0..2) if (st.ang[i] == null) return i
        return 0
    }

    private fun validity(f: Facts, st: St): String? {
        var sum = 0.0
        for (i in 0..2) {
            val a = st.ang[i] ?: return null
            if (a <= 0.5) return f.an(i) + " would be " + qf(a) + "°, which is too small for a triangle"
            if (a >= 179.5) return f.an(i) + " would be " + qf(a) + "°, which is too big for a triangle"
            sum += a
        }
        if (abs(sum - 180.0) > 1e-6) return "angles add to " + qf(sum) + "°, not 180°"
        for (i in 0..2) {
            val s = st.side[i] ?: continue
            if (s <= 0) return f.sn(i) + " would not be a length"
        }
        if (f.acute && (0..2).any { (st.ang[it] ?: 0.0) >= 90.0 }) return "the question says acute-angled"
        if (f.obtuse && (0..2).none { (st.ang[it] ?: 0.0) > 90.0 }) return "the question says obtuse-angled"
        val s0 = st.side[0]
        val s1 = st.side[1]
        val s2 = st.side[2]
        if (s0 != null && s1 != null && s2 != null) {
            if (s0 + s1 <= s2 + 1e-9 || s0 + s2 <= s1 + 1e-9 || s1 + s2 <= s0 + 1e-9) return "those sides cannot close into a triangle"
        }
        return null
    }

    /** Equations first, then angle sum / Pythagoras / sine rule / cosine rule until nothing new appears. */
    private fun derive(f: Facts, fix: Pair<Int, Double>?): St {
        val st = St(arrayOfNulls<Double>(3), arrayOfNulls<Double>(3), null, mutableListOf(), null)
        val eqs = f.equations().toMutableList()
        if (fix != null) {
            val a = DoubleArray(4)
            a[fix.first] = 1.0
            eqs.add(Eq(a, fix.second, f.an(fix.first) + " = " + qf(fix.second) + "° (tried)"))
        }
        val g = gauss(eqs)
        if (!g.ok) {
            st.bad = "These facts cannot all be true together — check the question: " + eqs.joinToString("; ") { it.why.substringBefore(" (") }
            return st
        }
        for (i in 0..2) {
            if (g.known[i]) st.ang[i] = g.v[i]
        }
        if (g.known[3]) st.x = g.v[3]
        for (i in 0..2) st.side[i] = f.sideVal[i]

        // narration of the angles that were worked out
        val xv = st.x
        if (xv != null && f.angLin.any { it != null && it.k != 0.0 }) st.steps.add("From the equations: x = " + qf(xv))
        val grp = f.sideEq.firstOrNull { it.size == 2 }
        if (grp != null && fix == null) {
            val k = 3 - grp[0] - grp[1]
            val ak = st.ang[k]
            val ai = st.ang[grp[0]]
            val aiGiven = f.angLin[grp[0]] != null || f.angLin[grp[1]] != null
            if (ak != null && ai != null && !aiGiven && f.angLin[k] != null && f.right < 0) {
                st.steps.add("Base angles: " + f.an(grp[0]) + " = " + f.an(grp[1]) + " = (180° − " + qf(ak) + "°)/2 = " + qf(ai) + "°")
            } else if (ak != null && ai != null && aiGiven) {
                st.steps.add("Apex angle: " + f.an(k) + " = 180° − 2 × " + qf(ai) + "° = " + qf(ak) + "°")
            }
        }
        for (i in 0..2) {
            val a = st.ang[i]
            if (a != null && f.angLin[i] == null && f.right != i) st.steps.add(f.an(i) + " = " + qf(a) + "°")
        }

        fun sA(i: Int) = st.ang[i]
        var changed = true
        var guard = 0
        while (changed && guard < 30) {
            changed = false
            guard++
            // equal sides share a length
            for (grpS in f.sideEq) {
                val known = grpS.firstNotNullOfOrNull { st.side[it] }
                if (known != null) {
                    for (o in grpS) {
                        if (st.side[o] == null) {
                            st.side[o] = known
                            st.steps.add(f.sn(o) + " = " + qf(known) + " (equal to " + f.sn(grpS.first { st.side[it] == known && it != o }) + ")")
                            changed = true
                        }
                    }
                }
            }
            // Pythagoras
            val r = f.right
            if (r >= 0 && sA(r) != null) {
                val h = st.side[r]
                val l1 = st.side[(r + 1) % 3]
                val l2 = st.side[(r + 2) % 3]
                if (h == null && l1 != null && l2 != null) {
                    st.side[r] = sqrt(l1 * l1 + l2 * l2)
                    st.steps.add("Pythagoras: " + f.sn(r) + "^2 = " + qf(l1) + "^2 + " + qf(l2) + "^2 ⇒ " + f.sn(r) + " = " + qf(st.side[r] ?: 0.0))
                    changed = true
                } else if (h != null && l1 != null && l2 == null) {
                    if (h <= l1) {
                        st.bad = "The hypotenuse must be the longest side"
                        return st
                    }
                    st.side[(r + 2) % 3] = sqrt(h * h - l1 * l1)
                    st.steps.add("Pythagoras: " + f.sn((r + 2) % 3) + "^2 = " + qf(h) + "^2 − " + qf(l1) + "^2 ⇒ " + f.sn((r + 2) % 3) + " = " + qf(st.side[(r + 2) % 3] ?: 0.0))
                    changed = true
                } else if (h != null && l2 != null && l1 == null) {
                    if (h <= l2) {
                        st.bad = "The hypotenuse must be the longest side"
                        return st
                    }
                    st.side[(r + 1) % 3] = sqrt(h * h - l2 * l2)
                    st.steps.add("Pythagoras: " + f.sn((r + 1) % 3) + "^2 = " + qf(h) + "^2 − " + qf(l2) + "^2 ⇒ " + f.sn((r + 1) % 3) + " = " + qf(st.side[(r + 1) % 3] ?: 0.0))
                    changed = true
                }
            }
            // angle sum
            val unknownA = (0..2).filter { st.ang[it] == null }
            if (unknownA.size == 1) {
                val k = unknownA[0]
                val s = (0..2).filter { it != k }.sumOf { st.ang[it] ?: 0.0 }
                st.ang[k] = 180.0 - s
                st.steps.add(f.an(k) + " = 180° − " + qf(s) + "° = " + qf(180.0 - s) + "°")
                changed = true
            }
            // cosine rule: three sides -> angle
            val s0 = st.side[0]
            val s1 = st.side[1]
            val s2 = st.side[2]
            if (s0 != null && s1 != null && s2 != null) {
                val sides = doubleArrayOf(s0, s1, s2)
                for (i in 0..2) {
                    if (st.ang[i] == null) {
                        val j = (i + 1) % 3
                        val k = (i + 2) % 3
                        val c = ((sides[j] * sides[j] + sides[k] * sides[k] - sides[i] * sides[i]) / (2 * sides[j] * sides[k]))
                        if (c < -1.0 || c > 1.0) {
                            st.bad = "Those sides cannot close into a triangle"
                            return st
                        }
                        st.ang[i] = acos(c) * 180 / PI
                        st.steps.add("Cosine rule: cos " + f.an(i) + " = (" + qf(sides[j]) + "^2 + " + qf(sides[k]) + "^2 − " + qf(sides[i]) + "^2)/(2 × " + qf(sides[j]) + " × " + qf(sides[k]) + ") ⇒ " + f.an(i) + " = " + qf(st.ang[i] ?: 0.0) + "°")
                        changed = true
                    }
                }
            }
            // cosine rule: two sides and the angle between them -> third side
            for (i in 0..2) {
                val ai = st.ang[i]
                val j = (i + 1) % 3
                val k = (i + 2) % 3
                val sj = st.side[j]
                val sk = st.side[k]
                if (st.side[i] == null && ai != null && sj != null && sk != null) {
                    val v = sj * sj + sk * sk - 2 * sj * sk * cos(ai * PI / 180)
                    st.side[i] = sqrt(max(v, 0.0))
                    st.steps.add("Cosine rule: " + f.sn(i) + "^2 = " + qf(sj) + "^2 + " + qf(sk) + "^2 − 2 × " + qf(sj) + " × " + qf(sk) + " × cos " + qf(ai) + "° ⇒ " + f.sn(i) + " = " + qf(st.side[i] ?: 0.0))
                    changed = true
                }
            }
            // sine rule
            var ratio: Double? = null
            var from = -1
            for (i in 0..2) {
                val s = st.side[i]
                val a = st.ang[i]
                if (s != null && a != null) {
                    ratio = s / sin(a * PI / 180)
                    from = i
                    break
                }
            }
            if (ratio != null) {
                for (j in 0..2) {
                    if (j == from) continue
                    val aj = st.ang[j]
                    val sj = st.side[j]
                    if (aj != null && sj == null) {
                        st.side[j] = ratio * sin(aj * PI / 180)
                        st.steps.add("Sine rule: " + f.sn(j) + "/sin(" + qf(aj) + "°) = " + f.sn(from) + "/sin(" + qf(st.ang[from] ?: 0.0) + "°) ⇒ " + f.sn(j) + " = " + qf(st.side[j] ?: 0.0))
                        changed = true
                    } else if (aj == null && sj != null) {
                        val sv = sj / ratio
                        if (sv > 1.0 + 1e-9) {
                            st.bad = "No triangle has these sides and angles (the sine would be bigger than 1)"
                            return st
                        }
                        var ang = asin(sv.coerceAtMost(1.0)) * 180 / PI
                        val other = (0..2).filter { it != j }.sumOf { st.ang[it] ?: 0.0 }
                        if (ang + other >= 180.0 - 1e-9) ang = 180.0 - ang
                        st.ang[j] = ang
                        st.steps.add("Sine rule: sin(" + f.an(j) + ") = " + qf(sj) + "×sin(" + qf(st.ang[from] ?: 0.0) + "°)/" + qf(st.side[from] ?: 0.0) + " ⇒ " + f.an(j) + " = " + qf(ang) + "°")
                        changed = true
                    }
                }
            }
        }
        return st
    }

    /** Which two corners to put on the bottom line: the base of an isosceles triangle, the legs of a right one. */
    private fun baseChoices(f: Facts, ang: DoubleArray, len: DoubleArray): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        if (f.right >= 0) out.add(Pair(f.right, (f.right + 1) % 3))
        val g = f.sideEq.firstOrNull { it.size == 2 }
        if (g != null) out.add(Pair(g[0], g[1]))
        var longest = 0
        for (i in 1..2) if (len[i] > len[longest] + 1e-12) longest = i
        out.add(Pair((longest + 1) % 3, (longest + 2) % 3))
        out.add(Pair(0, 1))
        out.add(Pair(1, 2))
        out.add(Pair(2, 0))
        return out.distinct()
    }

    /** Corners i and j on the bottom line (i at the left), the third corner above. len[v] is the side opposite corner v. */
    private fun build(len: DoubleArray, i: Int, j: Int): List<Pt>? {
        val k = 3 - i - j
        val bot = len[k]
        val ik = len[j]
        val jk = len[i]
        if (bot <= 0 || ik <= 0 || jk <= 0) return null
        val x = (bot * bot + ik * ik - jk * jk) / (2 * bot)
        val h2 = ik * ik - x * x
        if (h2 <= 1e-12) return null
        val y = sqrt(h2)
        val out = arrayOfNulls<Pt>(3)
        out[i] = Pt(0.0, 0.0)
        out[j] = Pt(bot, 0.0)
        out[k] = Pt(x, y)
        return listOf(out[0]!!, out[1]!!, out[2]!!)
    }

    /** The drone: measure the drawn figure and compare it with every fact of the question. Returns the first failure, or null. */
    private fun check(f: Facts, p: List<Pt>, steps: MutableList<String>, log: Boolean): String? {
        val ang = DoubleArray(3) { angDeg(p[it], p[(it + 1) % 3], p[(it + 2) % 3]) }
        val side = DoubleArray(3) { dist(p[(it + 1) % 3], p[(it + 2) % 3]) }
        var bad: String? = null
        fun ok(msg: String, pass: Boolean, shown: String) {
            if (pass) {
                if (log) steps.add("  ✓ $msg ($shown)")
            } else if (bad == null) {
                bad = "$msg is false ($shown)"
            }
        }
        for (i in 0..2) {
            val l = f.angLin[i] ?: continue
            if (l.k == 0.0) ok(f.an(i) + " = " + qf(l.c) + "°", abs(ang[i] - l.c) < 0.05, "measured " + qf(ang[i]) + "°")
        }
        if (f.right >= 0) ok(f.an(f.right) + " is a right angle", abs(ang[f.right] - 90.0) < 0.05, "measured " + qf(ang[f.right]) + "°")
        for (g in f.sideEq) {
            for (n in 0 until g.size - 1) {
                val a = side[g[n]]
                val b = side[g[n + 1]]
                ok(f.sn(g[n]) + " = " + f.sn(g[n + 1]), abs(a - b) < 1e-6 * max(1.0, a), qf(a) + " and " + qf(b))
            }
        }
        for (o in 0..2) {
            val v = f.sideVal[o] ?: continue
            ok(f.sn(o) + " = " + qf(v), abs(side[o] - v) < 1e-6 * max(1.0, v), "measured " + qf(side[o]))
        }
        ok("angles add to 180°", abs(ang[0] + ang[1] + ang[2] - 180.0) < 1e-6, qf(ang[0] + ang[1] + ang[2]) + "°")
        return bad
    }
}
