package com.aismartmath.board.board

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Trigonometry without any AI: a small parser for what people type (sin θ, 2sinθcosθ, sin^2 x, tan 30°, √3/2, π/6 ...),
 * an evaluator that works in degrees, a finder for exact values (1/2, √3/2 ...), and a scanner that finds every
 * solution of an equation over a range and an identity test. Used by [TrigSolver].
 */
internal sealed class TN {
    class Num(val v: Double, val isPi: Boolean = false) : TN()
    class Var(val name: Char) : TN()
    class Bin(val op: Char, val a: TN, val b: TN) : TN()
    class Neg(val a: TN) : TN()
    class Fn(val name: String, val a: TN, val pw: Double) : TN()
    class Sq(val a: TN) : TN()
}

private fun rad(d: Double) = d * PI / 180

internal fun applyFn(n: String, v: Double): Double {
    val s = sin(rad(v))
    val c = cos(rad(v))
    return when (n) {
        "sin" -> s
        "cos" -> c
        "tan" -> if (abs(c) < 1e-12) Double.NaN else s / c
        "sec" -> if (abs(c) < 1e-12) Double.NaN else 1 / c
        "csc" -> if (abs(s) < 1e-12) Double.NaN else 1 / s
        "cot" -> if (abs(s) < 1e-12) Double.NaN else c / s
        "asin" -> if (abs(v) > 1.0 + 1e-12) Double.NaN else asin(v.coerceIn(-1.0, 1.0)) * 180 / PI
        "acos" -> if (abs(v) > 1.0 + 1e-12) Double.NaN else acos(v.coerceIn(-1.0, 1.0)) * 180 / PI
        "atan" -> atan(v) * 180 / PI
        else -> Double.NaN
    }
}

/** Value of the expression when the variable is x degrees. */
internal fun tev(n: TN, x: Double): Double = when (n) {
    is TN.Num -> n.v
    is TN.Var -> x
    is TN.Neg -> -tev(n.a, x)
    is TN.Sq -> sqrt(tev(n.a, x))
    is TN.Bin -> {
        val p = tev(n.a, x)
        val q = tev(n.b, x)
        when (n.op) {
            '+' -> p + q
            '-' -> p - q
            '*' -> p * q
            '/' -> if (abs(q) < 1e-12) Double.NaN else p / q
            else -> p.pow(q)
        }
    }
    is TN.Fn -> {
        val r = applyFn(n.name, tev(n.a, x))
        if (n.pw == 1.0) r else r.pow(n.pw)
    }
}

internal fun tvars(n: TN, out: MutableSet<Char> = mutableSetOf()): MutableSet<Char> {
    when (n) {
        is TN.Var -> out.add(n.name)
        is TN.Neg -> tvars(n.a, out)
        is TN.Sq -> tvars(n.a, out)
        is TN.Bin -> {
            tvars(n.a, out)
            tvars(n.b, out)
        }
        is TN.Fn -> tvars(n.a, out)
        is TN.Num -> {}
    }
    return out
}

// ---------------------------------------------------------------- reading what was typed

private class Tok(val k: Char, val s: String, val v: Double = 0.0)

private fun prep(src: String): String {
    var s = src
    s = s.replace("−", "-").replace("–", "-").replace("×", "*").replace("·", "*").replace("÷", "/")
    s = s.replace("²", "^2").replace("³", "^3").replace("√", "sqrt")
    s = Regex("(?i)degrees?").replace(s, "°")
    s = Regex("(?i)theta").replace(s, "θ")
    s = Regex("(?i)\\bpi\\b").replace(s, "π")
    s = Regex("(?i)cosec").replace(s, "csc")
    s = Regex("(?i)arc(sin|cos|tan)").replace(s) { "a" + it.groupValues[1] }
    s = Regex("(?i)(sin|cos|tan|sec|csc|cot|sqrt)").replace(s) { it.value.lowercase() }
    return s.replace(" ", "")
}

