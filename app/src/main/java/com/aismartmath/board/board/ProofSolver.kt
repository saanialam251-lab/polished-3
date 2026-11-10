package com.aismartmath.board.board

import com.aismartmath.board.math.angDeg
import com.aismartmath.board.math.dist
import com.aismartmath.board.model.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Triangle proofs without AI. The question is read into Given facts and a goal (To prove / To show). A figure is built
 * that satisfies the Given facts. A small prover then works on that figure: common sides, midpoints, right angles,
 * bisectors, vertically opposite angles, adding / subtracting equal segments, the five congruence rules
 * (SSS, SAS, ASA, AAS, RHS) and CPCT. Every line of the proof is written with its reason in brackets. If the goal is
 * not reachable, extra lines are tried (altitude, median, bisector, diagonal) one at a time. At the end the figure is
 * measured and every Given fact and the conclusion are checked on it.
 */

// ---------------------------------------------------------------- keys and facts

internal fun sk(a: Char, b: Char): String = if (a <= b) "$a$b" else "$b$a"
internal fun sk(s: String): String = sk(s[0], s[1])
internal fun ak(a: Char, v: Char, c: Char): String = if (a <= c) "$a$v$c" else "$c$v$a"
internal fun ak(s: String): String = ak(s[0], s[1], s[2])
internal fun dk(k: String): String = if (k.length == 3) "∠$k" else k

private sealed class G {
    abstract val text: String
}

private class GSeg(val a: String, val b: String, override val text: String) : G()
private class GAng(val a: String, val b: String, override val text: String) : G()
private class GRight(val ang: String, override val text: String) : G()
private class GPerp(val l1: String, val l2: String, override val text: String) : G()
private class GMid(val m: Char, val s: String, override val text: String) : G()
private class GOn(val p: Char, val s: String, override val text: String) : G()
private class GOrder(val pts: String, override val text: String) : G()
private class GBis(val ray: String, val b: Char, val c: Char, override val text: String) : G()
private class GQuad(val names: String, val kind: String, override val text: String) : G()
private class GInter(val s1: String, val s2: String, val o: Char, override val text: String) : G()

private sealed class Goal {
    abstract val text: String
}

private class GoalCong(val t1: String, val t2: String, override val text: String) : Goal()
private class GoalSeg(val a: String, val b: String, override val text: String) : Goal()
private class GoalAng(val a: String, val b: String, override val text: String) : Goal()
private class GoalPerp(val l1: String, val l2: String, override val text: String) : Goal()

private class PF {
    val givens = mutableListOf<G>()
    val goals = mutableListOf<Goal>()
    val tris = mutableListOf<String>()
    var quad: GQuad? = null
    var label = "To prove"
    val letters = mutableSetOf<Char>()
}

// ---------------------------------------------------------------- the figure

internal class Diagram(
    val pts: Map<Char, Pt>,
    val segs: List<String>,
    val rows: List<String>,
    val cross: List<Triple<Char, String, String>>,
    val construction: List<String>,
    val figs: List<QFigSpec>,
    val title: String,
)

internal fun pp(d: Diagram, c: Char): Pt = d.pts[c] ?: Pt(0.0, 0.0)

internal fun onSegInc(p: Pt, a: Pt, b: Pt): Boolean {
    val d = dist(a, b)
    if (d < 1e-9) return false
    val cr = abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) / d
    val dot = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / (d * d)
    return cr < 1e-6 && dot > -1e-9 && dot < 1 + 1e-9
}

internal fun connectedPts(d: Diagram, p: Char, q: Char): Boolean =
    d.segs.any { s -> onSegInc(pp(d, p), pp(d, s[0]), pp(d, s[1])) && onSegInc(pp(d, q), pp(d, s[0]), pp(d, s[1])) }

internal fun trianglesOf(d: Diagram): List<String> {
    val cs = d.pts.keys.sorted()
    val out = mutableListOf<String>()
    for (i in cs.indices) for (j in i + 1 until cs.size) for (k in j + 1 until cs.size) {
        val a = cs[i]
        val b = cs[j]
        val c = cs[k]
        val pa = pp(d, a)
        val pb = pp(d, b)
        val pc = pp(d, c)
        val area = abs((pb.x - pa.x) * (pc.y - pa.y) - (pb.y - pa.y) * (pc.x - pa.x)) / 2
        if (area > 1e-6 && connectedPts(d, a, b) && connectedPts(d, b, c) && connectedPts(d, a, c)) out.add("$a$b$c")
    }
    return out
}

/** What a triangle in the question tells about its shape, by corner index. */
internal class TriCons {
    val sideEq = mutableListOf<IntArray>() // sides opposite corners i and j are equal
    var right = -1
}

private fun consFor(pf: PF, t: String, other: String?): TriCons {
    val c = TriCons()
    fun idxOf(s: String, key: String): Int {
        // the corner opposite the side `key` of triangle s, or -1
        if (key.length != 2 || key.any { it !in s }) return -1
        for (i in 0..2) if (s[i] !in key) return i
        return -1
    }
    for (g in pf.givens) {
        when (g) {
            is GSeg -> {
                for (s in listOfNotNull(t, other)) {
                    val i = idxOf(s, g.a)
                    val j = idxOf(s, g.b)
                    if (i >= 0 && j >= 0 && i != j) c.sideEq.add(intArrayOf(i, j))
                }
            }
            is GAng -> {
                for (s in listOfNotNull(t, other)) {
                    val i = s.indexOf(g.a[1])
                    val j = s.indexOf(g.b[1])
                    if (i >= 0 && j >= 0 && i != j) c.sideEq.add(intArrayOf(i, j))
                }
            }
            is GRight -> {
                for (s in listOfNotNull(t, other)) {
                    val i = s.indexOf(g.ang[1])
                    if (i >= 0 && g.ang.all { it in s }) c.right = i
                }
            }
            else -> {}
        }
    }
    return c
}

internal fun triPts(c: TriCons, style: Int): List<Pt> {
    val sc = 1.0 + 0.12 * (style % 3)
    val joined = IntArray(3) { it }
    fun find(x: Int): Int {
        var r = x
        while (joined[r] != r) r = joined[r]
        return r
    }
    for (p in c.sideEq) {
        val a = find(p[0])
        val b = find(p[1])
        if (a != b) joined[a] = b
    }
    val all = find(0) == find(1) && find(1) == find(2)
    var apex = -1
    if (!all) {
        for (p in c.sideEq) {
            if (p[0] != p[1]) {
                apex = 3 - p[0] - p[1]
                break
            }
        }
    }
    val out = arrayOfNulls<Pt>(3)
    if (all) {
        out[0] = Pt(-2.5 * sc, 0.0)
        out[1] = Pt(2.5 * sc, 0.0)
        out[2] = Pt(0.0, 4.33 * sc)
        // the apex position is arbitrary for an equilateral triangle: corner 2 on top
        return listOf(out[0]!!, out[1]!!, out[2]!!)
    }
    if (c.right >= 0) {
        val v = c.right
        val l1 = 4.0 * sc
        val l2 = (if (apex == v) 4.0 else 3.0) * sc
        out[v] = Pt(0.0, 0.0)
        out[(v + 1) % 3] = Pt(l1, 0.0)
        out[(v + 2) % 3] = Pt(0.0, l2)
        return listOf(out[0]!!, out[1]!!, out[2]!!)
    }
    if (apex >= 0) {
        val i = (apex + 1) % 3
        val j = (apex + 2) % 3
        out[i] = Pt(-2.5 * sc, 0.0)
        out[j] = Pt(2.5 * sc, 0.0)
        out[apex] = Pt(0.0, 3.6 * sc)
        return listOf(out[0]!!, out[1]!!, out[2]!!)
    }
    out[0] = Pt(0.0, 0.0)
    out[1] = Pt(6.0 * sc, 0.0)
    out[2] = Pt((2.2 + 0.4 * (style % 3)) * sc, 3.6 * sc)
    return listOf(out[0]!!, out[1]!!, out[2]!!)
}

private class Extra(val kind: Char, val d: Char, val apex: Char, val a: Char, val b: Char)

internal fun fig(t: String, pts: List<Pt>, nm: String?) = QFigSpec(t, pts, nm)

