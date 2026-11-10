package com.aismartmath.board.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aismartmath.board.math.hasMath
import com.aismartmath.board.ui.components.Chip
import com.aismartmath.board.ui.components.MathTextView
import com.aismartmath.board.ui.components.glass
import com.aismartmath.board.ui.theme.BoardColors
import kotlinx.coroutines.delay

private val EXAMPLES = listOf(
    "Isosceles" to "In isosceles triangle ABC, AB = AC and ∠A = 40°. Find ∠B and ∠C.",
    "Right △" to "In right-angled triangle PQR, right angle at Q, PQ = 3 cm and QR = 4 cm. Find PR and ∠P.",
    "Find x" to "The angles of a triangle are (2x + 10)°, 3x° and (x + 20)°. Find x.",
    "Area, R, r" to "In triangle ABC, AB = 13, BC = 14 and CA = 15. Find the area, the circumradius and the inradius.",
    "Point D on BC" to "In triangle ABC, AB = AC. D is a point on BC and ∠B = 40°. Find ∠ADC.",
    "Bisector" to "In triangle ABC, ∠B = 50° and ∠C = 60°. AD bisects ∠A and D is a point on BC. Find ∠ADC.",
    "Crossing, x" to "AB and CD intersect at O. ∠AOC = 3x° and ∠BOC = (2x + 10)°. Find x and ∠BOD.",
    "Straight line, x" to "AOB is a straight line. ∠AOC = 3x°, ∠COD = 2x° and ∠DOB = (x + 30)°. Find x.",
    "Parallel lines" to "AB ∥ CD and EF cuts AB at P and CD at Q. ∠BPE = 70°. Find ∠DQP and ∠CQP.",
    "Pythagoras, x" to "In right-angled triangle ABC, right angle at B, AB = x, BC = x + 7 and AC = 13. Find x.",
    "SSS proof" to "In triangles ABC and DEF, AB = DE, BC = EF and CA = FD. Prove that triangle ABC ≅ triangle DEF.",
    "Midpoint" to "In triangle ABC, AB = AC. D is the midpoint of BC. Prove that triangle ABD ≅ triangle ACD.",
    "RHS proof" to "In triangle ABC, AB = AC and AD ⊥ BC where D lies on BC. Prove that triangle ABD ≅ triangle ACD.",
    "Crossing" to "AD and BC bisect each other at O. Prove that triangle AOB ≅ triangle DOC.",
    "Add segments" to "Points A, B, C and D lie on a line in this order. AB = CD. Prove that AC = BD.",
    "Parallelogram" to "ABCD is a parallelogram. Prove that triangle ABC ≅ triangle CDA.",
    "∠B = ∠C" to "In triangle ABC, AB = AC. Prove that ∠B = ∠C.",
    "AB = AC" to "In triangle ABC, ∠B = ∠C. Prove that AB = AC.",
)

@Composable
private fun StepLine(s: String, color: Color, size: TextUnit = 11.sp, italic: Boolean = false) {
    if (hasMath(s)) {
        MathTextView(s, size, color, Modifier.fillMaxWidth())
    } else {
        Text(s, color = color, fontSize = size, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal)
    }
}

