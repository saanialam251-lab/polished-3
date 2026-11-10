package com.aismartmath.board.board

import com.aismartmath.board.math.angDeg
import com.aismartmath.board.math.dist
import com.aismartmath.board.model.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Angle questions without AI: a point D on BC (the line AD is joined if the question needs it, even when it does not say
 * so), two lines crossing, rays standing on a straight line, parallel lines with a transversal, x in the angles.
 *
 * The figure is drawn first. From the figure every true angle relation is collected (angle sum of a triangle, angles on
 * a straight line / linear pair, adjacent angles, angles around a point, vertically opposite angles, isosceles base
 * angles, exterior angle theorem, corresponding / alternate / co-interior angles for lines declared parallel).
 * The relations are then solved one at a time, each step written with its reason. The figure is rebuilt to the solved
 * values and measured, so the picture and the answer must agree.
 */
internal object AngleSolver {
    private val triRx = Regex("(?i:triangle)\\s+([A-Z]{3})(?![A-Za-z])")
    private val onRx = Regex("\\b([A-Z])\\s+(?i:is\\s+a\\s+point\\s+on|lies\\s+on|is\\s+on)\\s+(?:(?i:the)\\s+)?(?:(?i:side|segment|line)\\s+)?([A-Z]{2})\\b")
    private val onRx2 = Regex("(?i:a\\s+point)\\s+([A-Z])\\s+(?i:on)\\s+(?:(?i:side|segment|line)\\s+)?([A-Z]{2})\\b")
    private val midRx = Regex("\\b([A-Z])\\s+(?i:is\\s+the\\s+mid-?point\\s+of)\\s+(?:(?i:side|segment|line)\\s+)?([A-Z]{2})\\b")
    private val parRx = Regex("\\b([A-Z]{2})\\s*(?:\\|\\||(?i:is\\s+parallel\\s+to|parallel\\s+to))\\s*([A-Z]{2})\\b")
    private val crossRx = Regex("\\b([A-Z]{2})\\s+(?i:and)\\s+([A-Z]{2})\\s+(?i:intersect|cut|meet)(?:\\s+(?i:each\\s+other))?\\s+(?i:at)\\s+([A-Z])\\b")
    private val straightRx = Regex("\\b([A-Z]{3})\\s+(?i:is\\s+a\\s+straight\\s+line)")
    private val standsRx = Regex("\\b([A-Z]{2})\\s+(?i:stands\\s+on)\\s+(?:(?i:the\\s+)?(?:(?i:straight\\s+)?(?i:line)\\s+)?)([A-Z]{2})\\b")
    private val transRx = Regex("\\b([A-Z]{2})\\s+(?i:cuts|intersects|meets)\\s+([A-Z]{2})\\s+(?i:at)\\s+([A-Z])\\s+(?i:and)\\s+([A-Z]{2})\\s+(?i:at)\\s+([A-Z])\\b")
    private val valRx = Regex("∠\\s*([A-Z]{1,3})\\s*=\\s*([0-9xX(+\\-][0-9xX.+\\-\\s()]*)")
    private val eqRx = Regex("∠\\s*([A-Z]{1,3})\\s*=\\s*∠\\s*([A-Z]{1,3})")
    private val relRx = Regex("∠\\s*([A-Z]{1,3})\\s*(?:,|(?i:and))\\s*∠\\s*([A-Z]{1,3})\\s+(?i:are|is)\\s+(?:(?i:an?)\\s+)?((?i:complementary|supplementary|linear\\s+pair))")
    private val sumRx = Regex("∠\\s*([A-Z]{1,3})\\s*\\+\\s*∠\\s*([A-Z]{1,3})\\s*=\\s*(\\d+(?:\\.\\d+)?)")
    private val perpRx = Regex("\\b([A-Z]{2})\\s*(?:⊥|(?i:perpendicular\\s+to))\\s*([A-Z]{2})\\b")
    private val bisRx = Regex("\\b([A-Z]{2})\\s+(?i:bisects)\\s+(?:(?i:the)\\s+)?∠\\s*([A-Z]{1,3})")
    private val chainRx = Regex("\\b[A-Z]{2}\\b(?:\\s*=\\s*\\b[A-Z]{2}\\b)+")
    private val findRx = Regex("(?i)\\b(?:find|calculate|determine|compute|what\\s+is|what\\s+are)\\b")
    private val angTok = Regex("∠\\s*([A-Z]{1,3})")

    private class AF {
        var tri: String? = null
        val onSide = LinkedHashMap<Char, String>()
        val assumedSide = mutableSetOf<Char>()
        val mids = mutableSetOf<Char>()
        val bis = mutableListOf<Pair<String, String>>() // ray, angle token
        val parallel = mutableListOf<Pair<String, String>>()
        var cross: Triple<String, String, Char>? = null
        var straight: String? = null
        var trans: Triple<String, Char, Char>? = null // line EF, P on the first parallel line, Q on the second
        val vals = mutableListOf<Pair<String, Lin>>()
        val equal = mutableListOf<Pair<String, String>>()
        val sums = mutableListOf<Triple<String, String, Pair<Double, String>>>()
        val perp = mutableListOf<Pair<String, String>>()
        val sideEq = mutableListOf<Pair<String, String>>()
        val goals = mutableListOf<String>()
        var wantX = false
        val given = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        val letters = mutableSetOf<Char>()
    }

    fun looksAngle(raw: String): Boolean {
        val l = raw.lowercase()
        if (Regex("\\bprove\\b|show that|congruent|≅").containsMatchIn(l)) return false
        if (!(l.contains("∠") || l.contains("angle"))) return false
        return Regex("point on|lies on|intersect|straight line|stands on|parallel|∥|\\|\\||linear pair|complementary|supplementary|vertically opposite|transversal|midpoint|mid-point|bisect|a point").containsMatchIn(l)
    }