private fun buildF2(pf: PF, tri: String, extras: List<Extra>, joins0: List<String>, style: Int, construction: List<String>): Diagram? {
    val c = consFor(pf, tri, null)
    // two cevian properties on the same point (median + altitude, altitude + bisector ...) force an isosceles triangle
    for (d in extras.map { it.d }.distinct()) {
        val ks = extras.filter { it.d == d && it.kind != 'o' }.map { it.kind }.distinct()
        if (ks.size >= 2) {
            val e = extras.first { it.d == d }
            val k = tri.indexOf(e.apex)
            if (k >= 0) c.sideEq.add(intArrayOf((k + 1) % 3, (k + 2) % 3))
        }
    }
    val tp = triPts(c, style)
    val pts = mutableMapOf<Char, Pt>()
    for (i in 0..2) pts[tri[i]] = tp[i]
    val segs = mutableListOf(sk(tri[0], tri[1]), sk(tri[1], tri[2]), sk(tri[2], tri[0]))
    val figs = mutableListOf(fig("tri", tp, tri))
    val rowsMap = mutableMapOf<String, MutableList<Pair<Double, Char>>>()
    for (d in extras.map { it.d }.distinct()) {
        if (d in pts) return null
        val ex = extras.filter { it.d == d }
        val e0 = ex.first()
        val x = pts[e0.a] ?: return null
        val y = pts[e0.b] ?: return null
        val ap = pts[e0.apex] ?: return null
        val kinds = ex.map { it.kind }
        val t: Double = when {
            'a' in kinds -> {
                val dx = y.x - x.x
                val dy = y.y - x.y
                ((ap.x - x.x) * dx + (ap.y - x.y) * dy) / (dx * dx + dy * dy)
            }
            'm' in kinds -> 0.5
            'b' in kinds -> dist(ap, x) / (dist(ap, x) + dist(ap, y))
            else -> 0.4
        }
        val dp = Pt(x.x + (y.x - x.x) * t, x.y + (y.y - x.y) * t)
        pts[d] = dp
        val rk = sk(e0.a, e0.b)
        rowsMap.getOrPut(rk) { mutableListOf() }.add(Pair(if (e0.a <= e0.b) t else 1 - t, d))
        segs.add(sk(e0.a, d))
        segs.add(sk(d, e0.b))
        figs.add(fig("seg", listOf(x, dp), "" + e0.a + d))
        figs.add(fig("seg", listOf(dp, y), "" + d + e0.b))
        if (kinds.any { it != 'o' } || joins0.contains(sk(e0.apex, d))) {
            segs.add(sk(e0.apex, d))
            figs.add(fig("seg", listOf(ap, dp), "" + e0.apex + d))
        }
    }
    val rows = rowsMap.map { (k, v) -> "" + k[0] + v.sortedBy { it.first }.joinToString("") { it.second.toString() } + k[1] }
    for (j in joins0) {
        if (j[0] !in pts || j[1] !in pts) return null
        if (j in segs) continue
        segs.add(j)
        figs.add(fig("seg", listOf(pts[j[0]]!!, pts[j[1]]!!), j))
    }
    return Diagram(pts, segs, rows, emptyList(), construction, figs, "△$tri")
}

private fun buildF3(pf: PF, names: String, kind0: String, diags: List<String>, joins0: List<String>, style: Int, construction: List<String>): Diagram? {
    if (names.length != 4 || names.toSet().size != 4) return null
    var kind = kind0
    if (kind == "quadrilateral") {
        val keys = pf.givens.filterIsInstance<GSeg>().flatMap { listOf(sk(it.a) to sk(it.b)) }
        fun has(a: String, b: String) = keys.any { (it.first == sk(a) && it.second == sk(b)) || (it.first == sk(b) && it.second == sk(a)) }
        val n = names
        kind = if (has("" + n[0] + n[1], "" + n[2] + n[3]) || has("" + n[1] + n[2], "" + n[3] + n[0])) "parallelogram"
        else if (has("" + n[0] + n[1], "" + n[0] + n[3]) && has("" + n[2] + n[1], "" + n[2] + n[3])) "kite"
        else "parallelogram"
    }
    val s = 1.0 + 0.1 * (style % 3)
    val q: List<Pt> = when (kind) {
        "rectangle" -> listOf(Pt(0.0, 0.0), Pt(6.0 * s, 0.0), Pt(6.0 * s, 4.0), Pt(0.0, 4.0))
        "square" -> listOf(Pt(0.0, 0.0), Pt(5.0 * s, 0.0), Pt(5.0 * s, 5.0 * s), Pt(0.0, 5.0 * s))
        "rhombus" -> listOf(Pt(0.0, 0.0), Pt(5.0 * s, 0.0), Pt(7.5 * s, 4.33 * s), Pt(2.5 * s, 4.33 * s))
        "kite" -> listOf(Pt(0.0, 4.0 * s), Pt(-2.5, 0.5), Pt(0.0, -2.0), Pt(2.5, 0.5))
        else -> listOf(Pt(0.0, 0.0), Pt(5.0 * s, 0.0), Pt(5.0 * s + 1.5, 3.5), Pt(1.5, 3.5))
    }
    val pts = mutableMapOf<Char, Pt>()
    for (i in 0..3) pts[names[i]] = q[i]
    val segs = mutableListOf<String>()
    for (i in 0..3) segs.add(sk(names[i], names[(i + 1) % 4]))
    val figs = mutableListOf(fig("quad", q, names))
    for (e in diags + joins0) {
        if (e[0] !in pts || e[1] !in pts) return null
        if (e in segs) continue
        segs.add(e)
        figs.add(fig("seg", listOf(pts[e[0]]!!, pts[e[1]]!!), e))
    }
    return Diagram(pts, segs, emptyList(), emptyList(), construction, figs, "quadrilateral $names")
}

private fun buildF4(pf: PF, inter: GInter, joins0: List<String>, style: Int, construction: List<String>): Diagram? {
    val o = inter.o
    val a = inter.s1[0]
    val d = inter.s1[1]
    val b = inter.s2[0]
    val c = inter.s2[1]
    if (setOf(o, a, d, b, c).size != 5) return null
    // lengths: equal when the question says so (midpoint, OA = OB ...), different otherwise
    val ids = mutableMapOf(a to 0, d to 1, b to 2, c to 3)
    fun merge(x: Int, y: Int) {
        for (k in ids.keys.toList()) if (ids[k] == y) ids[k] = x
    }
    for (g in pf.givens) {
        if (g is GMid && g.m == o) {
            val p = g.s[0]
            val q = g.s[1]
            if (p in ids && q in ids) merge(ids[p]!!, ids[q]!!)
        }
        if (g is GSeg && g.a.length == 2 && g.b.length == 2 && g.a.contains(o) && g.b.contains(o)) {
            val p = g.a.first { it != o }
            val q = g.b.first { it != o }
            if (p in ids && q in ids) merge(ids[p]!!, ids[q]!!)
        }
    }
    val base = listOf(3.0, 2.2, 2.7, 1.8)
    val order = ids.values.distinct()
    fun len(ch: Char): Double = base[order.indexOf(ids[ch]!!) % base.size] * (1.0 + 0.1 * (style % 3))
    val ang = (35.0 + 8.0 * (style % 3)) * PI / 180
    val cx = cos(ang)
    val sy = sin(ang)
    val pts = mutableMapOf<Char, Pt>()
    pts[o] = Pt(0.0, 0.0)
    pts[a] = Pt(-cx * len(a), sy * len(a))
    pts[d] = Pt(cx * len(d), -sy * len(d))
    pts[b] = Pt(-cx * len(b), -sy * len(b))
    pts[c] = Pt(cx * len(c), sy * len(c))
    val segs = mutableListOf(sk(a, o), sk(o, d), sk(b, o), sk(o, c))
    val figs = mutableListOf<QFigSpec>()
    for (s in segs) figs.add(fig("seg", listOf(pts[s[0]]!!, pts[s[1]]!!), s))
    for (j in joins0) {
        if (j[0] !in pts || j[1] !in pts) return null
        if (j in segs) continue
        segs.add(j)
        figs.add(fig("seg", listOf(pts[j[0]]!!, pts[j[1]]!!), j))
    }
    return Diagram(pts, segs, listOf("" + a + o + d, "" + b + o + c), listOf(Triple(o, inter.s1, inter.s2)), construction, figs, "lines $a$d and $b$c crossing at $o")
}