private fun lex(s: String): List<Tok>? {
    val out = mutableListOf<Tok>()
    val fns = listOf("asin", "acos", "atan", "sqrt", "sin", "cos", "tan", "sec", "csc", "cot")
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit())) {
            var j = i
            while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
            val num = s.substring(i, j).toDoubleOrNull() ?: return null
            out.add(Tok('n', s.substring(i, j), num))
            i = j
        } else if (c == '°') {
            out.add(Tok('d', "°"))
            i++
        } else if (c == 'π') {
            out.add(Tok('p', "π"))
            i++
        } else if (c in "+-*/^()") {
            out.add(Tok('o', c.toString()))
            i++
        } else {
            val f = fns.firstOrNull { s.startsWith(it, i) }
            if (f != null) {
                out.add(Tok(if (f == "sqrt") 'r' else 'f', f))
                i += f.length
            } else if (c.isLetter()) {
                out.add(Tok('v', c.toString()))
                i++
            } else {
                return null
            }
        }
    }
    return out
}

private class TP(val t: List<Tok>) {
    var i = 0

    fun peek(): Tok? = t.getOrNull(i)

    fun isOp(c: String): Boolean {
        val p = peek() ?: return false
        return p.k == 'o' && p.s == c
    }

    fun startsAtom(): Boolean {
        val p = peek() ?: return false
        return p.k == 'n' || p.k == 'v' || p.k == 'f' || p.k == 'r' || p.k == 'p' || (p.k == 'o' && p.s == "(")
    }

    fun expr(): TN? {
        var l = term() ?: return null
        while (true) {
            if (isOp("+")) {
                i++
                val r = term() ?: return null
                l = TN.Bin('+', l, r)
            } else if (isOp("-")) {
                i++
                val r = term() ?: return null
                l = TN.Bin('-', l, r)
            } else {
                break
            }
        }
        return l
    }

    fun term(): TN? {
        var l = unary() ?: return null
        while (true) {
            if (isOp("*")) {
                i++
                val r = unary() ?: return null
                l = TN.Bin('*', l, r)
            } else if (isOp("/")) {
                i++
                val r = unary() ?: return null
                l = TN.Bin('/', l, r)
            } else if (startsAtom()) {
                val r = unary() ?: return null
                l = TN.Bin('*', l, r)
            } else {
                break
            }
        }
        return l
    }

    fun unary(): TN? {
        if (isOp("-")) {
            i++
            val a = unary() ?: return null
            return TN.Neg(a)
        }
        if (isOp("+")) {
            i++
            return unary()
        }
        return power()
    }

    fun power(): TN? {
        val b = atomPost() ?: return null
        if (isOp("^")) {
            i++
            val e = unary() ?: return null
            return TN.Bin('^', b, e)
        }
        return b
    }

    fun atomPost(): TN? {
        val a = atom() ?: return null
        while (peek()?.k == 'd') i++
        return a
    }

    fun paren(): TN? {
        if (!isOp("(")) return null
        i++
        val e = expr() ?: return null
        if (!isOp(")")) return null
        i++
        return e
    }

    fun atom(): TN? {
        val p = peek() ?: return null
        when (p.k) {
            'n' -> {
                i++
                return TN.Num(p.v)
            }
            'p' -> {
                i++
                return TN.Num(180.0, true)
            }
            'v' -> {
                i++
                return TN.Var(p.s[0])
            }
            'r' -> {
                i++
                val a = (if (isOp("(")) paren() else atomPost()) ?: return null
                return TN.Sq(a)
            }
            'f' -> {
                i++
                var pw = 1.0
                if (isOp("^")) {
                    i++
                    val q = peek()
                    if (q != null && q.k == 'n') {
                        pw = q.v
                        i++
                    } else {
                        return null
                    }
                }
                val a = fnArg() ?: return null
                return TN.Fn(p.s, a, pw)
            }
            else -> return paren()
        }
    }