    private fun parse(raw: String, f: AF): Boolean {
        var t = raw.replace("△", " triangle ").replace("Δ", " triangle ").replace("∆", " triangle ").replace("▲", " triangle ")
        t = t.replace("∥", "||").replace("−", "-").replace("\n", " ")
        t = Regex("(?i)\\bangles?\\s+(?=[A-Z])").replace(t, "∠")
        t = Regex("(?i)\\bdegrees?\\b").replace(t, "°")
        for (ch in t) if (ch in 'A'..'Z') f.letters.add(ch)
        triRx.find(t)?.let { f.tri = it.groupValues[1] }
        val fm = findRx.find(t)
        val gp = if (fm != null) t.substring(0, fm.range.first) else t
        val qp = if (fm != null) t.substring(fm.range.last + 1) else ""
        for (m in onRx.findAll(gp)) f.onSide[m.groupValues[1][0]] = m.groupValues[2]
        for (m in onRx2.findAll(gp)) f.onSide[m.groupValues[1][0]] = m.groupValues[2]
        for (m in midRx.findAll(gp)) {
            f.onSide[m.groupValues[1][0]] = m.groupValues[2]
            f.mids.add(m.groupValues[1][0])
            f.given.add(m.groupValues[1] + " is the midpoint of " + m.groupValues[2])
        }
        for ((d, s) in f.onSide) if (d !in f.mids) f.given.add("$d is a point on $s")
        for (m in parRx.findAll(gp)) {
            f.parallel.add(Pair(m.groupValues[1], m.groupValues[2]))
            f.given.add(m.groupValues[1] + " ∥ " + m.groupValues[2])
        }
        crossRx.find(gp)?.let {
            f.cross = Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3][0])
            f.given.add(it.groupValues[1] + " and " + it.groupValues[2] + " intersect at " + it.groupValues[3])
        }
        straightRx.find(gp)?.let {
            f.straight = it.groupValues[1]
            f.given.add(it.groupValues[1] + " is a straight line")
        }
        standsRx.find(gp)?.let {
            val ray = it.groupValues[1]
            val line = it.groupValues[2]
            f.straight = "" + line[0] + ray[0] + line[1]
            f.given.add(ray + " stands on the straight line " + line)
        }
        transRx.find(gp)?.let {
            f.trans = Triple(it.groupValues[1], it.groupValues[3][0], it.groupValues[5][0])
            f.given.add(it.groupValues[1] + " cuts " + it.groupValues[2] + " at " + it.groupValues[3] + " and " + it.groupValues[4] + " at " + it.groupValues[5])
        }
        for (m in chainRx.findAll(gp)) {
            val parts = m.value.split("=").map { it.trim() }
            for (i in 0 until parts.size - 1) f.sideEq.add(Pair(sk(parts[i]), sk(parts[i + 1])))
            f.given.add(m.value)
        }
        for (m in perpRx.findAll(gp)) {
            f.perp.add(Pair(m.groupValues[1], m.groupValues[2]))
            f.given.add(m.groupValues[1] + " ⊥ " + m.groupValues[2])
        }
        for (m in bisRx.findAll(gp)) {
            f.bis.add(Pair(m.groupValues[1], m.groupValues[2]))
            f.given.add(m.groupValues[1] + " bisects ∠" + m.groupValues[2])
        }
        val used = mutableListOf<IntRange>()
        for (m in eqRx.findAll(gp)) {
            used.add(m.range)
            f.equal.add(Pair(m.groupValues[1], m.groupValues[2]))
            f.given.add("∠" + m.groupValues[1] + " = ∠" + m.groupValues[2])
            f.tokens.add(m.groupValues[1])
            f.tokens.add(m.groupValues[2])
        }
        for (m in valRx.findAll(gp)) {
            if (used.any { m.range.first in it }) continue
            val l = parseLin(m.groupValues[2].trim()) ?: continue
            f.vals.add(Pair(m.groupValues[1], l))
            f.given.add("∠" + m.groupValues[1] + " = " + (if (l.k == 0.0) qf(l.c) + "°" else linText(l) + "°"))
            f.tokens.add(m.groupValues[1])
        }
        for (m in relRx.findAll(gp)) {
            val kind = m.groupValues[3].lowercase()
            val total = if (kind.startsWith("complementary")) 90.0 else 180.0
            val why = if (kind.startsWith("complementary")) "complementary angles (sum 90°)" else if (kind.startsWith("supplementary")) "supplementary angles (sum 180°)" else "linear pair (sum 180°)"
            f.sums.add(Triple(m.groupValues[1], m.groupValues[2], Pair(total, why)))
            f.given.add("∠" + m.groupValues[1] + " and ∠" + m.groupValues[2] + " are " + kind)
            f.tokens.add(m.groupValues[1])
            f.tokens.add(m.groupValues[2])
        }
        for (m in sumRx.findAll(gp)) {
            val total = m.groupValues[3].toDouble()
            val why = if (total == 90.0) "complementary angles (sum 90°)" else if (total == 180.0) "supplementary angles (sum 180°)" else "given sum"
            f.sums.add(Triple(m.groupValues[1], m.groupValues[2], Pair(total, why)))
            f.given.add("∠" + m.groupValues[1] + " + ∠" + m.groupValues[2] + " = " + qf(total) + "°")
            f.tokens.add(m.groupValues[1])
            f.tokens.add(m.groupValues[2])
        }
        if (Regex("(?i)\\bisosceles\\b").containsMatchIn(gp) && f.sideEq.isEmpty()) {
            val s = f.tri
            if (s != null) {
                f.sideEq.add(Pair(sk(s[0], s[1]), sk(s[0], s[2])))
                f.given.add("" + s[0] + s[1] + " = " + s[0] + s[2] + " (isosceles)")
            }
        }
        // ---- goals
        if (Regex("\\bx\\b").containsMatchIn(qp)) f.wantX = true
        for (m in angTok.findAll(qp)) {
            f.goals.add(m.groupValues[1])
            f.tokens.add(m.groupValues[1])
        }
        if (f.goals.isEmpty() && !f.wantX) return false
        // a point named only in an angle, like ∠ADC in triangle ABC: assume it lies on a side
        val tri = f.tri
        if (tri != null) {
            for (tok in f.tokens.filter { it.length == 3 }) {
                val v = tok[1]
                if (v !in tri && v !in f.onSide && tok[0] in tri && tok[2] in tri) {
                    val apex = tok[0]
                    f.onSide[v] = tri.filter { it != apex }
                    f.assumedSide.add(v)
                }
            }
        }
        return f.tri != null || f.cross != null || f.straight != null || f.parallel.isNotEmpty()
    }

    // ---------------------------------------------------------------- geometry of the figure

    private fun canon(d: Diagram, k: String): String {
        val v = k[1]
        val pv = pp(d, v)
        fun rep(x: Char): Char {
            val px = pp(d, x)
            var best = x
            val ux = px.x - pv.x
            val uy = px.y - pv.y
            val lu = sqrt(ux * ux + uy * uy)
            for ((c, p) in d.pts) {
                if (c == v || c >= best) continue
                val wx = p.x - pv.x
                val wy = p.y - pv.y
                val lw = sqrt(wx * wx + wy * wy)
                if (lu < 1e-9 || lw < 1e-9) continue
                if (abs(ux * wy - uy * wx) / (lu * lw) < 1e-6 && ux * wx + uy * wy > 0) best = c
            }
            return best
        }
        return ak(rep(k[0]), v, rep(k[2]))
    }

    private fun measure(d: Diagram, k: String): Double = angDeg(pp(d, k[1]), pp(d, k[0]), pp(d, k[2]))

    private class RayI(val th: Double, var letter: Char)

    private fun angDiff(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0

    private fun raysAt(d: Diagram, p: Char): List<RayI> {
        val p0 = pp(d, p)
        val out = mutableListOf<RayI>()
        fun addRay(q: Char) {
            val v = pp(d, q)
            val th = (Math.toDegrees(atan2(v.y - p0.y, v.x - p0.x)) + 360.0) % 360.0
            val ex = out.firstOrNull { abs(angDiff(it.th, th)) < 1e-4 }
            if (ex == null) out.add(RayI(th, q)) else if (q < ex.letter) ex.letter = q
        }
        for (s in d.segs) {
            val a = s[0]
            val b = s[1]
            if (p == a) addRay(b)
            else if (p == b) addRay(a)
            else if (onSegInc(p0, pp(d, a), pp(d, b))) {
                addRay(a)
                addRay(b)
            }
        }
        return out.sortedBy { it.th }
    }

    private class LineI(val pts: Set<Char>, val dx: Double, val dy: Double)

    private fun linesOf(d: Diagram): List<LineI> {
        val out = mutableListOf<LineI>()
        for (s in d.segs) {
            val a = pp(d, s[0])
            val b = pp(d, s[1])
            val len = dist(a, b)
            if (len < 1e-9) continue
            val set = d.pts.filter { (_, p) -> abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) / len < 1e-6 }.keys
            if (out.none { it.pts == set }) out.add(LineI(set, (b.x - a.x) / len, (b.y - a.y) / len))
        }
        return out
    }

    // ---------------------------------------------------------------- equations

    private class AEq(val terms: List<Pair<String, Double>>, val rhs: Lin, val why: String)

    private fun lAdd(a: Lin, b: Lin) = Lin(a.k + b.k, a.c + b.c)
    private fun lSub(a: Lin, b: Lin) = Lin(a.k - b.k, a.c - b.c)
    private fun lMul(a: Lin, m: Double) = Lin(a.k * m, a.c * m)
    private fun lDeg(l: Lin): String = (if (l.k == 0.0) qf(l.c) else linText(l)) + "°"

    private fun tokKey(f: AF, d: Diagram, tok: String): String? {
        if (tok.length == 3) return canon(d, ak(tok))
        if (tok.length == 1) {
            val t = f.tri ?: return null
            val i = t.indexOf(tok[0])
            if (i < 0) return null
            return canon(d, ak(t[(i + 2) % 3], t[i], t[(i + 1) % 3]))
        }
        return null
    }

    private fun genEqs(d: Diagram, f: AF, counts: MutableMap<String, Int>): List<AEq> {
        val out = mutableListOf<AEq>()
        val seen = HashSet<String>()
        fun add(terms: List<Pair<String, Double>>, rhs: Lin, why: String, rule: String) {
            val sig = terms.sortedBy { it.first }.joinToString("|") { it.first + ":" + it.second } + "=" + rhs.k + "," + rhs.c
            if (!seen.add(sig)) return
            out.add(AEq(terms, rhs, why))
            counts[rule] = (counts[rule] ?: 0) + 1
        }
        fun kk(a: Char, v: Char, c: Char) = canon(d, ak(a, v, c))
        val zero = Lin(0.0, 0.0)
        // ---- the question's own facts
        for ((tok, l) in f.vals) {
            val k = tokKey(f, d, tok) ?: continue
            add(listOf(Pair(k, 1.0)), l, "Given", "given")
        }
        for ((a, b) in f.equal) {
            val k1 = tokKey(f, d, a) ?: continue
            val k2 = tokKey(f, d, b) ?: continue
            if (k1 != k2) add(listOf(Pair(k1, 1.0), Pair(k2, -1.0)), zero, "Given", "given")
        }
        for ((a, b, tr) in f.sums) {
            val k1 = tokKey(f, d, a) ?: continue
            val k2 = tokKey(f, d, b) ?: continue
            add(listOf(Pair(k1, 1.0), Pair(k2, 1.0)), Lin(0.0, tr.first), tr.second, "given")
        }
        for ((l1, l2) in f.perp) {
            val foot = l1.firstOrNull { it in d.pts && onSegInc(pp(d, it), pp(d, l2[0]), pp(d, l2[1])) } ?: continue
            val x = l1.first { it != foot }
            for (y in l2.filter { it != foot }) add(listOf(Pair(kk(x, foot, y), 1.0)), Lin(0.0, 90.0), "Given: $l1 ⊥ $l2", "given")
        }
        for ((ray, tok) in f.bis) {
            val k = tokKey(f, d, tok) ?: continue
            val a = ray[0]
            val dd = ray[1]
            if (dd !in d.pts) continue
            val arms = tokArms(f, tok) ?: continue
            add(listOf(Pair(kk(arms.first, a, dd), 1.0), Pair(kk(dd, a, arms.second), -1.0)), zero, "Given: $ray bisects ∠$tok", "given")
            if (k.isEmpty()) continue
        }
        // ---- triangles
        val tris = trianglesOf(d)
        val sideUf = HashMap<String, String>()
        fun fnd(x: String): String {
            var r = x
            while (sideUf[r] != null && sideUf[r] != r) r = sideUf[r]!!
            return r
        }
        for ((a, b) in f.sideEq) {
            val ra = fnd(a)
            val rb = fnd(b)
            if (ra != rb) sideUf[ra] = rb
        }
        for (t in tris) {
            add(
                listOf(Pair(kk(t[1], t[0], t[2]), 1.0), Pair(kk(t[0], t[1], t[2]), 1.0), Pair(kk(t[0], t[2], t[1]), 1.0)),
                Lin(0.0, 180.0), "angle sum of a triangle", "triangle",
            )
            for (r in 0..2) {
                val v = t[r]
                val x = t[(r + 1) % 3]
                val y = t[(r + 2) % 3]
                if (fnd(sk(v, x)) == fnd(sk(v, y))) {
                    add(listOf(Pair(kk(v, x, y), 1.0), Pair(kk(v, y, x), -1.0)), zero, "$v$x = $v$y, so the angles opposite them are equal", "isosceles")
                }
            }
        }
        // ---- around every point
        for (p in d.pts.keys.sorted()) {
            val rs = raysAt(d, p)
            val m = rs.size
            if (m < 3 || m > 7) continue
            val gaps = DoubleArray(m) { i ->
                val n = rs[(i + 1) % m].th - rs[i].th
                if (n <= 0) n + 360.0 else n
            }
            fun kg(i: Int) = kk(rs[i % m].letter, p, rs[(i + 1) % m].letter)
            for (i in 0 until m) for (j in i + 1 until m) {
                val oi = rs.indexOfFirst { abs(angDiff(it.th, rs[i].th + 180.0)) < 1e-4 }
                val oj = rs.indexOfFirst { abs(angDiff(it.th, rs[j].th + 180.0)) < 1e-4 }
                if (oi < 0 || oj < 0 || oi == j || oj == i) continue
                val k1 = kk(rs[i].letter, p, rs[j].letter)
                val k2 = kk(rs[oi].letter, p, rs[oj].letter)
                if (k1 != k2) add(listOf(Pair(k1, 1.0), Pair(k2, -1.0)), zero, "vertically opposite angles", "vertical")
            }
            for (i in 0 until m) {
                var arc = 0.0
                val gapKeys = mutableListOf<String>()
                for (l in 1 until m) {
                    val gi = (i + l - 1) % m
                    if (gaps[gi] >= 180.0 - 1e-6) break
                    arc += gaps[gi]
                    gapKeys.add(kg(gi))
                    if (l >= 2 && arc < 180.0 - 1e-6) {
                        add(listOf(Pair(kk(rs[i].letter, p, rs[(i + l) % m].letter), 1.0)) + gapKeys.map { Pair(it, -1.0) }, zero, "adjacent angles (angle addition)", "adjacent")
                    }
                    if (l >= 2 && abs(arc - 180.0) < 1e-6) {
                        add(gapKeys.map { Pair(it, 1.0) }, Lin(0.0, 180.0), "angles on a straight line (linear pair)", "straight")
                    }
                    if (arc >= 180.0 - 1e-6) break
                }
            }
            // a straight line with rays on one side: the gap that is exactly 180° closes the other gaps
            for (i in 0 until m) {
                if (abs(gaps[i] - 180.0) < 1e-6) {
                    val ks = ArrayList<String>()
                    var tot = 0.0
                    var okAll = true
                    for (l in 1 until m) {
                        val gi = (i + l) % m
                        if (gaps[gi] >= 180.0 - 1e-6) {
                            okAll = false
                            break
                        }
                        tot += gaps[gi]
                        ks.add(kg(gi))
                    }
                    if (okAll && ks.size >= 2 && abs(tot - 180.0) < 1e-6) add(ks.map { Pair(it, 1.0) }, Lin(0.0, 180.0), "angles on a straight line (linear pair)", "straight")
                }
            }
            if (gaps.all { it < 180.0 - 1e-6 }) add((0 until m).map { Pair(kg(it), 1.0) }, Lin(0.0, 360.0), "angles around a point", "around")
        }
        // ---- exterior angle theorem
        for (t in tris) for (xi in 0..2) for (yi in 0..2) {
            if (xi == yi) continue
            val x = t[xi]
            val y = t[yi]
            val z = t[3 - xi - yi]
            val py = pp(d, y)
            val pz = pp(d, z)
            for ((w, pw) in d.pts) {
                if (w == x || w == y || w == z) continue
                val cr = abs((pz.x - py.x) * (pw.y - pz.y) - (pz.y - py.y) * (pw.x - pz.x))
                val dot = (pz.x - py.x) * (pw.x - pz.x) + (pz.y - py.y) * (pw.y - pz.y)
                if (cr < 1e-6 && dot > 0 && connectedPts(d, z, w)) {
                    add(
                        listOf(Pair(kk(x, z, w), 1.0), Pair(kk(y, x, z), -1.0), Pair(kk(x, y, z), -1.0)),
                        zero, "exterior angle theorem (an exterior angle equals the sum of the two opposite interior angles)", "exterior",
                    )
                }
            }
        }
        // ---- parallel lines
        if (f.parallel.isNotEmpty()) parallelEqs(d, f, ::add)
        return out
    }

    private fun tokArms(f: AF, tok: String): Pair<Char, Char>? {
        if (tok.length == 3) return Pair(tok[0], tok[2])
        val t = f.tri ?: return null
        val i = t.indexOf(tok[0])
        if (i < 0) return null
        return Pair(t[(i + 1) % 3], t[(i + 2) % 3])
    }

    private class RX(val p: Char, val letter: Char, val line: Int, val cls: Int, val sgn: Int)

    private fun parallelEqs(d: Diagram, f: AF, add: (List<Pair<String, Double>>, Lin, String, String) -> Unit) {
        val lines = linesOf(d)
        if (lines.isEmpty()) return
        val uf = IntArray(lines.size) { it }
        fun fnd(x: Int): Int {
            var r = x
            while (uf[r] != r) r = uf[r]
            return r
        }
        for ((a, b) in f.parallel) {
            val i = lines.indexOfFirst { a[0] in it.pts && a[1] in it.pts }
            val j = lines.indexOfFirst { b[0] in it.pts && b[1] in it.pts }
            if (i >= 0 && j >= 0 && i != j) uf[fnd(i)] = fnd(j)
        }
        val classSize = HashMap<Int, Int>()
        for (i in lines.indices) classSize[fnd(i)] = (classSize[fnd(i)] ?: 0) + 1
        val all = mutableListOf<RX>()
        for (p in d.pts.keys.sorted()) {
            val p0 = pp(d, p)
            for (r in raysAt(d, p)) {
                val li = lines.indexOfFirst { p in it.pts && r.letter in it.pts }
                if (li < 0) continue
                val q = pp(d, r.letter)
                val root = fnd(li)
                val ref = lines[root]
                val dot = (q.x - p0.x) * ref.dx + (q.y - p0.y) * ref.dy
                all.add(RX(p, r.letter, li, root, if (dot > 0) 1 else -1))
            }
        }
        fun kk(a: Char, v: Char, c: Char) = canon(d, ak(a, v, c))
        for (a in all) for (b in all) {
            if (a.p != b.p || a.letter >= b.letter || a.cls == b.cls) continue
            if ((classSize[a.cls] ?: 1) < 2 && (classSize[b.cls] ?: 1) < 2) continue
            for (c in all) for (e in all) {
                if (c.p != e.p || c.p == a.p || c.letter == e.letter) continue
                if (c.cls != a.cls || e.cls != b.cls) continue
                if (c.line == a.line && e.line == b.line) continue
                val k1 = kk(a.letter, a.p, b.letter)
                val k2 = kk(c.letter, c.p, e.letter)
                if (k1 == k2) continue
                val s1 = a.sgn * c.sgn
                val s2 = b.sgn * e.sgn
                if (s1 == s2) {
                    add(listOf(Pair(k1, 1.0), Pair(k2, -1.0)), Lin(0.0, 0.0), if (s1 > 0) "corresponding angles (parallel lines)" else "alternate angles (parallel lines)", "parallel")
                } else {
                    add(listOf(Pair(k1, 1.0), Pair(k2, 1.0)), Lin(0.0, 180.0), "co-interior angles between parallel lines add up to 180°", "parallel")
                }
            }
        }
    }

    // ---------------------------------------------------------------- solving, one step at a time

    private class Prop(val eqs: List<AEq>, val label: (String) -> String) {
        val known = LinkedHashMap<String, Lin>()
        var x: Double? = null
        val steps = mutableListOf<Pair<String, String>>() // text, reason
        var conflict: String? = null

        fun sub(l: Lin): Lin {
            val xv = x
            return if (xv == null) l else Lin(0.0, l.k * xv + l.c)
        }

        fun value(k: String): Double? {
            val l = known[k] ?: return null
            val s = sub(l)
            return if (abs(s.k) < 1e-12) s.c else null
        }

        private fun lAddL(a: Lin, b: Lin) = Lin(a.k + b.k, a.c + b.c)

        private fun show(l: Lin): String = (if (l.k == 0.0) qf(l.c) else linText(l)) + "°"

        private fun wrap(s: String): String = if (s.contains("+") || s.contains(" - ") || s.startsWith("-")) "($s)" else s

        private fun derive(e: AEq, target: String, coef: Double, r: Lin, result: Lin): String {
            val sym = StringBuilder()
            val num = StringBuilder()
            fun part(sign: Double, symText: String, numText: String) {
                if (sym.isEmpty()) {
                    sym.append(if (sign < 0) "-" else "").append(symText)
                    num.append(if (sign < 0) "-" else "").append(numText)
                } else {
                    sym.append(if (sign < 0) " − " else " + ").append(symText)
                    num.append(if (sign < 0) " − " else " + ").append(numText)
                }
            }
            if (e.rhs.k != 0.0 || e.rhs.c != 0.0) part(1.0, show(e.rhs), show(e.rhs))
            for ((k, c) in e.terms) {
                if (k == target) continue
                val v = known[k] ?: Lin(0.0, 0.0)
                val sv = sub(v)
                val coefText = if (abs(abs(c) - 1.0) < 1e-12) "" else qf(abs(c)) + "×"
                part(-c, coefText + label(k), coefText + wrap(show(sv)))
            }
            var s1 = sym.toString()
            var n1 = num.toString()
            if (abs(coef - 1.0) > 1e-12) {
                s1 = "($s1)/" + qf(coef)
                n1 = "($n1)/" + qf(coef)
            }
            val head = label(target) + " = " + s1
            val res = show(sub(result))
            return if (n1 == s1) "$head = $res" else "$head = $n1 = $res"
        }

        fun run() {
            var guard = 0
            var changed = true
            while (changed && guard < 300) {
                changed = false
                guard++
                for (e in eqs) {
                    val unknown = e.terms.filter { it.first !in known }
                    var s = Lin(0.0, 0.0)
                    for ((k, c) in e.terms) {
                        val v = known[k]
                        if (v != null) s = lAddL(s, Lin(sub(v).k * c, sub(v).c * c))
                    }
                    val rs = sub(e.rhs)
                    val r = Lin(rs.k - s.k, rs.c - s.c)
                    if (unknown.size == 1) {
                        val k = unknown[0].first
                        val c = unknown[0].second
                        if (abs(c) < 1e-12) continue
                        val v = Lin(r.k / c, r.c / c)
                        known[k] = v
                        if (e.why != "Given") steps.add(Pair(derive(e, k, c, r, v), e.why))
                        changed = true
                    } else if (unknown.isEmpty()) {
                        if (x == null && abs(r.k) > 1e-12) {
                            val xv = -r.c / r.k
                            val lhs = e.terms.joinToString(" + ") { (k, c) ->
                                val kv = known[k] ?: Lin(0.0, 0.0)
                                (if (abs(c - 1.0) < 1e-12) "" else qf(c) + "×") + wrap(if (kv.k == 0.0) qf(kv.c) else linText(kv))
                            }
                            val net = Lin(s.k - e.rhs.k, s.c - e.rhs.c)
                            steps.add(Pair("$lhs = ${qf(e.rhs.c)}  ⇒  ${linText(Lin(net.k, 0.0))} = ${qf(-net.c)}  ⇒  x = ${qf(xv)}", e.why))
                            x = xv
                            changed = true
                        } else if (abs(r.k) < 1e-9 && abs(r.c) > 1e-6 && conflict == null) {
                            conflict = "The facts disagree: " + e.why + " gives a difference of " + qf(r.c) + "°"
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- building the figure

    private class Built(val d: Diagram, val notes: List<String>)

    private fun sideFig(pts: Map<Char, Pt>, a: Char, b: Char) = fig("seg", listOf(pts[a]!!, pts[b]!!), "" + a + b)

    /** The parameter value in [lo, hi] with the smallest error, found by a coarse scan then a fine one. */
    private fun fit(lo: Double, hi: Double, err: (Double) -> Double): Double {
        var best = lo
        var be = Double.MAX_VALUE
        var x = lo
        while (x <= hi + 1e-9) {
            val e = err(x)
            if (e < be) {
                be = e
                best = x
            }
            x += (hi - lo) / 200.0
        }
        var a = max(lo, best - (hi - lo) / 200.0)
        val b = min(hi, best + (hi - lo) / 200.0)
        var y = a
        be = Double.MAX_VALUE
        while (y <= b + 1e-12) {
            val e = err(y)
            if (e < be) {
                be = e
                best = y
            }
            y += (hi - lo) / 20000.0
        }
        a = best
        return a
    }

    private fun errAt(d: Diagram, verts: List<Char>, lk: (String) -> Double?): Double {
        var tot = 0.0
        var n = 0
        val ls = d.pts.keys.sorted()
        for (v in verts) for (i in ls.indices) for (j in i + 1 until ls.size) {
            val a = ls[i]
            val c = ls[j]
            if (a == v || c == v) continue
            val k = lk(ak(a, v, c)) ?: continue
            val m = measure(d, ak(a, v, c))
            tot += (m - k) * (m - k)
            n++
        }
        return if (n == 0) 0.0 else tot
    }

    private fun buildFA(f: AF, lk: ((String) -> Double?)?, style: Int): Built? {
        val tri = f.tri ?: return null
        val notes = mutableListOf<String>()
        val angA = lk?.invoke(ak(tri[1], tri[0], tri[2]))
        val angB = lk?.invoke(ak(tri[0], tri[1], tri[2]))
        val angC = lk?.invoke(ak(tri[0], tri[2], tri[1]))
        val tp: List<Pt>
        if (angA != null && angB != null && angC != null && angA > 1 && angB > 1 && angC > 1) {
            val L = 6.0
            val t = L * sin(angC * PI / 180) / sin(angA * PI / 180)
            tp = listOf(Pt(t * cos(angB * PI / 180), t * sin(angB * PI / 180)), Pt(0.0, 0.0), Pt(L, 0.0))
        } else {
            val c = TriCons()
            for ((a, b) in f.sideEq) {
                if (a.all { it in tri } && b.all { it in tri }) {
                    val i = tri.indexOfFirst { it !in a }
                    val j = tri.indexOfFirst { it !in b }
                    if (i >= 0 && j >= 0 && i != j) c.sideEq.add(intArrayOf(i, j))
                }
            }
            tp = triPts(c, style)
        }
        val pts = mutableMapOf<Char, Pt>()
        for (i in 0..2) pts[tri[i]] = tp[i]
        val segs = mutableListOf(sk(tri[0], tri[1]), sk(tri[1], tri[2]), sk(tri[2], tri[0]))
        val figs = mutableListOf(fig("tri", tp, tri))
        val rowsMap = mutableMapOf<String, MutableList<Pair<Double, Char>>>()
        val frac = HashMap<Char, Double>()
        for ((dch, side) in f.onSide) {
            if (dch in tri || side.length != 2 || side.any { it !in tri }) continue
            val x = pts[side[0]]!!
            val y = pts[side[1]]!!
            val apex = tri.first { it !in side }
            val ap = pts[apex]!!
            var t = when {
                dch in f.mids -> 0.5
                f.bis.any { it.first.length == 2 && it.first[1] == dch } -> dist(ap, x) / (dist(ap, x) + dist(ap, y))
                else -> 0.4
            }
            if (lk != null && dch !in f.mids) {
                val verts = listOf(dch, apex)
                val known = f.tokens.isNotEmpty()
                if (known) {
                    fun mk(tt: Double): Diagram {
                        val dp = Pt(x.x + (y.x - x.x) * tt, x.y + (y.y - x.y) * tt)
                        val m2 = HashMap<Char, Pt>(pts)
                        m2[dch] = dp
                        return Diagram(m2, listOf(sk(side[0], dch), sk(dch, side[1]), sk(apex, dch)), emptyList(), emptyList(), emptyList(), emptyList(), "")
                    }
                    // only move D when some known angle actually depends on where D is
                    if (abs(errAt(mk(0.3), verts, lk) - errAt(mk(0.7), verts, lk)) > 1e-9) t = fit(0.02, 0.98) { tt -> errAt(mk(tt), verts, lk) }
                }
            }
            val dp = Pt(x.x + (y.x - x.x) * t, x.y + (y.y - x.y) * t)
            pts[dch] = dp
            frac[dch] = if (side[0] == tri.first { it == side[0] }) t else 1 - t
            val rk = sk(side[0], side[1])
            rowsMap.getOrPut(rk) { mutableListOf() }.add(Pair(if (side[0] <= side[1]) t else 1 - t, dch))
            segs.add(sk(side[0], dch))
            segs.add(sk(dch, side[1]))
            figs.add(fig("seg", listOf(x, dp), "" + side[0] + dch))
            figs.add(fig("seg", listOf(dp, y), "" + dch + side[1]))
        }
        // a line DE parallel to BC cutting two sides: E sits at the same fraction from the apex as D
        for ((a, b) in f.parallel) {
            val pair = listOf(a, b)
            val de = pair.firstOrNull { it.all { c -> c in f.onSide } && it.length == 2 } ?: continue
            val d1 = de[0]
            val e1 = de[1]
            val s1 = f.onSide[d1] ?: continue
            val s2 = f.onSide[e1] ?: continue
            val apex = s1.firstOrNull { it in s2 } ?: continue
            val p1 = pts[d1] ?: continue
            val pa = pts[apex] ?: continue
            val o1 = s1.first { it != apex }
            val o2 = s2.first { it != apex }
            val ratio = dist(pa, p1) / dist(pa, pts[o1]!!)
            val q = pts[o2]!!
            val ne = Pt(pa.x + (q.x - pa.x) * ratio, pa.y + (q.y - pa.y) * ratio)
            pts[e1] = ne
            // rebuild the E pieces
            figs.removeAll { it.nm == "" + s2[0] + e1 || it.nm == "" + e1 + s2[1] }
            segs.removeAll { it == sk(s2[0], e1) || it == sk(e1, s2[1]) }
            segs.add(sk(s2[0], e1))
            segs.add(sk(e1, s2[1]))
            figs.add(fig("seg", listOf(pts[s2[0]]!!, ne), "" + s2[0] + e1))
            figs.add(fig("seg", listOf(ne, pts[s2[1]]!!), "" + e1 + s2[1]))
            segs.add(sk(d1, e1))
            figs.add(fig("seg", listOf(p1, ne), "" + d1 + e1))
            val rk = sk(s2[0], s2[1])
            rowsMap.remove(rk)
            rowsMap.getOrPut(rk) { mutableListOf() }.add(Pair(if (s2[0] <= s2[1]) (dist(pts[s2[0]]!!, ne) / dist(pts[s2[0]]!!, pts[s2[1]]!!)) else 1 - (dist(pts[s2[0]]!!, ne) / dist(pts[s2[0]]!!, pts[s2[1]]!!)), e1))
        }
        // implicit constructions: every angle in the question needs its two arms drawn
        for (tok in f.tokens.filter { it.length == 3 }.distinct()) {
            for (pair in listOf("" + tok[0] + tok[1], "" + tok[1] + tok[2])) {
                if (pair[0] in pts && pair[1] in pts && !connectedPts(Diagram(pts, segs, emptyList(), emptyList(), emptyList(), emptyList(), ""), pair[0], pair[1])) {
                    segs.add(sk(pair[0], pair[1]))
                    figs.add(fig("seg", listOf(pts[pair[0]]!!, pts[pair[1]]!!), pair))
                    notes.add("Join ${pair[0]}${pair[1]} (the question does not say so, but ∠$tok needs it)")
                }
            }
        }
        val rows = rowsMap.map { (k, v) -> "" + k[0] + v.sortedBy { it.first }.joinToString("") { it.second.toString() } + k[1] }
        return Built(Diagram(pts, segs, rows, emptyList(), notes, figs, "△$tri"), notes)
    }

    private fun buildFB(f: AF, lk: ((String) -> Double?)?, style: Int): Built? {
        val cr = f.cross ?: return null
        val a = cr.first[0]
        val b = cr.first[1]
        val c = cr.second[0]
        val dd = cr.second[1]
        val o = cr.third
        if (setOf(a, b, c, dd, o).size != 5) return null
        fun make(phi: Double): Diagram {
            val r = phi * PI / 180
            val pts = mapOf(a to Pt(-4.0, 0.0), b to Pt(4.0, 0.0), o to Pt(0.0, 0.0), c to Pt(3.5 * cos(r), 3.5 * sin(r)), dd to Pt(-3.5 * cos(r), -3.5 * sin(r)))
            val segs = listOf(sk(a, o), sk(o, b), sk(c, o), sk(o, dd))
            val figs = segs.map { fig("seg", listOf(pts[it[0]]!!, pts[it[1]]!!), it) }
            return Diagram(pts, segs, listOf("" + a + o + b, "" + c + o + dd), listOf(Triple(o, cr.first, cr.second)), emptyList(), figs, "lines ${cr.first} and ${cr.second} crossing at $o")
        }
        val phi = if (lk != null) fit(5.0, 175.0) { p -> errAt(make(p), listOf(o), lk) } else 55.0 + 8.0 * (style % 3)
        return Built(make(phi), emptyList())
    }

    private fun buildFC(f: AF, lk: ((String) -> Double?)?, style: Int): Built? {
        val row = f.straight ?: return null
        if (row.length != 3) return null
        val a = row[0]
        val o = row[1]
        val b = row[2]
        val rays = mutableListOf<Char>()
        for (tok in f.tokens.filter { it.length == 3 }) for (ch in tok) if (ch !in row && ch !in rays) rays.add(ch)
        if (rays.isEmpty()) return null
        val pts = mutableMapOf<Char, Pt>()
        pts[a] = Pt(-4.0, 0.0)
        pts[o] = Pt(0.0, 0.0)
        pts[b] = Pt(4.0, 0.0)
        for ((i, x) in rays.withIndex()) {
            var alpha = 180.0 * (i + 1) / (rays.size + 1) + 6.0 * (style % 3)
            val l1 = lk?.invoke(ak(a, o, x))
            val l2 = lk?.invoke(ak(x, o, b))
            if (l1 != null) alpha = l1 else if (l2 != null) alpha = 180.0 - l2
            val th = (180.0 - alpha) * PI / 180
            pts[x] = Pt(3.5 * cos(th), 3.5 * sin(th))
        }
        val segs = mutableListOf(sk(a, o), sk(o, b))
        for (x in rays) segs.add(sk(o, x))
        val figs = segs.map { fig("seg", listOf(pts[it[0]]!!, pts[it[1]]!!), it) }
        return Built(Diagram(pts, segs, listOf(row), emptyList(), emptyList(), figs, "straight line $row with rays"), emptyList())
    }

    private fun buildFD(f: AF, lk: ((String) -> Double?)?, style: Int): Built? {
        val tr = f.trans ?: return null
        val par = f.parallel.firstOrNull() ?: return null
        val ab = par.first
        val cd = par.second
        val e = tr.first[0]
        val ff = tr.first[1]
        val p = tr.second
        val q = tr.third
        if (setOf(ab[0], ab[1], cd[0], cd[1], e, ff, p, q).size != 8) return null
        fun make(phi: Double): Diagram {
            val r = phi * PI / 180
            val qp = Pt(0.0, 0.0)
            val pp0 = Pt(2.5 / tan(r), 2.5)
            val pts = mapOf(
                cd[0] to Pt(-4.0, 0.0), cd[1] to Pt(4.0, 0.0), q to qp,
                ab[0] to Pt(pp0.x - 4.0, 2.5), ab[1] to Pt(pp0.x + 4.0, 2.5), p to pp0,
                e to Pt(pp0.x + 1.6 * cos(r), 2.5 + 1.6 * sin(r)), ff to Pt(-1.6 * cos(r), -1.6 * sin(r)),
            )
            val segs = listOf(sk(ab[0], p), sk(p, ab[1]), sk(cd[0], q), sk(q, cd[1]), sk(e, p), sk(p, q), sk(q, ff))
            val figs = segs.map { fig("seg", listOf(pts[it[0]]!!, pts[it[1]]!!), it) }
            return Diagram(pts, segs, listOf("" + ab[0] + p + ab[1], "" + cd[0] + q + cd[1], "" + e + p + q + ff), emptyList(), emptyList(), figs, "parallel lines $ab and $cd with transversal ${tr.first}")
        }
        val phi = if (lk != null) fit(10.0, 170.0) { x -> errAt(make(x), listOf(p, q), lk) } else 60.0 + 7.0 * (style % 3)
        return Built(make(phi), emptyList())
    }

    private fun family(f: AF): String? = when {
        f.cross != null -> "B"
        f.straight != null -> "C"
        f.tri == null && f.parallel.isNotEmpty() && f.trans != null -> "D"
        f.tri != null -> "A"
        else -> null
    }

    private fun build(f: AF, fam: String, lk: ((String) -> Double?)?, style: Int): Built? = when (fam) {
        "A" -> buildFA(f, lk, style)
        "B" -> buildFB(f, lk, style)
        "C" -> buildFC(f, lk, style)
        else -> buildFD(f, lk, style)
    }

    // ---------------------------------------------------------------- the entry point

    fun solve(raw: String, variant: Int): QResult? {
        val f = AF()
        if (!parse(raw, f)) return null
        val fam = family(f) ?: return null
        val steps = mutableListOf<String>()
        val think = mutableListOf<String>()
        steps.add(if (variant > 0) "🔁 Rechecking — reading #" + (variant + 1) else "📖 Reading the question…")
        for (g in f.given) steps.add("Given: $g")
        if (f.goals.isNotEmpty()) steps.add("Goal: find " + f.goals.joinToString(", ") { "∠$it" } + (if (f.wantX) " and x" else ""))
        else if (f.wantX) steps.add("Goal: find x")
        for (dch in f.assumedSide) steps.add("$dch is not placed in the question — I assume it lies on " + f.onSide[dch] + " (a point on a side)")
        val famName = when (fam) {
            "A" -> "a triangle with a point on a side"
            "B" -> "two lines crossing at a point"
            "C" -> "rays standing on a straight line"
            else -> "parallel lines cut by a transversal"
        }
        steps.add("Figure type: $famName")
        // pass 1: a figure only to see its structure
        val b1 = build(f, fam, null, variant) ?: return QResult(steps, emptyList(), null, "I could not tell how to draw this. Name the triangle (triangle ABC), the point on a side (D is a point on BC), crossing lines (AB and CD intersect at O) or a straight line (AOB is a straight line).")
        val d1 = b1.d
        val counts = LinkedHashMap<String, Int>()
        val eqs = genEqs(d1, f, counts)
        val label = { k: String -> "∠$k" }
        val pr = Prop(eqs, label)
        pr.run()
        val xv = pr.x
        val known = HashMap<String, Double>()
        for (k in pr.known.keys) pr.value(k)?.let { known[k] = it }
        val lk = { rawKey: String -> known[canon(d1, rawKey)] }

        // thinking: which rules the figure offers
        think.add("Figure: $famName — ${d1.pts.size} points, ${d1.segs.size} drawn segments")
        val ruleNames = listOf(
            "given" to "the facts in the question", "triangle" to "angle sum of a triangle", "isosceles" to "isosceles: equal sides give equal angles",
            "straight" to "angles on a straight line / linear pair", "adjacent" to "adjacent angles add up", "around" to "angles around a point = 360°",
            "vertical" to "vertically opposite angles", "exterior" to "exterior angle theorem", "parallel" to "parallel lines: corresponding / alternate / co-interior",
        )
        for ((key, name) in ruleNames) {
            val n = counts[key] ?: 0
            think.add((if (n > 0) "✓ " else "– ") + name + (if (n > 0) " — $n equation(s) found in this figure" else " — none in this figure"))
        }
        think.add("Unknown angles are solved one at a time from equations with a single unknown; x is found when an equation has only x left")
        if (b1.notes.isNotEmpty()) think.add("Construction needed but not stated: " + b1.notes.joinToString("; "))

        // pass 2: the figure at the solved values
        var b2: Built? = null
        var bad: String? = null
        val checkLog = mutableListOf<String>()
        for (st in 0 until 3) {
            val cand = build(f, fam, lk, variant + st) ?: continue
            checkLog.clear()
            bad = check(cand.d, known, pr, eqs, checkLog)
            if (bad == null) {
                b2 = cand
                break
            }
            steps.add("✗ Check failed: $bad — deleting this figure and drawing it another way")
        }
        val fin = b2 ?: return QResult(steps, emptyList(), null, "The figure I drew did not match the answer I worked out, so I did not place it. $bad", think)
        steps.add("✏️ Drawing the figure…")
        for (n in fin.notes) steps.add("Construction: $n")
        steps.addAll(checkLog)
        steps.add("✅ The figure and the answer agree")
        for ((text, why) in pr.steps) steps.add("$text   ($why)")
        pr.conflict?.let { steps.add("⚠ $it") }

        // answers
        val answers = mutableListOf<String>()
        if (f.wantX) answers.add(if (xv != null) "x = " + qf(xv) else "x cannot be found from these facts")
        for (tok in f.goals.distinct()) {
            val k = tokKey(f, d1, tok)
            if (k == null) {
                answers.add("∠$tok: I could not tell which angle this is")
                continue
            }
            val v = pr.value(k)
            if (v != null) {
                answers.add("∠$tok = " + qf(v) + "°")
            } else {
                answers.add("∠$tok is not fixed by these facts" + (relation(eqs, pr, k, label)?.let { " — in general " + it } ?: ""))
            }
        }
        if (answers.any { it.contains("not fixed") }) answers.add("(Tell me where D is — midpoint, foot of the altitude, bisector, or one more angle — and I will give a number.)")

        val board = mutableListOf<String>()
        board.add("Given:")
        for (g in f.given) board.add("• $g")
        board.add("To find: " + (f.goals.distinct().joinToString(", ") { "∠$it" } + (if (f.wantX) (if (f.goals.isEmpty()) "x" else " and x") else "")))
        if (fin.notes.isNotEmpty()) board.add("Construction: " + fin.notes.joinToString(";  "))
        board.add("Solution:")
        for ((text, why) in pr.steps) board.add("$text    ($why)")
        for (a in answers) board.add("∴ $a")
        val plan = QPlan("", emptyList(), emptySet(), emptySet(), emptySet(), fin.d.title, fin.d.figs)
        return QResult(steps, answers, plan, null, think, board)
    }

    /** Something like ∠ADC = ∠ABD + ∠BAD = 40° + ∠BAD when the answer is not a single number. */
    private fun relation(eqs: List<AEq>, pr: Prop, target: String, label: (String) -> String): String? {
        var best: AEq? = null
        var bestN = 99
        for (e in eqs) {
            if (e.terms.none { it.first == target }) continue
            val unk = e.terms.filter { pr.value(it.first) == null && it.first != target }
            val score = unk.size * 2 + (if (e.why.startsWith("exterior")) 0 else 1)
            if (unk.isNotEmpty() && score < bestN) {
                best = e
                bestN = score
            }
        }
        val e = best ?: return null
        val c = e.terms.first { it.first == target }.second
        val parts = mutableListOf<String>()
        var konst = e.rhs.c
        for ((k, cc) in e.terms) {
            if (k == target) continue
            val v = pr.value(k)
            if (v != null) konst -= cc * v else parts.add((if (cc < 0) "+ " else "− ") + (if (abs(abs(cc) - 1.0) < 1e-12) "" else qf(abs(cc))) + label(k))
        }
        val sb = StringBuilder(label(target) + " = ")
        val numTerms = mutableListOf<String>()
        if (abs(konst) > 1e-9 || parts.isEmpty()) numTerms.add(qf(konst / c) + "°")
        var text = numTerms.joinToString(" ")
        for (p in parts) text += " " + p
        sb.append(text.replace("  ", " ").trim())
        return sb.toString() + "  (" + e.why + ")"
    }

    /** The drone: measure the redrawn figure and compare with every solved angle and every given fact. */
    private fun check(d: Diagram, known: Map<String, Double>, pr: Prop, eqs: List<AEq>, log: MutableList<String>): String? {
        var bad: String? = null
        var shown = 0
        for ((k, v) in known) {
            val kk = canon(d, k)
            val m = measure(d, kk)
            if (abs(m - v) > 0.05) {
                if (bad == null) bad = "∠$k should be ${qf(v)}° but measures ${qf(m)}°"
            } else if (shown < 6) {
                log.add("  ✓ ∠$k = ${qf(v)}° (measured ${qf(m)}°)")
                shown++
            }
        }
        // every equation of the question itself must hold on the figure
        for (e in eqs) {
            if (e.why != "Given" && !e.why.startsWith("Given:")) continue
            var s = 0.0
            var ok = true
            for ((k, c) in e.terms) {
                s += c * measure(d, canon(d, k))
            }
            val rhs = pr.x?.let { e.rhs.k * it + e.rhs.c } ?: e.rhs.c.takeIf { e.rhs.k == 0.0 }
            if (rhs == null) ok = true else if (abs(s - rhs) > 0.05) ok = false
            if (!ok && bad == null) bad = "a given fact does not hold on the figure (" + e.why + ")"
        }
        return bad
    }
}