/**
 * Question tab. The person types or pastes a question (shown as maths while typing). The board then thinks (the
 * possibilities it weighs), reads, solves, draws the figure with the app's own tools, checks it, writes the solution on
 * the board line by line — and asks ✓ right or ✗ wrong. ✗ makes it look again with different assumptions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionPanel(state: BoardState) {
    val acc = BoardColors.Indigo
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<QResult?>(null) }
    var variant by remember { mutableIntStateOf(0) }
    var runId by remember { mutableIntStateOf(0) }
    var thoughts by remember { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf("idle") } // idle, think, work, write, done, kept
    val scroll = rememberScrollState()

    LaunchedEffect(runId) {
        val r = result
        if (r == null) return@LaunchedEffect
        thoughts = 0
        shown = 0
        // 1. thinking: the possibilities the engine weighed (never faster than about two seconds)
        phase = "think"
        val t0 = System.currentTimeMillis()
        for (i in r.thinking.indices) {
            thoughts = i + 1
            delay(290L)
        }
        val spent = System.currentTimeMillis() - t0
        if (spent < 2200L) delay(2200L - spent)
        // 2. reading, solving, drawing, checking
        phase = "work"
        var began = false
        for (i in r.steps.indices) {
            val s = r.steps[i]
            shown = i + 1
            if (!began && r.problem == null && s.startsWith("✏️")) {
                state.beginQuestion(r.plan)
                began = true
            }
            delay(if (s.startsWith("  ")) 110L else 260L)
        }
        if (r.problem != null) {
            phase = "done"
            return@LaunchedEffect
        }
        if (!began) state.beginQuestion(r.plan)
        // 3. write the solution on the board, one line at a time
        phase = "write"
        for (line in r.board) {
            state.writeSolution(line)
            delay(if (line.length > 60) 900L else 650L)
        }
        phase = "done"
    }

    LaunchedEffect(thoughts, shown) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    fun solveNow(v: Int) {
        variant = v
        result = QuestionSolver.solve(text, v)
        runId++
    }

    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp), acc, 8.dp).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("📝 Question — type or paste it; it is shown as maths while you type", color = Color.White, fontSize = 10.sp)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(Color(0xFFD08A4E)),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("e.g. In triangle ABC … or  sin θ / cos θ = tan θ", color = Color(0xFF9C8470), fontSize = 13.sp)
                inner()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp, max = 90.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF160E0A))
                .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6B4630)), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp),
        )
        if (text.isNotBlank()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF160E0A))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) { MathTextView(text, 14.sp, Color(0xFFFFE2B8), Modifier.fillMaxWidth()) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("Solve ✓", true, acc, small = true) { solveNow(0) }
            Chip("Clear", false, acc, small = true) {
                text = ""
                result = null
                variant = 0
                phase = "idle"
                thoughts = 0
                shown = 0
                state.clearQuestionDrawing()
                runId++
            }
            EXAMPLES.forEach { (label, q) ->
                Chip(label, false, acc, small = true) { text = q }
            }
        }
        val r = result
        if (r != null && phase != "idle") {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (r.thinking.isNotEmpty()) {
                    val dots = ".".repeat(1 + (thoughts % 3))
                    Text(
                        if (phase == "think") "🧠 Thinking$dots" else "🧠 Thought about",
                        color = Color(0xFFD9B88F),
                        fontSize = 11.sp,
                    )
                    for (i in 0 until minOf(thoughts, r.thinking.size)) {
                        val s = r.thinking[i]
                        val c = if (s.startsWith("✓")) Color(0xFF9BD08A) else BoardColors.Muted
                        StepLine(s, c, 10.sp, italic = true)
                    }
                }
                for (i in 0 until minOf(shown, r.steps.size)) {
                    val s = r.steps[i]
                    val c = when {
                        s.startsWith("✓") || s.startsWith("  ✓") || s.startsWith("✅") -> Color(0xFF9BD08A)
                        s.startsWith("✗") || s.startsWith("  ✗") || s.startsWith("⚠") -> Color(0xFFE59A7A)
                        s.startsWith("  ") -> BoardColors.Muted
                        else -> Color.White
                    }
                    StepLine(s, c)
                }
                val prob = r.problem
                if (prob != null && phase == "done") {
                    Text("❌ " + prob, color = Color(0xFFE59A7A), fontSize = 12.sp)
                }
                if (phase == "write") Text("✍️ Writing the solution on the board…", color = Color(0xFFD9B88F), fontSize = 11.sp)
            }
            if (phase == "done" || phase == "kept") {
                r.answers.forEach { a ->
                    StepLine(a, BoardColors.Teal, 13.sp)
                }
            }
            if (phase == "done" && r.problem == null) {
                Text("Is the figure and the answer right?", color = Color.White, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Chip("✓ Right", true, acc, small = false) {
                        state.keepQuestionDrawing()
                        phase = "kept"
                    }
                    Chip("✗ Wrong — check again", false, acc, small = false) {
                        solveNow(variant + 1)
                    }
                }
            }
            if (phase == "done" && r.problem != null) {
                Chip("Try another reading", false, acc, small = true) { solveNow(variant + 1) }
            }
            if (phase == "kept") {
                Text("✓ Kept on the board. Press Solve for the next question.", color = Color(0xFF9BD08A), fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}