    /** What a function takes: (…) or a short run like 30°, 2θ, A — it stops at +, -, * and / and at the next function. */
    fun fnArg(): TN? {
        if (isOp("(")) return paren()
        var neg = false
        if (isOp("-")) {
            i++
            neg = true
        }
        var a = simple() ?: return null
        while (true) {
            val q = peek() ?: break
            if (q.k == 'n' || q.k == 'v' || q.k == 'p' || q.k == 'r' || (q.k == 'o' && q.s == "(")) {
                val r = simple() ?: return null
                a = TN.Bin('*', a, r)
            } else if (q.k == 'o' && q.s == "^") {
                i++
                val e = simple() ?: return null
                a = TN.Bin('^', a, e)
            } else {
                break
            }
        }
        return if (neg) TN.Neg(a) else a
    }

    fun simple(): TN? {
        val p = peek() ?: return null
        val a: TN = when {
            p.k == 'n' -> {
                i++
                TN.Num(p.v)
            }
            p.k == 'p' -> {
                i++
                TN.Num(180.0, true)
            }
            p.k == 'v' -> {
                i++
                TN.Var(p.s[0])
            }
            p.k == 'r' -> {
                i++
                val x = (if (isOp("(")) paren() else simple()) ?: return null
                TN.Sq(x)
            }
            p.k == 'o' && p.s == "(" -> paren() ?: return null
            else -> return null
        }
        while (peek()?.k == 'd') i++
        return a
    }
}

internal fun parseTN(src: String): TN? {
    val toks = lex(prep(src)) ?: return null
    if (toks.isEmpty()) return null
    val p = TP(toks)
    val e = p.expr() ?: return null
    if (p.i != toks.size) return null
    return e
}

// ---------------------------------------------------------------- showing it back (typed maths, drawn as fractions on the board)

private fun prec(op: Char) = when (op) {
    '+', '-' -> 1
    '*', '/' -> 2
    else -> 3
}

/** The expression as typed maths. subst = replace function calls on plain numbers by their exact values. */
internal fun showTN(n: TN, subst: Boolean = false, parent: Int = 0): String = when (n) {
    is TN.Num -> if (n.isPi) "π" else qf(n.v)
    is TN.Var -> n.name.toString()
    is TN.Neg -> "-" + showTN(n.a, subst, 3)
    is TN.Sq -> "sqrt(" + showTN(n.a, subst, 0) + ")"
    is TN.Bin -> {
        val p = prec(n.op)
        val l = showTN(n.a, subst, if (n.op == '^') 4 else p)
        val r = showTN(n.b, subst, if (n.op == '-' || n.op == '/') p + 1 else if (n.op == '^') 4 else p)
        val body = if (n.op == '^') "$l^$r" else l + (if (n.op == '*') "*" else if (n.op == '/') "/" else " " + n.op + " ") + r
        if (p < parent) "($body)" else body
    }
    is TN.Fn -> {
        val noVar = tvars(n.a).isEmpty()
        if (subst && noVar) {
            val v = tev(n, 0.0)
            val e = exactOf(v)
            if (e != null) {
                if (e.contains(" ") || e.contains("/") || e.contains("-")) "($e)" else e
            } else {
                qf4(v)
            }
        } else {
            val arg = if (noVar) qf(tev(n.a, 0.0)) + "°" else showTN(n.a, subst, 0)
            if (n.pw == 1.0) n.name + "(" + arg + ")" else n.name + "(" + arg + ")^" + qf(n.pw)
        }
    }
}

internal fun qf4(v: Double): String {
    val r = round(v * 10000) / 10000
    return if (abs(r - round(r)) < 1e-9) round(r).toLong().toString() else r.toString()
}

// ---------------------------------------------------------------- exact values

/** 1/2, sqrt(3)/2, 2*sqrt(2), 3/4 ... or null when the number is not one of the usual exact forms. */
internal fun exactOf(v0: Double): String? {
    if (v0.isNaN() || v0.isInfinite()) return null
    val v = abs(v0)
    val s = exactPos(v) ?: return null
    return if (v0 < 0 && v > 1e-12) "-$s" else s
}

