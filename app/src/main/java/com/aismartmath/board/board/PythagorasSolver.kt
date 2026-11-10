package com.aismartmath.board.board

import com.aismartmath.board.math.angDeg
import com.aismartmath.board.math.dist
import com.aismartmath.board.model.Pt
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Right triangle with x in the sides: "In right-angled triangle ABC, right angle at B, AB = x, BC = x + 7, AC = 13.
 * Find x." The sides are put into Pythagoras' theorem, the quadratic is solved (roots that make a side negative are
 * rejected), the triangle is drawn with the real lengths and measured.
 */
internal object PythagorasSolver {
    private val sideRx = Regex("\\b([A-Z]{2})\\s*=\\s*([0-9xX(][0-9xX.+\\-\\s()]*)")
    private val rightAtRx = Regex("(?i:right[- ]?angle[d]?)\\s*(?:(?i:at|in)\\s*)(?:(?i:vertex)\\s*)?([A-Z])\\b")
    private val rightValRx = Regex("∠\\s*([A-Z]{1,3})\\s*=\\s*90")
    private val triRx = Regex("(?i:triangle)\\s+([A-Z]{3})(?![A-Za-z])")

    fun looks(raw: String): Boolean {
        val l = raw.lowercase()
        if (Regex("\\bprove\\b|show that|congruent|≅").containsMatchIn(l)) return false
        if (!(l.contains("right") || l.contains("hypotenuse") || l.contains("90"))) return false
        if (!Regex("\\b[A-Z]{2}\\s*=\\s*[^,.;]*[xX]").containsMatchIn(raw)) return false
        return Regex("\\bfind\\b|what is|value of x|\\bx\\b").containsMatchIn(l)
    }

    private fun poly(a: Double, b: Double, c: Double): String {
        val sb = StringBuilder()
        fun term(coef: Double, v: String) {
            if (abs(coef) < 1e-12) return
            val mag = abs(coef)
            val body = if (v.isEmpty()) qf(mag) else (if (abs(mag - 1.0) < 1e-12) "" else qf(mag)) + v
            if (sb.isEmpty()) sb.append(if (coef < 0) "-" else "").append(body)
            else sb.append(if (coef < 0) " - " else " + ").append(body)
        }
        term(a, "x^2")
        term(b, "x")
        term(c, "")
        return if (sb.isEmpty()) "0" else sb.toString()
    }

    private fun gcdInt(a: Long, b: Long): Long = if (b == 0L) abs(a) else gcdInt(b, a % b)

