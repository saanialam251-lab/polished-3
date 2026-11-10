package com.aismartmath.board.board

import com.aismartmath.board.model.Pt
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Trigonometry questions without AI: "find the value of sin 30° + cos 60°", "solve 2sin²θ − 1 = 0 for 0° ≤ θ ≤ 360°",
 * "prove that sin θ / cos θ = tan θ", "if sin θ = 3/5 find cos θ and tan θ", "find θ if tan θ = 1/√3".
 * Returns null when the text is not a trig question (the triangle solver then gets it).
 */
internal object TrigSolver {
    private val fnWord = Regex("(?<![a-zA-Z])(?:sin|cos|tan|sec|cosec|csc|cot)(?![a-zA-Z])", RegexOption.IGNORE_CASE)
    private val thetaWord = Regex("θ|(?i:theta)")
    private val goalRx = Regex("(?i)\\b(?:find|calculate|evaluate|determine|compute|what\\s+is|what\\s+are|solve|prove|show\\s+that|verify|simplify)\\b")
    private const val NUMB = "-?(?:\\d+(?:\\.\\d+)?\\s*(?:π|(?i:pi))?|(?:π|(?i:pi)))(?:/\\d+)?\\s*°?"
    private val rangeRx = Regex("(?:(?i:for|where|when|in|if)\\s+)?(" + NUMB + ")\\s*(?:≤|<=|<)\\s*([θαβφxt]|(?i:theta))\\s*(?:≤|<=|<)\\s*(" + NUMB + ")")
    private val prefixRx = Regex("(?i)^(?:if|given that|given|let|suppose|when|that|the value of|value of|the expression|the equation|the identity|for)\\s+")
    private val askVar = Regex("(?i)^(?:the\\s+)?(?:(?:general\\s+)?(?:value|values|solutions?|angles?)\\s+(?:of\\s+)?)?(?:[θαβφxt]|theta)\\W*$")

    private class Ctx {
        val steps = mutableListOf<String>()
        val think = mutableListOf<String>()
        val board = mutableListOf<String>()
        val ans = mutableListOf<String>()
        fun result(plan: QPlan? = null, problem: String? = null) = QResult(steps, ans, plan, problem, think, board)
    }

    private fun clean(s0: String): String {
        var s = s0.trim()
        s = prefixRx.replace(s, "")
        s = prefixRx.replace(s, "")
        s = s.trim().trimEnd(',', '.', ':', ';', '?', '!').trim()
        s = Regex("(?i)\\s*,?\\s*(?:then|and)$").replace(s, "").trim()
        return s
    }

    private fun bound(s: String): Double? {
        val t = parseTN(s.trim()) ?: return null
        val v = tev(t, 0.0)
        return if (v.isNaN()) null else v
    }

    fun looksTrig(raw: String): Boolean = fnWord.containsMatchIn(raw) || thetaWord.containsMatchIn(raw)