private fun exactPos(v: Double): String? {
    val tol = 1e-9
    if (abs(v - round(v)) < tol && v < 1e6) return round(v).toLong().toString()
    val sp = listOf(
        sqrt(3.0) / 3 to "1/sqrt(3)",
        2 / sqrt(3.0) to "2/sqrt(3)",
        (sqrt(6.0) - sqrt(2.0)) / 4 to "(sqrt(6) - sqrt(2))/4",
        (sqrt(6.0) + sqrt(2.0)) / 4 to "(sqrt(6) + sqrt(2))/4",
        2 - sqrt(3.0) to "2 - sqrt(3)",
        2 + sqrt(3.0) to "2 + sqrt(3)",
    )
    for ((x, s) in sp) if (abs(v - x) < tol) return s
    for (q in 2..20) {
        val p = v * q
        if (abs(p - round(p)) < tol) return round(p).toLong().toString() + "/" + q
    }
    for (n in listOf(2, 3, 5, 6, 7, 10, 11, 13, 14, 15)) {
        val r = sqrt(n.toDouble())
        for (q in 1..12) {
            val p = v * q / r
            val pr = round(p)
            if (abs(p - pr) < tol && pr >= 1 && pr <= 12) {
                val pi = pr.toLong()
                val core = if (pi == 1L) "sqrt($n)" else "$pi*sqrt($n)"
                return if (q == 1) core else "$core/$q"
            }
        }
    }
    return null
}

/** A ratio p/q (q up to 1000) when the number is rational, else null. */
internal fun ratioOf(v: Double): Pair<Long, Long>? {
    if (v.isNaN() || v.isInfinite()) return null
    val a = abs(v)
    for (q in 1..1000) {
        val p = a * q
        if (abs(p - round(p)) < 1e-9) return Pair(round(p).toLong() * (if (v < 0) -1 else 1), q.toLong())
    }
    return null
}

// ---------------------------------------------------------------- equations and identities

internal fun degText(d: Double): String = qf(d) + "°"

/** Every x in [lo, hi) where f(x) = 0: sign changes and touching points, refined to high accuracy. */
internal fun scanRoots(f: (Double) -> Double, lo: Double, hi: Double, step: Double): List<Double> {
    val found = mutableListOf<Double>()
    fun add(r: Double) {
        if (r < lo - 1e-9 || r >= hi - 1e-9) return
        val snapped = round(r * 1e6) / 1e6
        if (found.none { abs(it - snapped) < 1e-4 }) found.add(snapped)
    }
    val n = max(1, ((hi - lo) / step).toInt())
    val xs = DoubleArray(n + 1) { lo + (hi - lo) * it / n }
    val ys = DoubleArray(n + 1) { f(xs[it]) }
    for (k in 0..n) {
        val y0 = ys[k]
        if (y0.isNaN()) continue
        if (abs(y0) < 1e-10) {
            add(xs[k])
            continue
        }
        if (k < n) {
            val y1 = ys[k + 1]
            if (!y1.isNaN() && y0 * y1 < 0) {
                var a = xs[k]
                var b = xs[k + 1]
                var fa = y0
                for (it in 0 until 70) {
                    val m = (a + b) / 2
                    val fm = f(m)
                    if (fm.isNaN()) break
                    if (fa * fm <= 0) b = m else {
                        a = m
                        fa = fm
                    }
                }
                val r = (a + b) / 2
                val fr = f(r)
                if (!fr.isNaN() && abs(fr) < 1e-6) add(r)
            }
        }
        // a root where the curve only touches zero (like sin^2 x = 0 at a non-grid point)
        if (k in 1 until n) {
            val ym = ys[k - 1]
            val yp = ys[k + 1]
            if (!ym.isNaN() && !yp.isNaN() && abs(y0) <= abs(ym) && abs(y0) <= abs(yp) && abs(y0) < 1e-2 && ym * y0 >= 0 && y0 * yp >= 0) {
                var a = xs[k - 1]
                var b = xs[k + 1]
                for (it in 0 until 80) {
                    val m1 = a + (b - a) / 3
                    val m2 = b - (b - a) / 3
                    if (abs(f(m1)) < abs(f(m2))) b = m2 else a = m1
                }
                val r = (a + b) / 2
                val fr = f(r)
                if (!fr.isNaN() && abs(fr) < 1e-9) add(r)
            }
        }
    }
    return found.sorted()
}

