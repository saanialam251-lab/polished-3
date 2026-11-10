package com.aismartmath.board.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aismartmath.board.ui.components.Chip
import com.aismartmath.board.ui.components.glass
import com.aismartmath.board.ui.theme.BoardColors

private class Kind(val id: String, val icon: String, val name: String, val fields: List<Triple<String, String, String>>)

private val KINDS = listOf(
    Kind("tri", "△", "Triangle", listOf(Triple("a", "Side a", "3"), Triple("b", "Side b", "4"), Triple("c", "Side c", "5"))),
    Kind("rtri", "◺", "Right △", listOf(Triple("b", "Base", "3"), Triple("h", "Height", "4"))),
    Kind("rect", "▭", "Rectangle", listOf(Triple("w", "Width", "4"), Triple("h", "Height", "3"))),
    Kind("sq", "◻", "Square", listOf(Triple("s", "Side", "4"))),
    Kind("quad", "▱", "Quadrilateral", listOf(Triple("ab", "AB", "4"), Triple("bc", "BC", "3"), Triple("cd", "CD", "4"), Triple("da", "DA", "3"), Triple("ac", "Diag AC", "5"))),
    Kind("pent", "⬠", "Pentagon", listOf(Triple("s", "Side", "3"))),
    Kind("hex", "⬡", "Hexagon", listOf(Triple("s", "Side", "3"))),
    Kind("circ", "○", "Circle", listOf(Triple("r", "Radius", "3"))),
    Kind("cube", "🧊", "Cube", listOf(Triple("a", "Side", "3"))),
    Kind("cuboid", "▬", "Cuboid", listOf(Triple("l", "Length", "4"), Triple("w", "Width", "3"), Triple("h", "Height", "2"))),
    Kind("pyr", "🔺", "Pyramid", listOf(Triple("l", "Base length", "4"), Triple("w", "Base width", "4"), Triple("h", "Height", "3"))),
    Kind("prism", "⏢", "Prism", listOf(Triple("l", "Base", "4"), Triple("h", "Tri. height", "3"), Triple("w", "Length", "5"))),
    Kind("cyl", "🥫", "Cylinder", listOf(Triple("r", "Radius", "2"), Triple("h", "Height", "5"))),
    Kind("cone", "🍦", "Cone", listOf(Triple("r", "Radius", "2"), Triple("h", "Height", "4"))),
    Kind("sph", "⚽", "Sphere", listOf(Triple("r", "Radius", "3"))),
    Kind("graph", "ƒ", "Graph", emptyList()),
)

@Composable
private fun NumBox(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.width(58.dp)) {
        Text(label, color = BoardColors.Muted, fontSize = 8.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(Color(0xFFD08A4E)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF160E0A))
                .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6B4630)), RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 3.dp),
        )
    }
}

/**
 * Answer tab: pick a figure (triangle, quadrilateral, cylinder, graph...), type its sizes, and it is drawn at exactly
 * those sizes with its labels and numbers. Afterwards every tool (pen, highlighter, text...) works on it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnswerPanel(state: BoardState) {
    val acc = BoardColors.Indigo
    var kind by remember { mutableStateOf<Kind?>(null) }
    val vals = remember(kind) {
        mutableStateMapOf<String, String>().apply { kind?.fields?.forEach { put(it.first, it.third) } }
    }
    var eqText by remember { mutableStateOf("x^2") }
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp), acc, 8.dp).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val k = kind
        if (k == null) {
            Text("🧮 Answer — pick a figure", color = Color.White, fontSize = 10.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KINDS.forEach { kd ->
                    Chip(kd.icon + " " + kd.name, false, acc, small = true) { kind = kd }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(k.icon + " " + k.name + (if (k.id == "graph") " — which equation?" else " — its sizes"), color = Color.White, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Chip("‹ Back", false, acc, small = true) { kind = null }
            }
            if (k.id == "graph") {
                BasicTextField(
                    value = eqText,
                    onValueChange = { eqText = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush = SolidColor(Color(0xFFD08A4E)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF160E0A))
                        .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6B4630)), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("x^2", "x^3", "sin(x)", "cos(x)", "sqrt(x)", "1/x", "2*x+1", "abs(x)", "e^x", "ln(x)").forEach { e ->
                        Chip(e, eqText == e, acc, small = true) { eqText = e }
                    }
                    Chip("Plot ✓", true, acc, small = true) {
                        state.eq = eqText
                        state.plot()
                        state.selectTool("fx")
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    k.fields.forEach { (key, label, _) ->
                        NumBox(label, vals[key] ?: "") { vals[key] = it }
                    }
                }
                Chip("Draw ✓", true, acc, small = true) {
                    val m = k.fields.associate { (key, _, _) -> key to (vals[key]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0) }
                    if (state.makeAnswer(k.id, m)) kind = null
                }
            }
        }
    }
}