    fun solve(raw: String, variant: Int): QResult? {
        if (!looksTrig(raw)) return null
        val c = Ctx()
        c.steps.add("📖 Reading the question…")
        var txt = raw.trim().replace("\n", " ")
        var lo: Double? = null
        var hi: Double? = null
        var rangeVar: String? = null
        val rm = rangeRx.find(txt)
        if (rm != null) {
            lo = bound(rm.groupValues[1])
            hi = bound(rm.groupValues[3])
            rangeVar = rm.groupValues[2]
            if (lo != null && hi != null) {
                txt = txt.replace(rm.value, " ")
                c.steps.add("Range: " + degText(lo) + " ≤ " + rangeVar + " ≤ " + degText(hi))
            } else {
                lo = null
                hi = null
            }
        }
        val gm = goalRx.find(txt)
        val kw = gm?.value?.lowercase()?.replace(Regex("\\s+"), " ")
        var before = if (gm != null) clean(txt.substring(0, gm.range.first)) else ""
        var after = clean(if (gm != null) txt.substring(gm.range.last + 1) else txt)
        val ifm = Regex("(?i)\\s+(?:if|given that|given)\\s+").find(after)
        if (kw != null && before.isEmpty() && ifm != null && after.substring(ifm.range.last + 1).contains("=")) {
            before = clean(after.substring(ifm.range.last + 1))
            after = clean(after.substring(0, ifm.range.first))
        }
        val proving = kw == "prove" || kw == "show that" || kw == "verify"
        return when {
            proving -> eqMode(c, after, true, lo, hi, variant)
            kw == "solve" -> if (after.contains("=")) eqMode(c, after, false, lo, hi, variant) else evalMode(c, after, kw, variant)
            before.contains("=") && kw != null ->
                if (askVar.matches(after.trim())) eqMode(c, before, false, lo, hi, variant)
                else ratioMode(c, before, after, txt, variant)
            kw != null -> if (after.contains("=")) eqMode(c, after, false, lo, hi, variant) else evalMode(c, after, kw, variant)
            txt.contains("=") -> eqMode(c, clean(txt), false, lo, hi, variant)
            else -> evalMode(c, clean(txt), null, variant)
        }
    }

    // ------------------------------------------------------------------ a value: sin 30° + cos 60°

    private fun constFns(n: TN, out: MutableList<TN.Fn>) {
        when (n) {
            is TN.Fn -> if (tvars(n.a).isEmpty()) out.add(n) else constFns(n.a, out)
            is TN.Neg -> constFns(n.a, out)
            is TN.Sq -> constFns(n.a, out)
            is TN.Bin -> {
                constFns(n.a, out)
                constFns(n.b, out)
            }
            else -> {}
        }
    }

    private fun evalMode(c: Ctx, expr: String, kw: String?, variant: Int): QResult {
        val tn = parseTN(expr) ?: return c.result(problem = "I could not read the expression \"" + expr + "\". Try typing it like sin(30°) + cos(60°).")
        val vars = tvars(tn)
        c.steps.add("Expression: " + showTN(tn))
        c.think.add("Kind of question: find the value of a trigonometric expression")
        c.think.add("Trig functions seen: " + (Regex("sin|cos|tan|sec|csc|cot").findAll(showTN(tn)).map { it.value }.toSet().joinToString(", ").ifEmpty { "none" }))
        c.think.add("Angles are read in degrees (π counts as 180°)")
        if (vars.isEmpty()) {
            val v = tev(tn, 0.0)
            if (v.isNaN()) return c.result(problem = "That expression is not defined (division by zero or a tangent of 90°).")
            c.think.add("Method: use the exact values of the special angles 0°, 30°, 45°, 60°, 90°… then add up")
            val fns = mutableListOf<TN.Fn>()
            constFns(tn, fns)
            val seen = mutableSetOf<String>()
            c.board.add(showTN(tn))
            for (f in fns) {
                val label = showTN(f)
                if (!seen.add(label)) continue
                val fv = tev(f, 0.0)
                val line = label + " = " + (exactOf(fv) ?: ("≈ " + qf4(fv)))
                c.steps.add("  ✓ " + line)
                c.board.add(line)
            }
            val sub = showTN(tn, true)
            if (fns.isNotEmpty() && sub != showTN(tn)) c.board.add("= " + sub)
            val ex = exactOf(v)
            c.board.add("= " + (ex ?: ("≈ " + qf4(v))))
            c.steps.add("✅ Value worked out")
            c.ans.add("Value = " + (ex ?: ("≈ " + qf4(v))) + (if (ex != null && ex.contains("sqrt")) "  (≈ " + qf4(v) + ")" else ""))
            if (variant > 0) c.steps.add("I read it as: " + showTN(tn) + " — if that is not what you typed, add brackets and press Solve again.")
            return c.result()
        }
        if (vars.size > 1) return c.result(problem = "That has more than one letter (" + vars.joinToString(", ") + ") — give me one angle.")
        val x0 = vars.first()
        val f = { x: Double -> tev(tn, x) }
        var constant = true
        var first = Double.NaN
        for (k in 0 until 40) {
            val y = f(7.3 + 13.7 * k)
            if (y.isNaN() || y.isInfinite()) continue
            if (first.isNaN()) first = y else if (abs(y - first) > 1e-9 * (1 + abs(y))) constant = false
        }
        c.think.add("It has the letter " + x0 + " — checking whether it always gives the same number")
        if (constant && !first.isNaN()) {
            val ex = exactOf(first)
            c.board.add(showTN(tn))
            c.board.add("= " + (ex ?: qf4(first)) + "  (the same for every " + x0 + ")")
            c.steps.add("✅ Checked at 40 angles: always " + (ex ?: qf4(first)))
            c.ans.add("It simplifies to " + (ex ?: qf4(first)))
            return c.result()
        }
        c.steps.add("It depends on " + x0 + " — so there is no single value.")
        c.ans.add("Depends on " + x0 + ". Give a value of " + x0 + ", or make it an equation (… = number) and I will solve it.")
        return c.result()
    }