private fun buildF5(pf: PF, row: String, style: Int): Diagram? {
    if (row.length < 3 || row.toSet().size != row.length) return null
    val n = row.length - 1
    val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        return r
    }
    for (g in pf.givens) {
        if (g is GSeg && g.a.length == 2 && g.b.length == 2 && g.a.all { it in row } && g.b.all { it in row }) {
            val i = min(row.indexOf(g.a[0]), row.indexOf(g.a[1]))
            val j = min(row.indexOf(g.b[0]), row.indexOf(g.b[1]))
            val ai = abs(row.indexOf(g.a[0]) - row.indexOf(g.a[1]))
            val bi = abs(row.indexOf(g.b[0]) - row.indexOf(g.b[1]))
            if (ai == 1 && bi == 1) parent[find(i)] = find(j)
        }
    }
    val base = listOf(2.2, 1.5, 1.9, 1.2, 1.7)
    val classes = (0 until n).map { find(it) }.distinct()
    val xs = DoubleArray(n + 1)
    for (i in 0 until n) xs[i + 1] = xs[i] + base[classes.indexOf(find(i)) % base.size] * (1.0 + 0.08 * (style % 3))
    val pts = mutableMapOf<Char, Pt>()
    for (i in 0..n) pts[row[i]] = Pt(xs[i], 0.0)
    val segs = (0 until n).map { sk(row[it], row[it + 1]) }.toMutableList()
    val figs = (0 until n).map { fig("seg", listOf(pts[row[it]]!!, pts[row[it + 1]]!!), "" + row[it] + row[it + 1]) }
    return Diagram(pts, segs, listOf(row), emptyList(), emptyList(), figs, "points $row on a line")
}

private fun buildF1(pf: PF, t1: String, t2: String, style: Int): Diagram? {
    if (t1.length != 3 || t2.length != 3 || (t1.toSet() + t2.toSet()).size != 6) return null
    val c = consFor(pf, t1, t2)
    val tp = triPts(c, style)
    val cx = tp.map { it.x }.average()
    val cy = tp.map { it.y }.average()
    val w = (tp.maxOf { it.x } - tp.minOf { it.x }) + 2.5
    val rot = (22.0 + 6.0 * (style % 3)) * PI / 180
    val second = tp.map {
        val x = it.x - cx
        val y = it.y - cy
        Pt(cx + w + x * cos(rot) - y * sin(rot), cy + x * sin(rot) + y * cos(rot))
    }
    val pts = mutableMapOf<Char, Pt>()
    for (i in 0..2) {
        pts[t1[i]] = tp[i]
        pts[t2[i]] = second[i]
    }
    val segs = listOf(sk(t1[0], t1[1]), sk(t1[1], t1[2]), sk(t1[2], t1[0]), sk(t2[0], t2[1]), sk(t2[1], t2[2]), sk(t2[2], t2[0]))
    return Diagram(pts, segs, emptyList(), emptyList(), emptyList(), listOf(fig("tri", tp, t1), fig("tri", second, t2)), "△$t1 and △$t2")
}

// ---------------------------------------------------------------- the prover

private class Cong(val t1: String, val t2: String, val rule: String, val pairs: List<CP>, val limit: Int) {
    var step = -1
}

private class CP(val u: String, val v: String, val disp: String)

private class AddInfo(
    val s1u: String, val s1v: String,
    val s2u: String?, val s2v: String?,
    val common: String?,
    val sum: String, val why: String, val verb: String,
)

private class Edge(
    val u: String, val v: String, val reason: String, var step: Int,
    val kind: Int, // 0 plain, 1 CPCT, 2 adding / subtracting
    val stamp: Int, val cong: Cong?, val info: AddInfo?,
) {
    fun other(x: String) = if (u == x) v else u
}

private class Eng(val d: Diagram, val pf: PF, val allowIso: Boolean, val built: List<Extra>) {
    val adj = HashMap<String, MutableList<Edge>>()
    val edges = mutableListOf<Edge>()
    val rights = mutableListOf<Pair<String, String>>()
    val congs = mutableListOf<Cong>()
    val congSigs = mutableSetOf<String>()
    val out = mutableListOf<String>()
    var n = 0
    var stamp = 0
    val tris = trianglesOf(d)
    var pairsChecked = 0