/** True when left and right agree at (nearly) every angle where both exist. */
internal fun isIdentity(l: TN, r: TN): Boolean {
    var good = 0
    for (k in 0 until 60) {
        val x = 7.3 + 13.7 * k
        val a = tev(l, x)
        val b = tev(r, x)
        if (a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite()) continue
        if (abs(a - b) > 1e-9 * (1 + abs(a) + abs(b))) return false
        good++
    }
    return good >= 12
}

/** Does the function (one variable) repeat every `p` degrees? */
internal fun repeatsEvery(f: (Double) -> Double, p: Double): Boolean {
    var good = 0
    for (k in 0 until 30) {
        val x = 3.1 + 11.9 * k
        val a = f(x)
        val b = f(x + p)
        if (a.isNaN() || b.isNaN()) continue
        if (abs(a - b) > 1e-8 * (1 + abs(a) + abs(b))) return false
        good++
    }
    return good >= 8
}

/** A few well known identities, to name what the question uses. Typed with x as the angle. */
internal val KNOWN_IDS: List<Triple<String, String, String>> = listOf(
    Triple("Quotient identity", "sin(x)/cos(x)", "tan(x)"),
    Triple("Quotient identity", "cos(x)/sin(x)", "cot(x)"),
    Triple("Reciprocal identity", "1/sin(x)", "csc(x)"),
    Triple("Reciprocal identity", "1/cos(x)", "sec(x)"),
    Triple("Reciprocal identity", "1/tan(x)", "cot(x)"),
    Triple("Pythagorean identity", "sin(x)^2 + cos(x)^2", "1"),
    Triple("Pythagorean identity (tan, sec)", "1 + tan(x)^2", "sec(x)^2"),
    Triple("Pythagorean identity (cot, csc)", "1 + cot(x)^2", "csc(x)^2"),
    Triple("Double angle (sine)", "sin(2x)", "2sin(x)cos(x)"),
    Triple("Double angle (cosine)", "cos(2x)", "cos(x)^2 - sin(x)^2"),
    Triple("Double angle (cosine)", "cos(2x)", "1 - 2sin(x)^2"),
    Triple("Double angle (cosine)", "cos(2x)", "2cos(x)^2 - 1"),
    Triple("Double angle (tangent)", "tan(2x)", "2tan(x)/(1 - tan(x)^2)"),
    Triple("Complementary angles", "sin(90 - x)", "cos(x)"),
    Triple("Complementary angles", "cos(90 - x)", "sin(x)"),
    Triple("Complementary angles", "tan(90 - x)", "cot(x)"),
    Triple("Supplementary angles", "sin(180 - x)", "sin(x)"),
    Triple("Supplementary angles", "cos(180 - x)", "-cos(x)"),
    Triple("Negative angles", "sin(-x)", "-sin(x)"),
    Triple("Negative angles", "cos(-x)", "cos(x)"),
    Triple("Negative angles", "tan(-x)", "-tan(x)"),
)

private fun sameFn(a: TN, b: TN): Boolean {
    var good = 0
    for (k in 0 until 24) {
        val x = 5.7 + 15.3 * k
        val p = tev(a, x)
        val q = tev(b, x)
        if (p.isNaN() || q.isNaN() || p.isInfinite() || q.isInfinite()) continue
        if (abs(p - q) > 1e-9 * (1 + abs(p) + abs(q))) return false
        good++
    }
    return good >= 8
}

/** Names of the known identities whose either side equals either side of the question (as functions of the angle). */
internal fun namedIdentities(l: TN, r: TN): List<String> {
    val out = mutableListOf<String>()
    for ((name, a, b) in KNOWN_IDS) {
        val ta = parseTN(a) ?: continue
        val tb = parseTN(b) ?: continue
        val hit = sameFn(l, ta) || sameFn(l, tb) || sameFn(r, ta) || sameFn(r, tb)
        if (hit) {
            val line = name + ":  " + a + " = " + b
            if (line !in out) out.add(line)
        }
    }
    return out
}

internal fun minOf2(a: Double, b: Double) = min(a, b)