    // ------------------------------------------------------------------ equation or identity

    private fun fnOfVar(n: TN): TN.Fn? {
        if (n is TN.Fn && n.pw == 1.0 && n.a is TN.Var) return n
        return null
    }

    private fun eqMode(c: Ctx, eqText: String, prove: Boolean, loIn: Double?, hiIn: Double?, variant: Int): QResult {
        val parts = eqText.split("=")
        if (parts.size != 2) return c.result(problem = "I need one equation with a single = sign, like sin θ / cos θ = tan θ.")
        val l = parseTN(parts[0].trim()) ?: return c.result(problem = "I could not read the left side \"" + parts[0].trim() + "\".")
        val r = parseTN(parts[1].trim()) ?: return c.result(problem = "I could not read the right side \"" + parts[1].trim() + "\".")
        val vars = tvars(l, tvars(r))
        val ls = showTN(l)
        val rs = showTN(r)
        c.steps.add("Equation: " + ls + " = " + rs)
        if (vars.isEmpty()) {
            val a = tev(l, 0.0)
            val b = tev(r, 0.0)
            c.think.add("Kind of question: check a numeric statement")
            c.board.add(showTN(l, true) + " = " + (exactOf(a) ?: qf4(a)))
            c.board.add(showTN(r, true) + " = " + (exactOf(b) ?: qf4(b)))
            val eq = abs(a - b) < 1e-9
            c.steps.add(if (eq) "✅ Both sides are equal" else "✗ The sides are different")
            c.ans.add(if (eq) "True: both sides equal " + (exactOf(a) ?: qf4(a)) else "False: left = " + (exactOf(a) ?: qf4(a)) + ", right = " + (exactOf(b) ?: qf4(b)))
            return c.result()
        }
        if (vars.size > 1) return c.result(problem = "That has more than one letter (" + vars.joinToString(", ") + ") — I can handle one angle at a time.")
        val v = vars.first()
        c.think.add("Variable: " + v + " in degrees (π counts as 180°)")
        c.think.add("Trig functions seen: " + (Regex("sin|cos|tan|sec|csc|cot").findAll(ls + " " + rs).map { it.value }.toSet().joinToString(", ").ifEmpty { "none" }))
        val ids = namedIdentities(l, r)
        c.think.add("Compared both sides with " + KNOWN_IDS.size + " known identities — " + (if (ids.isEmpty()) "no direct match" else ids.size.toString() + " related"))
        c.think.add("Method 1: is it true for every " + v + "? (test at 60 angles)")
        val ident = isIdentity(l, r)
        c.think.add("Method 1 result: " + (if (ident) "yes — it is an identity" else "no — it is an equation"))
        val f = { x: Double -> tev(l, x) - tev(r, x) }
        if (ident) {
            c.board.add(ls + " = " + rs)
            c.board.add("Both sides agree at every angle tested, so it is an identity: true for every " + v + " where both sides exist")
            for (line in ids) c.board.add(line)
            c.steps.add("✅ Identity confirmed at 60 angles")
            for (line in ids) c.steps.add("  • " + line)
            c.ans.add("✓ " + ls + " = " + rs + " is an identity")
            c.ans.add("(Checked by numbers. A written algebra proof needs symbolic steps — next phase.)")
            return c.result()
        }
        // not an identity
        if (prove) {
            var shown = false
            for (k in 0 until 60) {
                val x = 7.3 + 13.7 * k
                val a = tev(l, x)
                val b = tev(r, x)
                if (!a.isNaN() && !b.isNaN() && abs(a - b) > 1e-6) {
                    c.board.add("Try " + v + " = " + qf(x) + "°: left = " + qf4(a) + ", right = " + qf4(b))
                    c.steps.add("✗ Counter-example at " + v + " = " + qf(x) + "°: left " + qf4(a) + " ≠ right " + qf4(b))
                    c.ans.add("✗ Not an identity — the two sides are different at " + v + " = " + qf(x) + "°")
                    shown = true
                    break
                }
            }
            if (!shown) c.ans.add("✗ Not an identity")
            c.steps.add("It may still hold for some angles — solving it as an equation:")
        }
        var lo = loIn ?: (if (variant >= 1) -360.0 else 0.0)
        var hi = hiIn ?: 360.0
        if (hi <= lo) {
            val t = lo
            lo = hi
            hi = t
        }
        val step = if (variant >= 2) 0.01 else 0.05
        c.think.add("Method 2: write L − R = 0 and look for every sign change between " + degText(lo) + " and " + degText(hi))
        val fl = fnOfVar(l)
        val fr = fnOfVar(r)
        val oneFn = (fl != null && tvars(r).isEmpty()) || (fr != null && tvars(l).isEmpty())
        c.think.add("Method 3: principal value + symmetry (needs one function = a number): " + (if (oneFn) "✓ applies" else "– not this shape"))
        c.board.add(ls + " = " + rs)
        c.board.add(ls + " - (" + rs + ") = 0")
        if (oneFn) {
            val fn = fl ?: fr!!
            val cv = tev(if (fl != null) r else l, 0.0)
            val cs = exactOf(cv) ?: qf4(cv)
            when (fn.name) {
                "sin", "cos" -> {
                    if (abs(cv) <= 1.0 + 1e-12) {
                        val a = applyFn(if (fn.name == "sin") "asin" else "acos", cv)
                        c.board.add(fn.name + "(" + v + ") = " + cs + "  ⇒  " + v + " = " + (if (fn.name == "sin") "arcsin" else "arccos") + "(" + cs + ") = " + degText(a) + "  (principal value)")
                        if (fn.name == "sin") c.board.add("Sine is also positive/negative in the other quadrant: " + v + " = 180° - " + degText(a) + " = " + degText(180 - a))
                        else c.board.add("Cosine is symmetric: " + v + " = -" + degText(a) + " or 360° - " + degText(a) + " = " + degText(360 - a))
                    } else {
                        c.board.add(fn.name + "(" + v + ") = " + cs + " is impossible, because " + fn.name + " is always between -1 and 1")
                    }
                }
                "tan", "cot" -> {
                    val a = applyFn("atan", if (fn.name == "tan") cv else 1 / cv)
                    c.board.add(fn.name + "(" + v + ") = " + cs + "  ⇒  " + v + " = " + degText(a) + "  (principal value), then every 180°")
                }
                else -> c.board.add(fn.name + "(" + v + ") = " + cs + " — flip it to " + (if (fn.name == "sec") "cos" else "sin") + "(" + v + ") = 1/(" + cs + ")")
            }
        }
        val roots = scanRoots(f, lo, hi, step)
        c.steps.add("🔎 Scanning " + degText(lo) + " to " + degText(hi) + " in steps of " + step + "° …")
        val good = mutableListOf<Double>()
        for (x in roots) {
            val a = tev(l, x)
            val b = tev(r, x)
            if (!a.isNaN() && !b.isNaN() && abs(a - b) < 1e-6 * (1 + abs(a))) {
                good.add(x)
                c.steps.add("  ✓ " + v + " = " + degText(x) + ": left = " + (exactOf(a) ?: qf4(a)) + ", right = " + (exactOf(b) ?: qf4(b)))
            } else {
                c.steps.add("  ✗ " + v + " = " + degText(x) + " fails the check — dropped")
            }
        }
        if (good.isEmpty()) {
            c.board.add("No solution between " + degText(lo) + " and " + degText(hi))
            c.ans.add("No solution in " + degText(lo) + " … " + degText(hi))
            c.steps.add("✅ Scan finished: none")
            return c.result()
        }
        c.board.add("Solutions from " + degText(lo) + " to " + degText(hi) + ":  " + v + " = " + good.joinToString(", ") { degText(it) })
        val period = if (repeatsEvery(f, 180.0)) 180.0 else if (repeatsEvery(f, 360.0)) 360.0 else 0.0
        if (period > 0.0 && loIn == null) {
            val base = good.map { ((it % period) + period) % period }.map { Math.round(it * 1e6) / 1e6 }.distinct().sorted()
            val gen = base.joinToString("  or  ") { degText(it) + " + " + qf(period) + "°n" }
            c.board.add("General solution:  " + v + " = " + gen + "   (n = any whole number)")
            c.ans.add("General: " + v + " = " + gen)
        }
        c.ans.add(0, v + " = " + good.joinToString(", ") { degText(it) } + "  (" + degText(lo) + " ≤ " + v + " ≤ " + degText(hi) + ")")
        c.steps.add("✅ " + good.size + " solution" + (if (good.size == 1) "" else "s") + " found and checked")
        if (variant >= 1 && loIn == null) c.steps.add("(Rechecked over −360° to 360°. Tell me the range if the question gives one, e.g. for 0° ≤ θ ≤ 180°.)")
        return c.result()
    }