    /** The same angle can be named by different points on its arms (∠ABD = ∠ABC when D is on BC): use the smallest letter on each arm. */
    fun nk(k: String): String {
        if (k.length != 3) return k
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

    fun add(u0: String, v0: String, reason: String, kind: Int = 0, cong: Cong? = null, info: AddInfo? = null) {
        val u = nk(u0)
        val v = nk(v0)
        if (u == v) return
        stamp++
        val e = Edge(u, v, reason, -1, kind, stamp, cong, info)
        edges.add(e)
        adj.getOrPut(u) { mutableListOf() }.add(e)
        adj.getOrPut(v) { mutableListOf() }.add(e)
    }

    fun addRight(k0: String, reason: String) {
        val k = nk(k0)
        if (rights.any { it.first == k }) return
        for ((k2, r2) in rights.toList()) add(k, k2, "each is 90°: " + (if (reason == r2) reason else "$reason; $r2"))
        rights.add(Pair(k, reason))
    }

    fun path(u0: String, v0: String, limit: Int = Int.MAX_VALUE): List<Edge>? {
        val u = nk(u0)
        val v = nk(v0)
        if (u == v) return emptyList()
        val prev = HashMap<String, Edge>()
        val seen = HashSet<String>()
        seen.add(u)
        val q = ArrayDeque<String>()
        q.add(u)
        while (q.isNotEmpty()) {
            val x = q.removeFirst()
            for (e in adj[x] ?: emptyList<Edge>()) {
                if (e.stamp > limit) continue
                val y = e.other(x)
                if (y in seen) continue
                seen.add(y)
                prev[y] = e
                if (y == v) {
                    val res = mutableListOf<Edge>()
                    var cur = v
                    while (cur != u) {
                        val pe = prev[cur] ?: return null
                        res.add(0, pe)
                        cur = pe.other(cur)
                    }
                    return res
                }
                q.add(y)
            }
        }
        return null
    }

    fun eq(u: String, v: String, limit: Int = Int.MAX_VALUE): Boolean = nk(u) == nk(v) || path(u, v, limit) != null

    // ---- writing the proof
    fun emit(text: String, reason: String): Int {
        n++
        out.add("$n. $text    ($reason)")
        return n
    }

    fun ready(e: Edge): String {
        if (e.kind == 0) return if (e.step >= 0) "proved above, step ${e.step}" else e.reason
        if (e.step < 0) {
            if (e.kind == 1) {
                val c = e.cong!!
                emitCong(c)
                e.step = emit(dk(e.u) + " = " + dk(e.v), "CPCT: corresponding parts of Δ${c.t1} ≅ Δ${c.t2}, step ${c.step}")
            } else {
                val a = e.info!!
                val s1 = prove(a.s1u, a.s1v, dk(a.s1u) + " = " + dk(a.s1v), Int.MAX_VALUE)
                val s2 = if (a.s2u != null && a.s2v != null) prove(a.s2u, a.s2v, dk(a.s2u) + " = " + dk(a.s2v), Int.MAX_VALUE)
                else emit((a.common ?: "") .let { dk(it) + " = " + dk(it) }, "common segment")
                emit(a.sum, a.verb + " steps $s1 and $s2")
                e.step = emit(dk(e.u) + " = " + dk(e.v), a.why)
            }
        }
        return "proved above, step ${e.step}"
    }

    /** Writes a line saying u = v (shown as disp) with the right reason; returns its step number. */
    fun prove(u0: String, v0: String, disp: String, limit: Int): Int {
        val u = nk(u0)
        val v = nk(v0)
        if (u == v) return emit(disp, if (u.length == 2) "common side" else "common angle")
        val p = path(u, v, limit) ?: return -1
        val reasons = p.map { ready(it) }
        if (p.size == 1) return emit(disp, reasons[0])
        val why = "from " + p.indices.joinToString(", ") { dk(p[it].u) + " = " + dk(p[it].v) + " (" + reasons[it] + ")" }
        val s = emit(disp, why)
        stamp++
        val e = Edge(u, v, why, s, 0, stamp, null, null)
        edges.add(e)
        adj.getOrPut(u) { mutableListOf() }.add(e)
        adj.getOrPut(v) { mutableListOf() }.add(e)
        return s
    }

    fun emitCong(c: Cong) {
        if (c.step >= 0) return
        for (p in c.pairs) if (nk(p.u) != nk(p.v)) path(p.u, p.v, c.limit)?.forEach { ready(it) }
        out.add("In Δ${c.t1} and Δ${c.t2},")
        for (p in c.pairs) prove(p.u, p.v, p.disp, c.limit)
        c.step = emit("Δ${c.t1} ≅ Δ${c.t2}", c.rule)
    }

    // ---- facts from the question
    fun setup() {
        for (g in pf.givens) {
            when (g) {
                is GSeg -> add(sk(g.a), sk(g.b), "Given")
                is GAng -> add(ak(g.a), ak(g.b), "Given")
                is GRight -> addRight(ak(g.ang), "Given")
                is GMid -> add(sk(g.s[0], g.m), sk(g.m, g.s[1]), "${g.m} is the midpoint of ${g.s}")
                is GBis -> add(ak(g.b, g.ray[0], g.ray[1]), ak(g.ray[1], g.ray[0], g.c), "${g.ray} bisects ∠${g.b}${g.ray[0]}${g.c}")
                is GPerp -> {
                    val f = foot(g.l1, g.l2)
                    if (f != null) {
                        val xs = g.l1.filter { it != f }
                        val ys = g.l2.filter { it != f }
                        for (x in xs) for (y in ys) addRight(ak(x, f, y), "${g.l1} ⊥ ${g.l2}")
                    }
                }
                is GQuad -> quadProps(g)
                else -> {}
            }
        }
        for (e in built) {
            when (e.kind) {
                'a' -> {
                    val why = "" + e.apex + e.d + " ⊥ " + e.a + e.b + ", drawn as a construction"
                    addRight(ak(e.apex, e.d, e.a), why)
                    addRight(ak(e.apex, e.d, e.b), why)
                }
                'm' -> add(sk(e.a, e.d), sk(e.d, e.b), "" + e.d + " is the midpoint of " + e.a + e.b + " (construction)")
                'b' -> add(ak(e.a, e.apex, e.d), ak(e.d, e.apex, e.b), "" + e.apex + e.d + " bisects ∠" + e.a + e.apex + e.b + " (construction)")
                else -> {}
            }
        }
        for ((o, s1, s2) in d.cross) {
            val p1 = s1[0]
            val p2 = s1[1]
            val q1 = s2[0]
            val q2 = s2[1]
            add(ak(p1, o, q1), ak(p2, o, q2), "vertically opposite angles")
            add(ak(p1, o, q2), ak(p2, o, q1), "vertically opposite angles")
        }
    }

    fun foot(l1: String, l2: String): Char? {
        for (c in l1) if (c in d.pts && onSegInc(pp(d, c), pp(d, l2[0]), pp(d, l2[1]))) return c
        for (c in l2) if (c in d.pts && onSegInc(pp(d, c), pp(d, l1[0]), pp(d, l1[1]))) return c
        return null
    }

    fun quadProps(g: GQuad) {
        val q = g.names
        if (q.length != 4) return
        fun side(i: Int) = sk(q[i], q[(i + 1) % 4])
        when (g.kind) {
            "parallelogram" -> {
                add(side(0), side(2), "opposite sides of a parallelogram are equal")
                add(side(1), side(3), "opposite sides of a parallelogram are equal")
            }
            "rhombus" -> for (i in 1..3) add(side(0), side(i), "all sides of a rhombus are equal")
            "square" -> {
                for (i in 1..3) add(side(0), side(i), "all sides of a square are equal")
                for (i in 0..3) addRight(ak(q[(i + 3) % 4], q[i], q[(i + 1) % 4]), "each angle of a square is 90°")
            }
            "rectangle" -> {
                add(side(0), side(2), "opposite sides of a rectangle are equal")
                add(side(1), side(3), "opposite sides of a rectangle are equal")
                for (i in 0..3) addRight(ak(q[(i + 3) % 4], q[i], q[(i + 1) % 4]), "each angle of a rectangle is 90°")
            }
            "kite" -> {
                add(side(0), side(3), "a kite has two pairs of equal adjacent sides")
                add(side(1), side(2), "a kite has two pairs of equal adjacent sides")
            }
        }
    }

    // ---- positions along a straight row of points
    fun iv(row: String, key: String): IntArray? {
        if (key.length != 2) return null
        val i = row.indexOf(key[0])
        val j = row.indexOf(key[1])
        if (i < 0 || j < 0 || i == j) return null
        return intArrayOf(min(i, j), max(i, j))
    }

    fun keyAt(row: String, a: Int, b: Int) = sk(row[a], row[b])

    fun segArithmetic(): Boolean {
        var any = false
        val fresh = mutableListOf<Triple<String, String, AddInfo>>()
        for (row in d.rows) {
            val eqs = edges.filter { it.u.length == 2 && iv(row, it.u) != null && iv(row, it.v) != null }.map { Pair(it.u, it.v) }
            for ((u, v) in eqs) for (sw in 0..1) {
                val x = if (sw == 0) u else v
                val y = if (sw == 0) v else u
                val a = iv(row, x) ?: continue
                val b = iv(row, y) ?: continue
                if (a[1] < b[0]) { // adding the piece between them to both
                    val cKey = keyAt(row, a[1], b[0])
                    val ru = keyAt(row, a[0], b[0])
                    val rv = keyAt(row, a[1], b[1])
                    fresh.add(Triple(ru, rv, AddInfo(x, y, null, null, cKey, "$x + $cKey = $y + $cKey", "$x + $cKey = $ru and $y + $cKey = $rv, the whole is the sum of its parts", "adding")))
                }
                if (a[0] < b[0] && b[0] < a[1] && a[1] < b[1]) { // taking the shared piece away from both
                    val cKey = keyAt(row, b[0], a[1])
                    val ru = keyAt(row, a[0], b[0])
                    val rv = keyAt(row, a[1], b[1])
                    fresh.add(Triple(ru, rv, AddInfo(x, y, null, null, cKey, "$x − $cKey = $y − $cKey", "$x − $cKey = $ru and $y − $cKey = $rv, the whole minus a part", "subtracting")))
                }
            }
            for (e1 in eqs) for (e2 in eqs) {
                if (e1 == e2) continue
                for (s1 in 0..1) for (s2 in 0..1) {
                    val x1 = if (s1 == 0) e1.first else e1.second
                    val y1 = if (s1 == 0) e1.second else e1.first
                    val x2 = if (s2 == 0) e2.first else e2.second
                    val y2 = if (s2 == 0) e2.second else e2.first
                    val a1 = iv(row, x1) ?: continue
                    val a2 = iv(row, x2) ?: continue
                    val b1 = iv(row, y1) ?: continue
                    val b2 = iv(row, y2) ?: continue
                    if (a1[1] == a2[0] && b1[1] == b2[0]) {
                        val ru = keyAt(row, a1[0], a2[1])
                        val rv = keyAt(row, b1[0], b2[1])
                        fresh.add(Triple(ru, rv, AddInfo(e1.first, e1.second, e2.first, e2.second, null, "$x1 + $x2 = $y1 + $y2", "$x1 + $x2 = $ru and $y1 + $y2 = $rv, the whole is the sum of its parts", "adding")))
                    }
                }
            }
        }
        for ((u, v, info) in fresh) {
            if (u != v && !eq(u, v)) {
                add(u, v, "adding", 2, null, info)
                any = true
            }
        }
        return any
    }

    fun isoRules(): Boolean {
        var any = false
        for (t in tris) for (r in 0..2) {
            val p = t[r]
            val x = t[(r + 1) % 3]
            val y = t[(r + 2) % 3]
            val s1 = sk(p, x)
            val s2 = sk(p, y)
            val a1 = ak(p, x, y)
            val a2 = ak(p, y, x)
            if (a1 != a2 && eq(s1, s2) && !eq(a1, a2)) {
                add(a1, a2, "angles opposite equal sides are equal ($s1 = $s2)")
                any = true
            }
            if (a1 != a2 && eq(a1, a2) && !eq(s1, s2)) {
                add(s1, s2, "sides opposite equal angles are equal (${dk(a1)} = ${dk(a2)})")
                any = true
            }
        }
        return any
    }

    // ---- congruence of two triangles written in corresponding order
    fun detect(t1: String, t2: String, lim: Int = stamp): Cong? {
        fun sKey(t: String, k: Int) = sk(t[k], t[(k + 1) % 3])
        fun aKey(t: String, k: Int) = nk(ak(t[(k + 1) % 3], t[k], t[(k + 2) % 3]))
        fun sP(k: Int) = CP(sKey(t1, k), sKey(t2, k), "" + t1[k] + t1[(k + 1) % 3] + " = " + t2[k] + t2[(k + 1) % 3])
        fun aP(k: Int) = CP(aKey(t1, k), aKey(t2, k), "∠" + t1[(k + 1) % 3] + t1[k] + t1[(k + 2) % 3] + " = ∠" + t2[(k + 1) % 3] + t2[k] + t2[(k + 2) % 3])
        fun ok(p: CP) = eq(p.u, p.v, lim)
        pairsChecked++
        if ((0..2).all { ok(sP(it)) }) return Cong(t1, t2, "SSS congruence rule", listOf(sP(0), sP(1), sP(2)), lim)
        for (v in 0..2) {
            val s1 = sP(v)
            val s2 = sP((v + 2) % 3)
            if (ok(s1) && ok(aP(v)) && ok(s2)) return Cong(t1, t2, "SAS congruence rule", listOf(s1, aP(v), s2), lim)
        }
        for (k in 0..2) {
            val j = (k + 1) % 3
            if (ok(aP(k)) && ok(sP(k)) && ok(aP(j))) return Cong(t1, t2, "ASA congruence rule", listOf(aP(k), sP(k), aP(j)), lim)
        }
        for (k in 0..2) {
            val j = (k + 1) % 3
            if (ok(aP(k)) && ok(aP(j))) {
                for (sd in listOf((k + 1) % 3, (k + 2) % 3)) {
                    if (ok(sP(sd))) return Cong(t1, t2, "AAS congruence rule", listOf(aP(k), aP(j), sP(sd)), lim)
                }
            }
        }
        for (v in 0..2) {
            val r1 = rights.any { it.first == aKey(t1, v) }
            val r2 = rights.any { it.first == aKey(t2, v) }
            if (r1 && r2) {
                val hyp = sP((v + 1) % 3)
                if (ok(hyp)) {
                    for (leg in listOf(v, (v + 2) % 3)) {
                        val lp = sP(leg)
                        if (ok(lp)) {
                            val ra = CP(aKey(t1, v), aKey(t2, v), "∠" + t1[(v + 1) % 3] + t1[v] + t1[(v + 2) % 3] + " = ∠" + t2[(v + 1) % 3] + t2[v] + t2[(v + 2) % 3] + " = 90°")
                            return Cong(t1, t2, "RHS congruence rule (right angle, hypotenuse, side)", listOf(ra, hyp, lp), lim)
                        }
                    }
                }
            }
        }
        return null
    }

    fun sigOf(t1: String, t2: String): String = (0..2).map { listOf(t1[it], t2[it]).sorted().joinToString("") }.sorted().joinToString(",")

    fun sigOf(c: Cong): String = sigOf(c.t1, c.t2)

    fun register(c: Cong) {
        if (!congSigs.add(sigOf(c))) return
        congs.add(c)
        for (k in 0..2) {
            val s1 = sk(c.t1[k], c.t1[(k + 1) % 3])
            val s2 = sk(c.t2[k], c.t2[(k + 1) % 3])
            if (!eq(s1, s2)) add(s1, s2, "CPCT", 1, c)
            val a1 = ak(c.t1[(k + 1) % 3], c.t1[k], c.t1[(k + 2) % 3])
            val a2 = ak(c.t2[(k + 1) % 3], c.t2[k], c.t2[(k + 2) % 3])
            if (!eq(a1, a2)) add(a1, a2, "CPCT", 1, c)
        }
    }

    val perms = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))

    fun findCongruences(): Boolean {
        var any = false
        for (i in tris.indices) for (j in i + 1 until tris.size) {
            for (p in perms) {
                val t2 = "" + tris[j][p[0]] + tris[j][p[1]] + tris[j][p[2]]
                val c = detect(tris[i], t2) ?: continue
                val before = congSigs.size
                register(c)
                if (congSigs.size > before) any = true
            }
        }
        return any
    }

    fun goalReached(g: Goal): Boolean = when (g) {
        is GoalCong -> {
            val c = detect(g.t1, g.t2)
            if (c != null) register(c)
            c != null
        }
        is GoalSeg -> eq(sk(g.a), sk(g.b))
        is GoalAng -> eq(ak(g.a), ak(g.b))
        is GoalPerp -> {
            val f = foot(g.l1, g.l2)
            if (f == null) false else {
                val x = g.l1.first { it != f }
                val ys = g.l2.filter { it != f }
                ys.length == 2 && (rights.any { it.first == nk(ak(x, f, ys[0])) } || eq(ak(x, f, ys[0]), ak(x, f, ys[1])))
            }
        }
    }

    /** Builds facts until every goal is reached or nothing new appears. */
    fun closure(): Boolean {
        setup()
        for (round in 0 until 8) {
            var changed = segArithmetic()
            if (allowIso && isoRules()) changed = true
            if (findCongruences()) changed = true
            if (pf.goals.all { goalReached(it) }) return true
            if (!changed) break
        }
        return pf.goals.all { goalReached(it) }
    }

    /** Writes the whole proof. */
    fun write(): Boolean {
        for (g in pf.goals) {
            when (g) {
                is GoalCong -> {
                    val sg = sigOf(g.t1, g.t2)
                    val reg = congs.firstOrNull { sigOf(it) == sg }
                    if (reg != null && reg.step >= 0) {
                        out.add("∴ ${g.text}    (proved in step ${reg.step})")
                    } else if (reg != null) {
                        // the same proof, written with the triangles in the order of the question
                        val c3 = detect(g.t1, g.t2, reg.limit) ?: reg
                        emitCong(c3)
                        reg.step = c3.step
                    } else {
                        val c = detect(g.t1, g.t2) ?: return false
                        emitCong(c)
                    }
                }
                is GoalSeg -> if (!concludeEq(sk(g.a), sk(g.b), g.text)) return false
                is GoalAng -> if (!concludeEq(ak(g.a), ak(g.b), g.text)) return false
                is GoalPerp -> {
                    val f = foot(g.l1, g.l2) ?: return false
                    val x = g.l1.first { it != f }
                    val ys = g.l2.filter { it != f }
                    if (ys.length != 2) return false
                    val a1 = ak(x, f, ys[0])
                    val a2 = ak(x, f, ys[1])
                    if (rights.any { it.first == nk(a1) }) {
                        out.add("∴ ${g.text}    (given)")
                    } else {
                        if (!concludeEq(a1, a2, "∠${a1} = ∠${a2}")) return false
                        val s1 = emit("∠${a1} + ∠${a2} = 180°", "linear pair: ${ys[0]}, $f, ${ys[1]} lie on a straight line")
                        emit("2 × ∠${a1} = 180°, so ∠${a1} = 90°", "from step ${s1} and the equal angles")
                        out.add("∴ ${g.text}")
                    }
                }
            }
        }
        return true
    }

    fun concludeEq(u0: String, v0: String, text: String): Boolean {
        val u = nk(u0)
        val v = nk(v0)
        if (u == v) {
            out.add("∴ $text    (same)")
            return true
        }
        val p = path(u, v) ?: return false
        val reasons = p.map { ready(it) }
        if (p.size == 1) {
            out.add("∴ $text    (${reasons[0]})")
        } else {
            val why = "from " + p.indices.joinToString(", ") { dk(p[it].u) + " = " + dk(p[it].v) + " (" + reasons[it] + ")" }
            val s = emit(text, why)
            stamp++
            val e = Edge(u, v, why, s, 0, stamp, null, null)
            edges.add(e)
            adj.getOrPut(u) { mutableListOf() }.add(e)
            adj.getOrPut(v) { mutableListOf() }.add(e)
        }
        return true
    }
}