    fun solve(raw: String, variant: Int): QResult? {
        val t = raw.replace("△", " triangle ").replace("Δ", " triangle ").replace("−", "-").replace("\n", " ")
        val nm = triRx.find(t)?.groupValues?.get(1) ?: "ABC"
        if (nm.length != 3 || nm.toSet().size != 3) return null
        var right = -1
        rightAtRx.find(t)?.let { right = nm.indexOf(it.groupValues[1][0]) }
        if (right < 0) rightValRx.find(t)?.let { val tok = it.groupValues[1]; right = if (tok.length == 3) nm.indexOf(tok[1]) else nm.indexOf(tok[0]) }
        if (right < 0) return null
        val side = arrayOfNulls<Lin>(3) // by opposite corner
        val steps = mutableListOf<String>()
        steps.add(if (variant > 0) "🔁 Rechecking — reading #" + (variant + 1) else "📖 Reading the question…")
        val given = mutableListOf<String>()
        for (m in sideRx.findAll(t)) {
            val key = m.groupValues[1]
            if (key.any { it !in nm } || key[0] == key[1]) continue
            val l = parseLin(m.groupValues[2].trim()) ?: continue
            val opp = nm.indexOfFirst { it !in key }
            side[opp] = l
            given.add(key + " = " + linText(l))
            steps.add("Given: " + key + " = " + linText(l))
        }
        if (side.any { it == null }) return null
        steps.add("Given: right angle at " + nm[right])
        given.add("right angle at " + nm[right])
        val h = side[right]!!
        val a = side[(right + 1) % 3]!!
        val b = side[(right + 2) % 3]!!
        val hName = "" + nm[(right + 1) % 3] + nm[(right + 2) % 3]
        val aName = "" + nm[right] + nm[(right + 2) % 3] // side opposite corner right+1
        val bName = "" + nm[right] + nm[(right + 1) % 3]
        val think = mutableListOf<String>()
        think.add("Right angle at ${nm[right]}, so the hypotenuse is $hName and the legs are $aName and $bName")
        think.add("Pythagoras' theorem: (leg)² + (leg)² = (hypotenuse)²")
        think.add("The sides contain x, so putting them in gives an equation in x (a quadratic if x² appears)")
        think.add("A root is rejected if any side would be zero or negative")
        think.add("Then the triangle is drawn with the real lengths and measured")

        val qa = a.k * a.k + b.k * b.k - h.k * h.k
        val qb = 2 * (a.k * a.c + b.k * b.c - h.k * h.c)
        val qc = a.c * a.c + b.c * b.c - h.c * h.c
        val board = mutableListOf<String>()
        board.add("Given:")
        for (g in given) board.add("• $g")
        board.add("To find: x")
        board.add("Solution:")
        fun sq(l: Lin): String = if (l.k == 0.0) qf(l.c) + "^2" else "(" + linText(l) + ")^2"
        board.add("$aName^2 + $bName^2 = $hName^2    (Pythagoras' theorem, right angle at ${nm[right]})")
        board.add(sq(a) + " + " + sq(b) + " = " + sq(h))
        // expanded form
        val lhsA = a.k * a.k + b.k * b.k
        val lhsB = 2 * (a.k * a.c + b.k * b.c)
        val lhsC = a.c * a.c + b.c * b.c
        board.add(poly(lhsA, lhsB, lhsC) + " = " + poly(h.k * h.k, 2 * h.k * h.c, h.c * h.c) + "    (expanding the brackets)")
        board.add(poly(qa, qb, qc) + " = 0    (everything on one side)")
        var pa = qa
        var pb = qb
        var pc = qc
        val ia = round(pa).toLong()
        val ib = round(pb).toLong()
        val ic = round(pc).toLong()
        if (abs(pa - ia) < 1e-9 && abs(pb - ib) < 1e-9 && abs(pc - ic) < 1e-9) {
            var g = gcdInt(gcdInt(ia, ib), ic)
            if (g > 1L) {
                pa = ia.toDouble() / g
                pb = ib.toDouble() / g
                pc = ic.toDouble() / g
                board.add(poly(pa, pb, pc) + " = 0    (dividing every term by $g)")
            }
        }
        val roots = mutableListOf<Double>()
        if (abs(pa) < 1e-12) {
            if (abs(pb) < 1e-12) return null
            roots.add(-pc / pb)
            board.add("x = " + qf(-pc / pb))
        } else {
            val disc = pb * pb - 4 * pa * pc
            if (disc < -1e-9) {
                board.add("The discriminant ${qf(disc)} is negative, so there is no real x.")
                return QResult(steps, emptyList(), null, "No real value of x makes these sides a right triangle.", think, board)
            }
            val sd = sqrt(max0(disc))
            val r1 = (-pb + sd) / (2 * pa)
            val r2 = (-pb - sd) / (2 * pa)
            val ir1 = round(r1)
            val ir2 = round(r2)
            if (abs(r1 - ir1) < 1e-9 && abs(r2 - ir2) < 1e-9) {
                fun fac(r: Double) = if (r >= 0) "(x - " + qf(r) + ")" else "(x + " + qf(-r) + ")"
                board.add((if (abs(pa - 1.0) < 1e-12) "" else qf(pa)) + fac(ir1) + fac(ir2) + " = 0    (factorising)")
            } else {
                board.add("x = (-(${qf(pb)}) ± sqrt(${qf(disc)}))/${qf(2 * pa)}    (quadratic formula)")
            }
            roots.add(r1)
            if (abs(r1 - r2) > 1e-9) roots.add(r2)
            board.add("x = " + roots.joinToString("  or  x = ") { qf(it) })
        }
        var good: Double? = null
        for (r in roots) {
            val la = a.k * r + a.c
            val lb = b.k * r + b.c
            val lh = h.k * r + h.c
            if (la > 1e-9 && lb > 1e-9 && lh > 1e-9) {
                if (good == null) good = r
                steps.add("  ✓ x = " + qf(r) + " gives sides " + qf(la) + ", " + qf(lb) + ", " + qf(lh) + " — all positive")
            } else {
                board.add("x = " + qf(r) + " is rejected: it makes a side " + (if (minOf(la, lb, lh) < 0) "negative" else "zero") + ", and a length must be positive.")
                steps.add("  ✗ x = " + qf(r) + " makes a side ≤ 0 — rejected")
            }
        }
        val x = good ?: return QResult(steps, emptyList(), null, "No value of x gives three positive sides.", think, board)
        val la = a.k * x + a.c
        val lb = b.k * x + b.c
        val lh = h.k * x + h.c
        board.add("∴ x = " + qf(x))
        board.add("$aName = " + qf(la) + ",  $bName = " + qf(lb) + ",  $hName = " + qf(lh))
        // draw and measure
        val pts = arrayOfNulls<Pt>(3)
        pts[right] = Pt(0.0, 0.0)
        pts[(right + 1) % 3] = Pt(lb, 0.0) // side between corner right and corner right+1
        pts[(right + 2) % 3] = Pt(0.0, la)
        val p = listOf(pts[0]!!, pts[1]!!, pts[2]!!)
        val m1 = dist(p[right], p[(right + 1) % 3])
        val m2 = dist(p[right], p[(right + 2) % 3])
        val m3 = dist(p[(right + 1) % 3], p[(right + 2) % 3])
        val ang = angDeg(p[right], p[(right + 1) % 3], p[(right + 2) % 3])
        val ok = abs(m1 - lb) < 1e-6 && abs(m2 - la) < 1e-6 && abs(m3 - lh) < 1e-6 && abs(ang - 90.0) < 0.01
        steps.add("✏️ Drawing the triangle with the real lengths…")
        steps.add(if (ok) "✅ Measured: ${qf(m1)}, ${qf(m2)}, ${qf(m3)} and the angle at ${nm[right]} is ${qf(ang)}° — it matches" else "✗ The drawn triangle does not match")
        if (!ok) return QResult(steps, emptyList(), null, "The triangle I drew did not match the answer.", think, board)
        val plan = QPlan(nm, p, setOf(0, 1, 2), setOf(right), emptySet(), "right triangle $nm")
        val ans = listOf("x = " + qf(x), "$aName = " + qf(la) + ", $bName = " + qf(lb) + ", $hName = " + qf(lh))
        steps.add("🎯 Answer ready")
        return QResult(steps, ans, plan, null, think, board)
    }

    private fun max0(v: Double) = if (v < 0) 0.0 else v
}