    // ------------------------------------------------------------------ "if sin θ = 3/5, find cos θ and tan θ"

    private val DEF = mapOf(
        "sin" to "opposite/hypotenuse", "cos" to "adjacent/hypotenuse", "tan" to "opposite/adjacent",
        "csc" to "hypotenuse/opposite", "sec" to "hypotenuse/adjacent", "cot" to "adjacent/opposite",
    )

    private fun canon(w: String): String {
        val s = w.lowercase()
        return if (s == "cosec") "csc" else s
    }

    private fun ratioMode(c: Ctx, given: String, goals: String, fullText: String, variant: Int): QResult {
        val parts = given.split("=")
        if (parts.size != 2) return c.result(problem = "I need the given as one equation, like sin θ = 3/5.")
        var l = parseTN(parts[0].trim()) ?: return c.result(problem = "I could not read \"" + parts[0].trim() + "\".")
        var r = parseTN(parts[1].trim()) ?: return c.result(problem = "I could not read \"" + parts[1].trim() + "\".")
        if (fnOfVar(l) == null && fnOfVar(r) != null) {
            val t = l
            l = r
            r = t
        }
        val fn = fnOfVar(l) ?: return c.result(problem = "The given should look like sin θ = 3/5 (one ratio of the angle equal to a number).")
        if (tvars(r).isNotEmpty()) return c.result(problem = "The right side should be a number like 3/5.")
        val vch = (fn.a as TN.Var).name
        val cv = tev(r, 0.0)
        if (cv.isNaN()) return c.result(problem = "That number is not defined.")
        val gs = fn.name + "(" + vch + ") = " + (exactOf(cv) ?: qf4(cv))
        c.steps.add("Given: " + gs)
        c.board.add("Given: " + gs)
        c.think.add("Kind of question: one trig ratio is known, find the others")
        c.think.add("Method: place the angle in a right triangle (SOH-CAH-TOA), use Pythagoras for the third side")
        // which quadrant
        val low = fullText.lowercase()
        val fixed = when {
            low.contains("acute") || low.contains("first quadrant") || Regex("0\\s*°?\\s*<\\s*[θαβφxt]\\s*<\\s*90").containsMatchIn(low) -> 0
            low.contains("obtuse") || low.contains("second quadrant") -> 1
            low.contains("third quadrant") -> 2
            low.contains("fourth quadrant") -> 3
            else -> -1
        }
        val order = if (fixed >= 0) listOf(fixed) else (0..3).map { (it + variant) % 4 }
        c.think.add("Quadrant: " + (if (fixed >= 0) "the question says it" else "not stated — I start with the first (acute) and try the others if you press ✗"))
        var theta = Double.NaN
        var quad = -1
        for (q in order) {
            val roots = scanRoots({ x -> tev(l, x) - cv }, 90.0 * q, 90.0 * (q + 1), 0.05)
            if (roots.isNotEmpty()) {
                theta = roots.first()
                quad = q
                break
            }
        }
        if (theta.isNaN()) return c.result(problem = "No angle has " + gs + (if (fixed >= 0) " in that quadrant." else "."))
        val qn = listOf("first", "second", "third", "fourth")[quad]
        c.steps.add("θ is in the " + qn + " quadrant" + (if (fixed < 0 && variant == 0) " (assumed acute)" else ""))
        c.board.add("θ is in the " + qn + " quadrant" + (if (quad == 0) " (acute)" else ""))
        // which ratios to give
        val gExpr = parseTN(goals.trim())
        val exprGoal = gExpr != null && tvars(gExpr) == setOf(vch) && gExpr !is TN.Fn
        val want = mutableListOf<String>()
        if (!exprGoal) {
            for (m in fnWord.findAll(goals)) {
                val n = canon(m.value)
                if (n !in want) want.add(n)
            }
            val wantsAll = Regex("(?i)all\\s+(?:the\\s+)?(?:other\\s+)?(?:trigonometric\\s+)?(?:ratios|functions)|other\\s+ratios").containsMatchIn(goals)
            if (wantsAll || (want.isEmpty() && !askVar.containsMatchIn(goals.trim()))) {
                for (n in listOf("sin", "cos", "tan", "csc", "sec", "cot")) if (n != fn.name && n !in want) want.add(n)
            }
        }
        var plan: QPlan? = null
        val rt = ratioOf(cv)
        val fnName = fn.name
        if (quad == 0 && rt != null && rt.first > 0 && rt.second > 0) {
            val p = rt.first.toDouble()
            val q = rt.second.toDouble()
            var opp = Double.NaN
            var adj = Double.NaN
            var hyp = Double.NaN
            when (fnName) {
                "sin" -> if (p < q) { opp = p; hyp = q; adj = sqrt(q * q - p * p) }
                "cos" -> if (p < q) { adj = p; hyp = q; opp = sqrt(q * q - p * p) }
                "tan" -> { opp = p; adj = q; hyp = sqrt(p * p + q * q) }
                "cot" -> { adj = p; opp = q; hyp = sqrt(p * p + q * q) }
                "csc" -> if (p > q) { hyp = p; opp = q; adj = sqrt(p * p - q * q) }
                "sec" -> if (p > q) { hyp = p; adj = q; opp = sqrt(p * p - q * q) }
            }
            if (!opp.isNaN() && !adj.isNaN() && !hyp.isNaN()) {
                c.board.add(fnName + "(θ) = " + DEF[fnName] + "  ⇒  from " + qf(p) + "/" + qf(q))
                val known = when (fnName) {
                    "sin" -> "opposite = " + qf(opp) + ", hypotenuse = " + qf(hyp)
                    "cos" -> "adjacent = " + qf(adj) + ", hypotenuse = " + qf(hyp)
                    "tan" -> "opposite = " + qf(opp) + ", adjacent = " + qf(adj)
                    "cot" -> "adjacent = " + qf(adj) + ", opposite = " + qf(opp)
                    "csc" -> "hypotenuse = " + qf(hyp) + ", opposite = " + qf(opp)
                    else -> "hypotenuse = " + qf(hyp) + ", adjacent = " + qf(adj)
                }
                c.board.add("Right triangle with " + known)
                when (fnName) {
                    "sin", "cos", "csc", "sec" -> {
                        val shortName = if (fnName == "sin" || fnName == "csc") "adjacent" else "opposite"
                        val big = if (fnName == "sin" || fnName == "csc") adj else opp
                        val h = hyp
                        val other = if (fnName == "sin") opp else if (fnName == "cos") adj else if (fnName == "csc") opp else adj
                        c.board.add(shortName + "^2 = " + qf(h) + "^2 - " + qf(other) + "^2 = " + qf(h * h - other * other) + "  ⇒  " + shortName + " = " + (exactOf(big) ?: qf4(big)))
                    }
                    else -> c.board.add("hypotenuse^2 = " + qf(opp) + "^2 + " + qf(adj) + "^2 = " + qf(opp * opp + adj * adj) + "  ⇒  hypotenuse = " + (exactOf(hyp) ?: qf4(hyp)))
                }
                val nice = listOf(opp, adj, hyp).all { abs(it - Math.round(it).toDouble()) < 1e-9 }
                plan = QPlan(
                    "ABC",
                    listOf(Pt(0.0, 0.0), Pt(adj, 0.0), Pt(adj, opp)),
                    if (nice) setOf(0, 1, 2) else emptySet(),
                    setOf(0, 1),
                    emptySet(),
                    "right triangle for " + gs,
                )
                c.steps.add("✏️ Right triangle drawn: angle θ at A, right angle at B")
            }
        }
        if (plan == null && quad == 0) {
            val s = sin(theta)
            val co = cosd(theta)
            plan = QPlan("ABC", listOf(Pt(0.0, 0.0), Pt(6 * co, 0.0), Pt(6 * co, 6 * s)), emptySet(), setOf(0, 1), emptySet(), "right triangle for " + gs)
        }
        if (plan == null) c.board.add("θ = " + degText(theta) + " (found from " + gs + ")")
        if (want.isEmpty()) c.board.add("θ = " + degText(theta))
        for (n in want) {
            val v = applyFn(n, theta)
            val ex = exactOf(v) ?: ("≈ " + qf4(v))
            val sign = if (quad != 0 && v < 0) "  (negative in the " + qn + " quadrant)" else ""
            val line = n + "(θ) = " + (if (quad == 0 && rt != null && plan != null) DEF[n] + " = " else "") + ex + sign
            c.board.add(line)
            c.ans.add(n + "(θ) = " + ex)
            // the drone: recompute straight from the angle
            c.steps.add("  ✓ check: " + n + "(" + degText(theta) + ") = " + qf4(v))
        }
        val s2 = applyFn("sin", theta)
        val c2 = applyFn("cos", theta)
        c.steps.add("  ✓ check: sin² + cos² = " + qf4(s2 * s2 + c2 * c2))
        if (askVar.containsMatchIn(goals) || want.isEmpty()) c.ans.add("θ = " + degText(theta))
        if (exprGoal && gExpr != null) {
            val gv = tev(gExpr, theta)
            val line = showTN(gExpr) + " = " + (exactOf(gv) ?: qf4(gv))
            c.board.add(line)
            c.ans.add(line)
        }
        c.steps.add("✅ Done")
        if (quad == 0 && variant == 0) c.ans.add("(θ taken as acute — press ✗ to try the other quadrants)")
        return c.result(plan)
    }

    private fun sin(d: Double) = kotlin.math.sin(d * Math.PI / 180)
    private fun cosd(d: Double) = kotlin.math.cos(d * Math.PI / 180)
}