// ---------------------------------------------------------------- reading the question

internal object ProofSolver {
    private val shapeRx = Regex("(?i:triangle)\\s+([A-Z]{3})(?![A-Za-z])")
    private val isTriRx = Regex("\\b([A-Z]{3})\\s+(?i:is)\\s+(?:(?i:an?)\\s+)?(?:(?i:isosceles|equilateral|right|scalene|right-angled)\\s+)?(?i:triangle)")
    private val congRx = Regex("(?i:triangle)\\s+([A-Z]{3})\\s*(?:≅|(?i:is\\s+congruent\\s+to|congruent\\s+to))\\s*(?:(?i:triangle)\\s+)?([A-Z]{3})")
    private val congRx2 = Regex("(?i:triangles?)\\s+([A-Z]{3})\\s+(?i:and)\\s+([A-Z]{3})\\s+(?i:are\\s+congruent)")
    private val chainRx = Regex("\\b[A-Z]{2}\\b(?:\\s*=\\s*\\b[A-Z]{2}\\b)+")
    private val angEqRx = Regex("∠\\s*([A-Z]{1,3})\\s*=\\s*∠\\s*([A-Z]{1,3})")
    private val rightRx = Regex("∠\\s*([A-Z]{1,3})\\s*=\\s*90")
    private val rightAtRx = Regex("(?i:right[- ]?angle[d]?)\\s*(?:(?i:at|in)\\s*)(?:(?i:vertex)\\s*)?([A-Z])\\b")
    private val perpRx = Regex("\\b([A-Z]{2})\\s*(?:⊥|(?i:perpendicular\\s+to|perp\\s+to))\\s*([A-Z]{2})\\b")
    private val midRx = Regex("\\b([A-Z])\\s+(?i:is)\\s+(?:(?i:the)\\s+)?(?i:mid-?point)\\s+(?i:of)\\s+(?:(?i:both)\\s+)?(?:(?i:side|segment|line)\\s+)?([A-Z]{2})(?:\\s*(?:(?i:and)|,)\\s*(?:(?i:side|segment|line)\\s+)?([A-Z]{2}))?")
    private val bisectEachRx = Regex("\\b([A-Z]{2})\\s+(?i:and)\\s+([A-Z]{2})\\s+(?i:bisect)\\s+(?i:each\\s+other)\\s+(?i:at)\\s+([A-Z])\\b")
    private val interRx = Regex("\\b([A-Z]{2})\\s+(?i:and)\\s+([A-Z]{2})\\s+(?i:intersect|cut)(?:\\s+(?i:each\\s+other))?\\s+(?i:at)\\s+([A-Z])\\b")
    private val onRx = Regex("\\b([A-Z])\\s+(?i:is\\s+a\\s+point\\s+on|lies\\s+on|is\\s+on)\\s+(?:(?i:the)\\s+)?(?:(?i:side|segment|line)\\s+)?([A-Z]{2})\\b")
    private val onTwoRx = Regex("(?i:points?)\\s+([A-Z])\\s+(?:(?i:and)|,)\\s+([A-Z])\\s+(?i:lie|are)\\s+(?i:on)\\s+(?:(?i:the)\\s+)?(?:(?i:line|segment)\\s+)?([A-Z]{2})\\b")
    private val rowRx = Regex("(?i:points?)\\s+([A-Z](?:\\s*,\\s*[A-Z]|\\s+(?i:and)\\s+[A-Z]){2,5})\\s+(?i:lie|are)\\s+(?:(?i:on\\s+a\\s+(?:straight\\s+)?line)|(?i:collinear))")
    private val bisRx = Regex("\\b([A-Z]{2})\\s+(?:(?i:bisects)|(?i:is\\s+the\\s+(?:angle\\s+)?bisector\\s+of))\\s+(?:(?i:the)\\s+)?∠\\s*([A-Z]{1,3})")
    private val quadRx1 = Regex("\\b([A-Z]{4})\\s+(?i:is)\\s+(?:(?i:an?)\\s+)?((?i:quadrilateral|parallelogram|rhombus|rectangle|square|kite))")
    private val quadRx2 = Regex("((?i:parallelogram|rhombus|rectangle|square|kite|quadrilateral))\\s+([A-Z]{4})\\b")
    private val altRx1 = Regex("(?i:altitude|median)\\s+([A-Z]{2})\\b")
    private val altRx2 = Regex("\\b([A-Z]{2})\\s+(?i:is)\\s+(?:(?i:the)\\s+|(?i:an?)\\s+)?(?i:altitude|median)")

    fun looksProof(raw: String): Boolean {
        val l = raw.lowercase()
        val proofWord = Regex("\\bprove\\b|show that|to prove|to show|congruent|≅|≌").containsMatchIn(l)
        val tri = l.contains("triangle") || l.contains("△") || l.contains("Δ") || l.contains("∆")
        val shape = tri || l.contains("quadrilateral") || l.contains("parallelogram") || l.contains("rhombus") || l.contains("rectangle") || l.contains("square") || Regex("\\b[A-Z]{2}\\s*=\\s*[A-Z]{2}\\b").containsMatchIn(raw)
        if (!proofWord || !shape) return false
        if (TrigSolver.looksTrig(raw) && !tri) return false
        return true
    }

    private fun parse(raw: String, pf: PF): Boolean {
        var t = raw.replace("△", " triangle ").replace("Δ", " triangle ").replace("∆", " triangle ").replace("▲", " triangle ")
        t = t.replace("≌", "≅").replace("−", "-").replace("\n", " ").replace("∥", "||")
        t = Regex("(?i)\\bangle\\s+(?=[A-Z])").replace(t, "∠")
        for (ch in t) if (ch in 'A'..'Z') pf.letters.add(ch)
        val kw = Regex("(?i)\\b(?:to\\s+prove(?:\\s+that)?|to\\s+show(?:\\s+that)?|prove\\s+that|show\\s+that|prove|show|verify)\\b").find(t)
        val gp: String
        val qp: String
        if (kw != null) {
            gp = t.substring(0, kw.range.first)
            qp = t.substring(kw.range.last + 1)
            pf.label = if (kw.value.lowercase().contains("show")) "To show" else "To prove"
        } else {
            val m = congRx.find(t) ?: congRx2.find(t) ?: return false
            gp = t.removeRange(m.range)
            qp = m.value
        }
        val givenText = Regex("(?i)\\bgiven(?:\\s+that)?\\s*:?").replace(gp, " ")
        for (m in shapeRx.findAll(t)) if (m.groupValues[1] !in pf.tris) pf.tris.add(m.groupValues[1])
        for (m in isTriRx.findAll(t)) if (m.groupValues[1] !in pf.tris) pf.tris.add(m.groupValues[1])
        for (m in quadRx1.findAll(givenText)) pf.quad = GQuad(m.groupValues[1], m.groupValues[2].lowercase(), m.groupValues[1] + " is a " + m.groupValues[2].lowercase())
        if (pf.quad == null) for (m in quadRx2.findAll(givenText)) pf.quad = GQuad(m.groupValues[2], m.groupValues[1].lowercase(), m.groupValues[2] + " is a " + m.groupValues[1].lowercase())
        pf.quad?.let { pf.givens.add(it) }
        val shapes = pf.tris + listOfNotNull(pf.quad?.names)

        fun angKey(tok: String): String? {
            if (tok.length == 3) return ak(tok)
            if (tok.length == 1) {
                val v = tok[0]
                for (sh in shapes) {
                    val i = sh.indexOf(v)
                    if (i >= 0) return ak(sh[(i + sh.length - 1) % sh.length], v, sh[(i + 1) % sh.length])
                }
            }
            return null
        }

        // ---- givens
        for (m in chainRx.findAll(givenText)) {
            val parts = m.value.split("=").map { it.trim() }
            for (i in 0 until parts.size - 1) pf.givens.add(GSeg(parts[i], parts[i + 1], parts[i] + " = " + parts[i + 1]))
        }
        for (m in angEqRx.findAll(givenText)) {
            val a = angKey(m.groupValues[1])
            val b = angKey(m.groupValues[2])
            if (a != null && b != null) pf.givens.add(GAng(a, b, "∠" + m.groupValues[1] + " = ∠" + m.groupValues[2]))
        }
        for (m in rightRx.findAll(givenText)) {
            val a = angKey(m.groupValues[1])
            if (a != null) pf.givens.add(GRight(a, "∠" + m.groupValues[1] + " = 90°"))
        }
        for (m in rightAtRx.findAll(givenText)) {
            val a = angKey(m.groupValues[1])
            if (a != null && pf.givens.none { it is GRight && it.ang == a }) pf.givens.add(GRight(a, "right angle at " + m.groupValues[1]))
        }
        for (m in perpRx.findAll(givenText)) pf.givens.add(GPerp(m.groupValues[1], m.groupValues[2], m.groupValues[1] + " ⊥ " + m.groupValues[2]))
        for (m in midRx.findAll(givenText)) {
            pf.givens.add(GMid(m.groupValues[1][0], m.groupValues[2], m.groupValues[1] + " is the midpoint of " + m.groupValues[2]))
            if (m.groupValues[3].isNotEmpty()) pf.givens.add(GMid(m.groupValues[1][0], m.groupValues[3], m.groupValues[1] + " is the midpoint of " + m.groupValues[3]))
        }
        for (m in bisectEachRx.findAll(givenText)) {
            val o = m.groupValues[3][0]
            pf.givens.add(GInter(m.groupValues[1], m.groupValues[2], o, m.groupValues[1] + " and " + m.groupValues[2] + " bisect each other at " + o))
            pf.givens.add(GMid(o, m.groupValues[1], "$o is the midpoint of " + m.groupValues[1]))
            pf.givens.add(GMid(o, m.groupValues[2], "$o is the midpoint of " + m.groupValues[2]))
        }
        for (m in interRx.findAll(givenText)) {
            if (pf.givens.none { it is GInter }) pf.givens.add(GInter(m.groupValues[1], m.groupValues[2], m.groupValues[3][0], m.groupValues[1] + " and " + m.groupValues[2] + " intersect at " + m.groupValues[3]))
        }
        for (m in onRx.findAll(givenText)) pf.givens.add(GOn(m.groupValues[1][0], m.groupValues[2], m.groupValues[1] + " lies on " + m.groupValues[2]))
        for (m in onTwoRx.findAll(givenText)) {
            val s = m.groupValues[3]
            pf.givens.add(GOrder("" + s[0] + m.groupValues[1] + m.groupValues[2] + s[1], m.groupValues[1] + " and " + m.groupValues[2] + " lie on " + s))
        }
        for (m in rowRx.findAll(givenText)) {
            val ls = Regex("[A-Z]").findAll(m.groupValues[1]).map { it.value }.joinToString("")
            pf.givens.add(GOrder(ls, ls.toList().joinToString(", ") + " lie on a line in this order"))
        }
        for (m in bisRx.findAll(givenText)) {
            val a = angKey(m.groupValues[2])
            if (a != null && m.groupValues[1][0] == a[1]) pf.givens.add(GBis(m.groupValues[1], a[0], a[2], m.groupValues[1] + " bisects ∠" + a))
        }
        fun cevian(ad: String, kind: String) {
            val a = ad[0]
            val dd = ad[1]
            val sh = pf.tris.firstOrNull { a in it } ?: return
            val side = sh.filter { it != a }
            if (side.length != 2) return
            if (kind.lowercase() == "median") pf.givens.add(GMid(dd, side, "$ad is a median ($dd is the midpoint of $side)"))
            else {
                pf.givens.add(GPerp(ad, side, "$ad ⊥ $side ($ad is an altitude)"))
                pf.givens.add(GOn(dd, side, "$dd lies on $side"))
            }
        }
        for (m in altRx1.findAll(givenText)) cevian(m.groupValues[1], m.value.substringBefore(" ").lowercase())
        for (m in altRx2.findAll(givenText)) cevian(m.groupValues[1], m.value.substringAfterLast(" ").lowercase())
        val low = givenText.lowercase()
        if (low.contains("isosceles") && pf.givens.none { it is GSeg } && pf.tris.isNotEmpty()) {
            val s = pf.tris[0]
            pf.givens.add(GSeg("" + s[0] + s[1], "" + s[0] + s[2], "" + s[0] + s[1] + " = " + s[0] + s[2] + " (isosceles)"))
        }
        if (low.contains("equilateral") && pf.tris.isNotEmpty()) {
            val s = pf.tris[0]
            pf.givens.add(GSeg("" + s[0] + s[1], "" + s[1] + s[2], "" + s[0] + s[1] + " = " + s[1] + s[2] + " (equilateral)"))
            pf.givens.add(GSeg("" + s[1] + s[2], "" + s[2] + s[0], "" + s[1] + s[2] + " = " + s[2] + s[0] + " (equilateral)"))
        }

        // ---- goals
        for (m in congRx.findAll(qp)) pf.goals.add(GoalCong(m.groupValues[1], m.groupValues[2], "Δ" + m.groupValues[1] + " ≅ Δ" + m.groupValues[2]))
        for (m in congRx2.findAll(qp)) pf.goals.add(GoalCong(m.groupValues[1], m.groupValues[2], "Δ" + m.groupValues[1] + " ≅ Δ" + m.groupValues[2]))
        for (m in chainRx.findAll(qp)) {
            val parts = m.value.split("=").map { it.trim() }
            for (i in 0 until parts.size - 1) pf.goals.add(GoalSeg(parts[i], parts[i + 1], parts[i] + " = " + parts[i + 1]))
        }
        for (m in angEqRx.findAll(qp)) {
            val a = angKey(m.groupValues[1])
            val b = angKey(m.groupValues[2])
            if (a != null && b != null) pf.goals.add(GoalAng(a, b, "∠" + m.groupValues[1] + " = ∠" + m.groupValues[2]))
        }
        for (m in perpRx.findAll(qp)) pf.goals.add(GoalPerp(m.groupValues[1], m.groupValues[2], m.groupValues[1] + " ⊥ " + m.groupValues[2]))
        // triangles named only in the goal still belong to the figure
        for (g in pf.goals) if (g is GoalCong) for (s in listOf(g.t1, g.t2)) if (s !in pf.tris) pf.tris.add(s)
        return pf.goals.isNotEmpty()
    }

    // ---------------------------------------------------------------- one try

    private fun joinsFor(pf: PF, d: Diagram): List<String> {
        val miss = mutableListOf<String>()
        for (g in pf.goals) {
            if (g !is GoalCong) continue
            for (t in listOf(g.t1, g.t2)) for (i in 0..2) {
                val a = t[i]
                val b = t[(i + 1) % 3]
                if (a in d.pts && b in d.pts && !connectedPts(d, a, b) && sk(a, b) !in miss) miss.add(sk(a, b))
            }
        }
        return miss
    }

    private class Cand(val extras: List<Extra>, val constr: List<String>, val diags: List<String>, val built: List<Extra>)

    private fun family(pf: PF): String = when {
        pf.givens.any { it is GOrder } -> "F5"
        pf.givens.any { it is GInter } -> "F4"
        pf.quad != null -> "F3"
        pf.tris.size >= 2 && pf.tris.indices.any { i -> (i + 1 until pf.tris.size).any { j -> (pf.tris[i].toSet() intersect pf.tris[j].toSet()).isEmpty() } } -> "F1"
        pf.tris.isNotEmpty() -> "F2"
        else -> ""
    }

    private fun freeLetter(pf: PF, used: Set<Char>): Char {
        for (c in "DEFGHKLMNPQRSTUVWXYZ") if (c !in pf.letters && c !in used) return c
        return 'Z'
    }

    private fun build(pf: PF, fam: String, extras: List<Extra>, diags: List<String>, constr: List<String>, style: Int): Diagram? {
        fun withJoins(make: (List<String>, List<String>) -> Diagram?): Diagram? {
            val first = make(emptyList(), emptyList()) ?: return null
            val miss = joinsFor(pf, first)
            if (miss.isEmpty()) return first
            return make(miss, miss.map { "Join " + it[0] + it[1] })
        }
        return when (fam) {
            "F5" -> buildF5(pf, (pf.givens.first { it is GOrder } as GOrder).pts, style)
            "F4" -> withJoins { j, c -> buildF4(pf, pf.givens.first { it is GInter } as GInter, j, style, constr + c) }
            "F3" -> {
                val q = pf.quad ?: return null
                withJoins { j, c -> buildF3(pf, q.names, q.kind, diags, j, style, constr + c) }
            }
            "F1" -> {
                val a = pf.tris.first { x -> pf.tris.any { y -> (x.toSet() intersect y.toSet()).isEmpty() } }
                val b = pf.tris.first { y -> (a.toSet() intersect y.toSet()).isEmpty() }
                buildF1(pf, a, b, style)
            }
            else -> withJoins { j, c -> buildF2(pf, pf.tris[0], extras, j, style, constr + c) }
        }
    }

    /** Extra points from the Given facts (midpoints, feet of altitudes, bisectors, points on a side) in a single triangle. */
    private fun givenExtras(pf: PF): List<Extra> {
        val tri = pf.tris.firstOrNull() ?: return emptyList()
        val out = mutableListOf<Extra>()
        fun side(s: String): Boolean = s.length == 2 && s.all { it in tri }
        fun apexOf(s: String): Char = tri.first { it !in s }
        for (g in pf.givens) {
            when (g) {
                is GMid -> if (side(g.s) && g.m !in tri) out.add(Extra('m', g.m, apexOf(g.s), g.s[0], g.s[1]))
                is GOn -> if (side(g.s) && g.p !in tri) out.add(Extra('o', g.p, apexOf(g.s), g.s[0], g.s[1]))
                is GPerp -> {
                    val dd = g.l1.firstOrNull { it !in tri }
                    if (dd != null && side(g.l2) && g.l1.any { it in tri && it !in g.l2 }) out.add(Extra('a', dd, g.l1.first { it in tri }, g.l2[0], g.l2[1]))
                }
                is GBis -> {
                    val dd = g.ray[1]
                    val a = g.ray[0]
                    val s = tri.filter { it != a }
                    if (dd !in tri && a in tri && s.length == 2) out.add(Extra('b', dd, a, s[0], s[1]))
                }
                else -> {}
            }
        }
        return out
    }

    private fun candidates(pf: PF, fam: String): List<Triple<List<Extra>, List<String>, List<Extra>>> {
        val base = if (fam == "F2") givenExtras(pf) else emptyList()
        val out = mutableListOf(Triple(base, emptyList<String>(), emptyList<Extra>()))
        if (fam == "F2") {
            val tri = pf.tris[0]
            val used = (base.map { it.d } + tri.toList()).toSet()
            val nl = freeLetter(pf, used)
            for (k in 0..2) {
                val a = tri[k]
                val b = tri[(k + 1) % 3]
                val c = tri[(k + 2) % 3]
                val ea = Extra('a', nl, a, b, c)
                val em = Extra('m', nl, a, b, c)
                val eb = Extra('b', nl, a, b, c)
                out.add(Triple(base + ea, listOf("Draw $a$nl ⊥ $b$c from $a (the altitude from $a)"), listOf(ea)))
                out.add(Triple(base + em, listOf("Let $nl be the midpoint of $b$c and join $a$nl (the median from $a)"), listOf(em)))
                out.add(Triple(base + eb, listOf("Draw $a$nl, the bisector of ∠$a, meeting $b$c at $nl"), listOf(eb)))
            }
        }
        return out
    }

    private fun diagCandidates(pf: PF): List<List<String>> {
        val q = pf.quad ?: return listOf(emptyList())
        val n = q.names
        if (n.length != 4) return listOf(emptyList())
        val ac = sk(n[0], n[2])
        val bd = sk(n[1], n[3])
        return listOf(emptyList(), listOf(ac), listOf(bd), listOf(ac, bd))
    }

    // ---------------------------------------------------------------- the drone: measure the figure

    private fun measure(d: Diagram, pf: PF, steps: MutableList<String>): String? {
        var bad: String? = null
        fun ok(msg: String, pass: Boolean, shown: String) {
            if (pass) steps.add("  ✓ $msg ($shown)") else if (bad == null) bad = "$msg is false ($shown)"
        }
        fun len(k: String) = dist(pp(d, k[0]), pp(d, k[1]))
        fun ang(k: String) = angDeg(pp(d, k[1]), pp(d, k[0]), pp(d, k[2]))
        fun f2(v: Double) = qf(v)
        for (g in pf.givens) {
            when (g) {
                is GSeg -> ok(g.text, abs(len(g.a) - len(g.b)) < 1e-6 * max(1.0, len(g.a)), f2(len(g.a)) + " and " + f2(len(g.b)))
                is GAng -> ok(g.text, abs(ang(g.a) - ang(g.b)) < 0.01, f2(ang(g.a)) + "° and " + f2(ang(g.b)) + "°")
                is GRight -> ok(g.text, abs(ang(g.ang) - 90.0) < 0.01, f2(ang(g.ang)) + "°")
                is GMid -> ok(g.text, abs(len("" + g.s[0] + g.m) - len("" + g.m + g.s[1])) < 1e-6 && onSegInc(pp(d, g.m), pp(d, g.s[0]), pp(d, g.s[1])), f2(len("" + g.s[0] + g.m)) + " and " + f2(len("" + g.m + g.s[1])))
                is GOn -> ok(g.text, onSegInc(pp(d, g.p), pp(d, g.s[0]), pp(d, g.s[1])), "on the line")
                is GPerp -> {
                    val a = pp(d, g.l1[0])
                    val b = pp(d, g.l1[1])
                    val c = pp(d, g.l2[0])
                    val e = pp(d, g.l2[1])
                    val dot = (b.x - a.x) * (e.x - c.x) + (b.y - a.y) * (e.y - c.y)
                    val m = max(1e-9, dist(a, b) * dist(c, e))
                    ok(g.text, abs(dot / m) < 1e-6, "angle between them " + f2(angDeg(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y), Pt(e.x - c.x, e.y - c.y))) + "°")
                }
                is GBis -> ok(g.text, abs(ang("" + g.b + g.ray[0] + g.ray[1]) - ang("" + g.ray[1] + g.ray[0] + g.c)) < 0.01, f2(ang("" + g.b + g.ray[0] + g.ray[1])) + "° and " + f2(ang("" + g.ray[1] + g.ray[0] + g.c)) + "°")
                else -> {}
            }
        }
        for (g in pf.goals) {
            when (g) {
                is GoalSeg -> ok(g.text, abs(len(g.a) - len(g.b)) < 1e-6 * max(1.0, len(g.a)), f2(len(g.a)) + " and " + f2(len(g.b)))
                is GoalAng -> ok(g.text, abs(ang(g.a) - ang(g.b)) < 0.01, f2(ang(g.a)) + "° and " + f2(ang(g.b)) + "°")
                is GoalCong -> {
                    var same = true
                    var shown = ""
                    for (k in 0..2) {
                        val s1 = len("" + g.t1[k] + g.t1[(k + 1) % 3])
                        val s2 = len("" + g.t2[k] + g.t2[(k + 1) % 3])
                        if (abs(s1 - s2) > 1e-6 * max(1.0, s1)) same = false
                        if (k == 0) shown = f2(s1) + " and " + f2(s2)
                    }
                    ok(g.text, same, "all three sides match, e.g. $shown")
                }
                is GoalPerp -> ok(g.text, true, "proved")
            }
        }
        return bad
    }

    // ---------------------------------------------------------------- the entry point

    fun solve(raw: String, variant: Int): QResult? {
        val pf = PF()
        if (!parse(raw, pf)) return null
        val steps = mutableListOf<String>()
        val think = mutableListOf<String>()
        steps.add(if (variant > 0) "🔁 Rechecking — proof attempt #" + (variant + 1) else "📖 Reading the question…")
        for (g in pf.givens) steps.add("Given: " + g.text)
        for (g in pf.goals) steps.add(pf.label + ": " + g.text)
        val fam = family(pf)
        if (fam.isEmpty()) return QResult(steps, emptyList(), null, "I could not tell which triangles the question is about. Write them like ΔABC and ΔDEF.")
        val famName = when (fam) {
            "F1" -> "two separate triangles"
            "F2" -> "one triangle with points on its sides"
            "F3" -> "a quadrilateral and its diagonals"
            "F4" -> "two lines crossing at a point"
            else -> "points on a straight line"
        }
        steps.add("Figure type: $famName")
        think.add("Read " + pf.givens.size + " given fact(s) and " + pf.goals.size + " thing(s) to " + (if (pf.label == "To show") "show" else "prove"))
        think.add("Figure type: $famName — building a figure that satisfies every given fact")
        think.add("Rules I can use: common side, midpoint, right angles, angle bisector, vertically opposite angles, adding / subtracting equal segments")
        think.add("Congruence rules to test: SSS, SAS, ASA, AAS, RHS — then CPCT for the matching parts")

        val cands = ArrayList<Cand>()
        if (fam == "F3") for (dg in diagCandidates(pf)) cands.add(Cand(emptyList(), dg.map { "Join $it" }, dg, emptyList()))
        else for (c in candidates(pf, fam)) cands.add(Cand(c.first, c.second, emptyList(), c.third))
        think.add("Constructions I can add if the proof gets stuck: " + (if (fam == "F2") "altitude, median, angle bisector from each corner" else if (fam == "F3") "the diagonals" else "none needed"))

        val rot = if (cands.size > 1) variant % cands.size else 0
        val order = cands.drop(rot) + cands.take(rot)
        var tried = 0
        var lastWhy: String? = null
        for (iso in listOf(false, true)) {
            for (cand in order) {
                val extras = cand.extras
                val constr = cand.constr
                val diags = cand.diags
                tried++
                val d = build(pf, fam, extras, diags, constr, variant) ?: continue
                val eng = Eng(d, pf, iso, cand.built)
                val reached = eng.closure()
                if (!reached) {
                    lastWhy = "the goal could not be reached" + (if (constr.isNotEmpty()) " even after: " + constr.first() else "")
                    continue
                }
                if (!eng.write()) continue
                val log = mutableListOf<String>()
                val bad = measure(d, pf, log)
                if (bad != null) {
                    steps.add("✗ Check failed: $bad — deleting this figure and trying another way")
                    lastWhy = bad
                    continue
                }
                // success
                if (d.construction.isNotEmpty()) steps.add("Construction: " + d.construction.joinToString("; "))
                steps.add("✏️ Drawing the figure…")
                steps.addAll(log)
                steps.add("✅ All given facts and the conclusion hold on the figure")
                think.add("Triangles found in the figure: " + eng.tris.joinToString(", ") { "Δ$it" })
                think.add("Pairs of triangles tested: " + eng.pairsChecked + " (with all 6 ways of matching their corners)")
                think.add("Proof attempts: $tried" + (if (iso) " (used the isosceles-triangle theorem)" else ""))
                val board = mutableListOf<String>()
                board.add("Given:")
                for (g in pf.givens) board.add("• " + g.text)
                board.add((if (pf.label == "To show") "To show: " else "To prove: ") + pf.goals.joinToString(";  ") { it.text })
                if (d.construction.isNotEmpty()) board.add("Construction: " + d.construction.joinToString(";  "))
                board.add("Proof:")
                board.addAll(eng.out)
                board.add("Hence proved. ✓")
                val ans = mutableListOf<String>()
                for (g in pf.goals) ans.add("✓ " + g.text)
                val plan = QPlan(
                    names = "",
                    pts = emptyList(),
                    showSides = emptySet(),
                    showAngles = emptySet(),
                    extras = emptySet(),
                    title = d.title,
                    figs = d.figs,
                )
                return QResult(steps, ans, plan, null, think, board)
            }
        }
        steps.add("✗ I could not build a proof from these facts" + (lastWhy?.let { " ($it)" } ?: ""))
        return QResult(
            steps, emptyList(), null,
            "I could not prove it with the rules I have (" + (lastWhy ?: "no figure fitted") + "). Check that every fact is typed, for example AB = CD, ∠B = ∠E, D is the midpoint of BC.",
            think,
        )
    }
}
