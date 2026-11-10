package com.aismartmath.board.board

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import com.aismartmath.board.data.ImageStore
import com.aismartmath.board.math.center
import com.aismartmath.board.math.derivativeRequest
import com.aismartmath.board.math.texToUnicode
import com.aismartmath.board.math.centerOf
import com.aismartmath.board.math.dist
import com.aismartmath.board.math.dseg
import com.aismartmath.board.math.figOk
import com.aismartmath.board.math.inPoly
import com.aismartmath.board.math.parseEq
import com.aismartmath.board.math.polyArea
import com.aismartmath.board.math.pts
import com.aismartmath.board.math.rectOf
import com.aismartmath.board.math.SOLD
import com.aismartmath.board.math.SOLN
import com.aismartmath.board.math.SOLT
import com.aismartmath.board.math.regularPolygon
import com.aismartmath.board.math.solveQuad
import com.aismartmath.board.math.solveTri
import com.aismartmath.board.model.Fig
import com.aismartmath.board.math.MathCache
import com.aismartmath.board.model.GraphDef
import com.aismartmath.board.model.Pt
import com.aismartmath.board.model.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Web: LB - a different letter set for every new shape (six letters so pentagons and hexagons fit). */
val LB = listOf("ABCDEF", "PQRSTU", "XYZWVU", "LMNOPQ", "EFGHIJ", "STUVWX", "JKLMNO")
private val POLY = setOf("tri", "quad", "rect", "rtri", "pent", "hex")
private val NEED = mapOf("pt" to 1, "tri" to 3, "quad" to 4, "pent" to 5, "hex" to 6)

/** What was found on the phone's clipboard. */
sealed interface ClipItem {
    class Words(val s: String) : ClipItem
    class Picture(val bmp: Bitmap) : ClipItem
}

/** Something a figure can be hidden behind: the labels that are off for a brand-new figure. */
fun defaultHide(f: Fig): Set<String> = when (f.t) {
    "seg" -> setOf("len")
    "tri", "quad", "rect", "rtri", "pent", "hex" -> {
        val n = pts(f).size
        ((0 until n).map { "s$it" } + (0 until n).map { "a$it" }).toSet()
    }
    else -> emptySet()
}

/** Smallest box (minX, minY, maxX, maxY in board units) round a figure, or null when it has no place to select. */
fun figBounds(f: Fig): DoubleArray? {
    val q: List<Pt> = when (f.t) {
        "fx", "unit" -> return null
        "ink", "img", "txt" -> f.p
        "circ" -> {
            val r = dist(f.p[0], f.p[1])
            return doubleArrayOf(f.p[0].x - r, f.p[0].y - r, f.p[0].x + r, f.p[0].y + r)
        }
        "sol" -> {
            val r = (f.sd.values.maxOrNull() ?: 2.0).coerceAtLeast(1.0)
            return doubleArrayOf(f.p[0].x - r, f.p[0].y - r, f.p[0].x + r, f.p[0].y + r)
        }
        else -> pts(f)
    }
    if (q.isEmpty()) return null
    return doubleArrayOf(q.minOf { it.x }, q.minOf { it.y }, q.maxOf { it.x }, q.maxOf { it.y })
}

private fun samplesOf(f: Fig): List<Pt> = when (f.t) {
    "img", "txt" -> {
        val a = f.p[0]
        val b = f.p[1]
        listOf(a, Pt(b.x, a.y), b, Pt(a.x, b.y), Pt((a.x + b.x) / 2, (a.y + b.y) / 2))
    }
    "circ" -> {
        val r = dist(f.p[0], f.p[1])
        val c = f.p[0]
        listOf(c, Pt(c.x + r, c.y), Pt(c.x - r, c.y), Pt(c.x, c.y + r), Pt(c.x, c.y - r))
    }
    "sol" -> listOf(f.p[0])
    "ink" -> f.p
    else -> pts(f)
}

private class GOrig(val f: Fig, val p: List<Pt>, val sd: Map<String, Double>, val gb: Double, val fsz: Double)

/** The point on a graph whose tangent is being shown. */
class Trace(val f: Fig, val x: Double)

class Preset(val name: String, val eq: String, val a: Double, val b: Double, val c: Double, val group: String = "Graphs")

/** Web: PRE */
val PRESETS = listOf(
    Preset("Line", "a*x+b", 1.0, 0.0, 0.0),
    Preset("Parabola", "a*x^2+b*x+c", 1.0, 0.0, 0.0),
    Preset("Cubic", "a*x^3+b*x^2+c*x", 1.0, 0.0, 0.0),
    Preset("Sine", "a*sin(b*x+c)", 1.0, 1.0, 0.0),
    Preset("1/x", "a/x", 1.0, 0.0, 0.0),
    Preset("√x", "a*sqrt(x)+b", 1.0, 0.0, 0.0),
    Preset("Circle", "x^2+y^2=a", 9.0, 0.0, 0.0),
    Preset("Ellipse", "x^2/a+y^2/b=1", 9.0, 4.0, 0.0),
    Preset("Hyperbola", "x^2/a-y^2/b=1", 4.0, 4.0, 0.0),
    Preset("y²=ax", "y^2=a*x", 4.0, 0.0, 0.0),
    Preset("Square", "max(abs(x),abs(y))=a", 3.0, 0.0, 0.0, "Shapes by equation"),
    Preset("Rectangle", "max(abs(x)/a,abs(y)/b)=1", 4.0, 2.0, 0.0, "Shapes by equation"),
    Preset("Rhombus", "abs(x)/a+abs(y)/b=1", 4.0, 2.0, 0.0, "Shapes by equation"),
    Preset("Triangle", "max(max(2*b*x+a*y-a*b,-2*b*x+a*y-a*b),-y-b)=0", 3.0, 2.0, 0.0, "Shapes by equation"),
    Preset("Circle t", "(a*cos(t),a*sin(t))", 3.0, 0.0, 0.0, "Curves"),
    Preset("Rose", "r=a*sin(b*theta)", 4.0, 3.0, 0.0, "Curves"),
    Preset("Spiral", "r=a*theta", 0.3, 0.0, 0.0, "Curves"),
    Preset("Heart", "(4*sin(t)^3,3.25*cos(t)-1.25*cos(2*t)-0.5*cos(3*t)-0.25*cos(4*t))", 1.0, 0.0, 0.0, "Curves"),
    Preset("Lissajous", "(a*sin(b*t),a*sin(c*t))", 4.0, 3.0, 2.0, "Curves"),
    Preset("dy/dx x³", "dy/dx of x^3-3*x", 1.0, 0.0, 0.0, "Calculus"),
    Preset("dy/dx sin", "dy/dx of sin(x)", 1.0, 0.0, 0.0, "Calculus"),
    Preset("d²y/dx²", "d2y/dx2 of x^3-3*x", 1.0, 0.0, 0.0, "Calculus"),
)

/** A layer of the board: figures on it can be hidden, locked or made see-through together. */
class Layer(val id: Int, name0: String) {
    var name by mutableStateOf(name0)
    var visible by mutableStateOf(true)
    var locked by mutableStateOf(false)
    var opacity by mutableFloatStateOf(1f)
}

data class MenuItem(val key: String, val label: String, val icon: String)
data class MenuState(val x: Float, val y: Float, val items: List<MenuItem>, val at: Pt)

/** Which phase brings each tool to life. */
fun toolPhase(id: String): Int = when (id) {
    "pt", "seg", "tri", "rtri", "quad", "pent", "hex", "rect", "circ", "sel", "pan" -> 3
    "fx", "dv" -> 4
    "sk", "pen", "hl", "er" -> 5
    "trig", "unit" -> 6
    else -> 7
}

fun hcount(f: Fig): Int = when (f.t) {
    "pt" -> 1
    "seg", "circ", "rect", "rtri" -> 2
    "tri", "quad", "pent", "hex" -> f.p.size
    "img", "txt" -> 2
    else -> 0
}

class BoardState(val topic: Topic, private val scope: CoroutineScope) {
    val camera = Camera()
    var tool by mutableStateOf(topic.toolIds.first())
    val shapes = mutableStateListOf<Fig>()
    val pend = mutableStateListOf<Pt>()

    var cursor by mutableStateOf<Pt?>(null)
    var draft by mutableStateOf<Fig?>(null)
    var msg by mutableStateOf("")

    var clock by mutableLongStateOf(0L)
    var animTick by mutableIntStateOf(0)

    var sel by mutableStateOf<Fig?>(null)
    var editing by mutableStateOf<Fig?>(null)
    var mv by mutableStateOf<Fig?>(null)
    var rs by mutableStateOf<Fig?>(null)
    var detailsOn by mutableStateOf(false)
    var labelsOpen by mutableStateOf(false)
    var clip by mutableStateOf<Fig?>(null)

    // ---------- copied figures (one or a whole box of them), pictures, text ----------
    var clipGroup by mutableStateOf<List<Fig>>(emptyList())
    val hasClip: Boolean get() = clipGroup.isNotEmpty()
    private var internalStamp = 0L

    /** Hooks the screen fills in: read the phone clipboard, open the picture chooser, tell when the clipboard last changed. */
    var readClipboard: () -> ClipItem? = { null }
    var clipStamp: () -> Long = { 0L }
    var pickImage: () -> Unit = {}

    // ---------- sketch tool: drag a box round things to select them ----------
    var skDrag by mutableStateOf(true)
    /** Select tool: false = pick one figure (default), true = draw a box round several. */
    var selContent by mutableStateOf(false)
    val boxMode: Boolean get() = (tool == "sk" && skDrag) || (tool == "sel" && selContent)

    /** What the box caught, waiting for the tick / cross. */
    var pending by mutableStateOf<List<Fig>>(emptyList())
    var pendRect by mutableStateOf<DoubleArray?>(null)

    var textDialog by mutableStateOf(false)
    var forceAxes by mutableStateOf(false)
    var parIdx by mutableIntStateOf(0)
    var group by mutableStateOf<List<Fig>>(emptyList())
    var boxA by mutableStateOf<Offset?>(null)
    var boxB by mutableStateOf<Offset?>(null)
    private var gOrig: List<GOrig> = emptyList()
    private var gBefore: List<FigSnap> = emptyList()
    private var gStart = Pt(0.0, 0.0)
    private var gAnchor = Pt(0.0, 0.0)
    private var gD0 = 1.0

    // ---------- option boxes of the pen, highlighter and eraser ----------
    var toolMenu by mutableStateOf<String?>(null)
    var helpOpen by mutableStateOf(false)
    var helpTopic by mutableStateOf("graph")
    var penOpts by mutableStateOf(false)
    var eraserOpts by mutableStateOf(false)

    // ---------- picture cropping ----------
    var cropping by mutableStateOf<Fig?>(null)
    private var blankJob: Job? = null
    private var blankCount = 0
    private var blankAt = Offset.Zero
    var menu by mutableStateOf<MenuState?>(null)
    var toastMsg by mutableStateOf<String?>(null)

    // ---------- board identity and layers ----------
    var boardName by mutableStateOf("")
    val layers = mutableStateListOf(Layer(0, "Layer 1"))
    var activeLayer by mutableIntStateOf(0)
    var layersOpen by mutableStateOf(false)
    private var nextLayerId = 1

    fun resetLayerCounter() {
        nextLayerId = (layers.maxOfOrNull { it.id } ?: 0) + 1
    }

    fun layerOf(f: Fig): Layer? = layers.firstOrNull { it.id == f.layer }
    fun figVisible(f: Fig): Boolean = layerOf(f)?.visible != false
    fun figLocked(f: Fig): Boolean = layerOf(f)?.locked == true

    fun addLayer() {
        if (layers.size >= 6) {
            showToast("Six layers is the most")
            return
        }
        val l = Layer(nextLayerId++, "Layer " + (layers.size + 1))
        layers.add(l)
        activeLayer = l.id
    }

    fun deleteLayer(id: Int) {
        if (layers.size <= 1) return
        val target = layers.first { it.id != id }
        shapes.filter { it.layer == id }.forEach { it.layer = target.id }
        layers.removeAll { it.id == id }
        if (activeLayer == id) activeLayer = target.id
    }

    fun moveLayer(i: Int, dir: Int) {
        val j = i + dir
        if (i < 0 || j < 0 || i >= layers.size || j >= layers.size) return
        val a = layers[i]
        layers[i] = layers[j]
        layers[j] = a
    }

    fun setLayerLocked(l: Layer, v: Boolean) {
        l.locked = v
        if (v) {
            if (editing != null && editing?.layer == l.id) closeEdit()
            if (sel != null && sel?.layer == l.id) sel = null
            keepActiveUsable()
        }
    }

    fun setLayerVisible(l: Layer, v: Boolean) {
        l.visible = v
        if (!v) {
            if (editing != null && editing?.layer == l.id) closeEdit()
            if (sel != null && sel?.layer == l.id) sel = null
            keepActiveUsable()
        }
    }

    /** New drawings never go onto a hidden or locked layer. */
    private fun keepActiveUsable() {
        val a = layers.firstOrNull { it.id == activeLayer }
        if (a == null || a.locked || !a.visible) {
            layers.firstOrNull { !it.locked && it.visible }?.let { activeLayer = it.id }
        }
    }

    // ---------- history ----------
    val undoStack = mutableStateListOf<Act>()
    val redoStack = mutableStateListOf<Act>()
    val canUndo: Boolean get() = pend.isNotEmpty() || undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // ---------- lines that close up into a shape ----------
    var autoJoin by mutableStateOf(true)

    // ---------- parallel lines ----------
    var pxOn by mutableStateOf(false)
    var pxMode by mutableStateOf("all")
    var pxHl by mutableIntStateOf(0)
    var pxBis by mutableStateOf(false)
    private var pxSeen = false
    private var parKey = ""
    private var parVal: ParGeo? = null

    // ---------- graphs ----------
    var eq by mutableStateOf("a*x+b")
    var gpA by mutableDoubleStateOf(1.0)
    var gpB by mutableDoubleStateOf(0.0)
    var gpC by mutableDoubleStateOf(0.0)
    var animA by mutableStateOf(false)
    var trace by mutableStateOf<Trace?>(null)

    // ---------- Solve: pen, highlighter, eraser, sketch ----------
    val skP = mutableStateListOf<Offset>()
    var penAt by mutableStateOf<Offset?>(null)
    var penCol by mutableStateOf(Color(0xFFFBBF24))
    var penW by mutableStateOf(4f)
    var penStyle by mutableStateOf(0)
    var eraserDp by mutableStateOf(14f)
    var erasePart by mutableStateOf(true)
    var ghost by mutableStateOf<List<Pt>>(emptyList())
    var ghostAt by mutableLongStateOf(0L)

    var holdFired = false
    private var lbN = 0
    private var hintShown = false
    private var toastJob: Job? = null
    private var holdJob: Job? = null
    private var tapJob: Job? = null
    private var tapCount = 0
    private var tapFig: Fig? = null
    private var tapAt = Offset.Zero
    private var tapWorld = Pt(0.0, 0.0)
    private var tapVtx = -1
    private var mvO: List<Pt> = emptyList()
    private var mvS = Pt(0.0, 0.0)
    private var rsO: List<Pt> = emptyList()
    private var rsC = Pt(0.0, 0.0)
    private var rsD = 1.0
    private var rsH = 1.0
    private var rsSd: Map<String, Double> = emptyMap()
    private var rsFsz = 0.6
    private var eraseSnap: List<Fig>? = null
    private val eraseOrig = HashMap<Fig, Int>()

    // ---------- tool kinds ----------
    val isTapTool: Boolean get() = tool in NEED
    val isDragTool: Boolean get() = tool == "seg" || tool == "rect" || tool == "rtri" || tool == "circ"
    val drawsWithFinger: Boolean get() = isTapTool || isDragTool
    val need: Int get() = NEED[tool] ?: 0
    val isGraphTool: Boolean get() = tool == "fx" || tool == "dv"
    val isFreeTool: Boolean get() = tool == "sk" || tool == "pen" || tool == "hl" || tool == "er"
    val isPenTool: Boolean get() = tool == "pen" || tool == "hl"
    val isEraserTool: Boolean get() = tool == "er"
    val isSolidTool: Boolean get() = tool in SOLT

    /** Web: opening Graph / Derivative with no graph yet plots the Line preset. */
    fun start() {
        if (isGraphTool || tool == "trig" || tool == "unit") forceAxes = true
        if (isGraphTool && lastFx() == null) preset(0)
        if (tool == "trig" && trigFig() == null) addTrig()
        if (tool == "unit" && unitFig() == null) setUnitDeg(30)
    }

    // ---------- trigonometry: the trig triangle and the unit circle ----------
    fun trigFig(): Fig? = shapes.firstOrNull { it.t == "trig" && it.dieAt == 0L }
    fun unitFig(): Fig? = shapes.firstOrNull { it.t == "unit" && it.dieAt == 0L }
    fun unitDeg(): Int = unitFig()?.let { unitDegOf(it.p[1]) } ?: 30

    private fun addTrig() {
        val born = System.nanoTime()
        val f = Fig("trig", listOf(Pt(-4.0, -3.0)), born)
        f.layer = activeLayer
        f.ga = 30.0
        f.gb = 6.0
        shapes.add(f)
        bump(born)
        record(AddAct(listOf(f), "Added trig triangle"))
    }

    /** Web: setUnit - put the unit circle's point at this angle (whole degrees). */
    fun setUnitDeg(d: Int) {
        val r = d * PI / 180
        val np = listOf(Pt(0.0, 0.0), Pt(cos(r), sin(r)))
        val u = unitFig()
        if (u == null) {
            val born = System.nanoTime()
            val f = Fig("unit", np, born)
            f.layer = activeLayer
            shapes.add(f)
            bump(born)
            record(AddAct(listOf(f), "Added unit circle"))
        } else {
            edit(u, "Unit circle angle") { u.p = np }
        }
    }

    /** Unit circle tool: the angle follows the finger. */
    fun setUnitAt(px: Offset) {
        val raw = rawWorld(px)
        setUnitDeg(unitDegOf(raw))
    }

    fun selectTool(id: String) {
        tool = id
        trace = null
        animA = false
        cancelFree()
        pend.clear()
        cursor = null
        draft = null
        msg = ""
        mv = null
        rs = null
        group = emptyList()
        pending = emptyList()
        pendRect = null
        boxA = null
        boxB = null
        cropping = null
        toolMenu = null
        penOpts = false
        eraserOpts = false
        closeEdit()
        hideMenu()
        start()
    }

    fun initialOy(): Double =
        if (tool in setOf("fx", "dv", "trig", "unit")) -70.0 * camera.density else 0.0

    // ---------- coordinates ----------
    fun rawWorld(p: Offset): Pt = Pt(camera.wx(p.x), camera.wy(p.y))
    fun snapPt(w: Pt): Pt {
        val q = camera.snapStep
        return Pt(floor(w.x / q + 0.5) * q, floor(w.y / q + 0.5) * q)
    }

    /** Snap to an existing corner or point when one is close (so lines meet exactly), otherwise to the grid. */
    fun snap(p: Offset): Pt {
        val raw = rawWorld(p)
        val tol = 14.0 * camera.density / camera.u
        var best: Pt? = null
        var bd = tol
        for (s in shapes) {
            if (s.dieAt != 0L) continue
            val cand: List<Pt> = when (s.t) {
                "pt", "seg", "tri", "quad", "pent", "hex" -> s.p
                "rect", "rtri" -> pts(s)
                else -> emptyList()
            }
            for (c in cand) {
                val d = dist(c, raw)
                if (d < bd) {
                    bd = d
                    best = c
                }
            }
        }
        return best ?: snapPt(raw)
    }

    // ---------- animation clock, toasts ----------
    fun bump(t: Long = System.nanoTime()) {
        clock = t
        animTick++
        refreshPar()
    }

    fun showToast(m: String) {
        toastMsg = m
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2400)
            toastMsg = null
        }
    }

    // ---------- history ----------
    private fun record(a: Act) {
        undoStack.add(a)
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
    }

    /** Change a figure and remember it for undo. Quick successive changes of one kind become one step. */
    fun edit(f: Fig, label: String, change: () -> Unit) {
        val before = f.snapshot()
        change()
        val after = f.snapshot()
        if (before == after) return
        val now = System.nanoTime()
        val last = undoStack.lastOrNull()
        if (last is EditAct && last.f === f && last.label == label && now - last.time < 900_000_000L) {
            last.after = after
            last.time = now
            redoStack.clear()
        } else {
            record(EditAct(f, before, after, now, label))
        }
        refreshPar()
    }

    fun undo() {
        if (pend.isNotEmpty()) {
            pend.removeAt(pend.lastIndex)
            showToast("↶ Removed the last point")
            return
        }
        if (undoStack.isEmpty()) {
            showToast("Nothing to undo")
            return
        }
        val a = undoStack.removeAt(undoStack.lastIndex)
        applyAct(a, false)
        redoStack.add(a)
        showToast("↶ Undid: " + a.label)
    }

    fun redo() {
        if (redoStack.isEmpty()) {
            showToast("Nothing to redo")
            return
        }
        val a = redoStack.removeAt(redoStack.lastIndex)
        applyAct(a, true)
        undoStack.add(a)
        showToast("↷ Redid: " + a.label)
    }

    private fun applyAct(a: Act, forward: Boolean) {
        when (a) {
            is AddAct -> {
                if (forward) a.fs.forEach { restoreFig(it, Int.MAX_VALUE) } else a.fs.forEach { softRemove(it) }
                bump()
            }
            is RemoveAct -> {
                if (forward) a.fs.forEach { softRemove(it) } else a.fs.forEachIndexed { k, f -> restoreFig(f, a.idx[k]) }
                bump()
            }
            is EditAct -> {
                a.f.restore(if (forward) a.after else a.before)
                bump()
            }
            is MultiEditAct -> {
                a.fs.forEachIndexed { i, f -> f.restore(if (forward) a.after[i] else a.before[i]) }
                bump()
            }
            is ReplaceAct -> {
                val outList = if (forward) a.removed else a.added
                val inList = if (forward) a.added else a.removed
                val inIdx = if (forward) a.addedIdx else a.removedIdx
                outList.forEach { dropFig(it, a.instant) }
                inList.forEachIndexed { k, f -> restoreFig(f, inIdx[k]) }
                bump()
            }
        }
    }

    private fun restoreFig(f: Fig, idx: Int) {
        f.dieAt = 0L
        if (!shapes.contains(f)) shapes.add(idx.coerceIn(0, shapes.size), f)
    }

    private fun clearRefs(f: Fig) {
        if (editing === f) closeEdit()
        if (sel === f) sel = null
        if (mv === f) mv = null
        if (rs === f) rs = null
        if (trace?.f === f) trace = null
        if (cropping === f) cropping = null
        if (f in group) group = group - f
    }

    private fun dropFig(f: Fig, instant: Boolean) {
        if (instant) {
            shapes.remove(f)
            clearRefs(f)
        } else {
            softRemove(f)
        }
    }

    /** Fade a figure out and take it off the board (no history entry). */
    fun softRemove(f: Fig) {
        if (f.dieAt != 0L) return
        val t = System.nanoTime()
        f.dieAt = t
        clearRefs(f)
        bump(t)
        scope.launch {
            delay(260)
            if (f.dieAt != 0L) shapes.remove(f)
        }
    }

    /** Delete a figure the user chose, with an undo step. */
    fun delete(f: Fig) {
        if (f.dieAt != 0L) return
        record(RemoveAct(listOf(f), listOf(shapes.indexOf(f)), "Deleted " + figName(f)))
        softRemove(f)
    }

    fun clear() {
        pend.clear()
        closeEdit()
        sel = null
        mv = null
        rs = null
        hideMenu()
        val alive = shapes.filter { it.dieAt == 0L }
        if (alive.isEmpty()) return
        record(RemoveAct(alive, alive.map { shapes.indexOf(it) }, "Cleared the board"))
        alive.forEach { softRemove(it) }
    }

    // ---------- adding ----------
    fun addFig(t: String, p: List<Pt>) {
        if (!figOk(t, p)) {
            msg = "That did not make a shape — try again"
            return
        }
        msg = ""
        val born = System.nanoTime()
        val nm = if (t in POLY) LB[lbN++ % LB.size] else if (t == "seg") nextSegNames() else null
        val f = Fig(t, p, born, nm)
        f.layer = activeLayer
        f.hide = defaultHide(f)
        shapes.add(f)
        bump(born)
        if (t == "seg" && autoJoin && tryJoin(f)) return
        record(AddAct(listOf(f), "Added " + figName(f)))
        if (!hintShown && t != "pt") {
            hintShown = true
            showToast("Tip: use 👆 Select to edit, copy or move figures")
        }
    }

    /** Called when a finger lifts after pointing with a tap tool (Point / Triangle / Quadrilateral / Pentagon / Hexagon). */
    fun placeTapPoint(p: Pt) {
        if (tool == "pt") {
            addFig("pt", listOf(p))
        } else {
            pend.add(p)
            if (pend.size == need) {
                addFig(tool, pend.toList())
                pend.clear()
            }
        }
    }

    // ---------- lines that close up into a shape ----------
    private fun sameP(a: Pt, b: Pt) = abs(a.x - b.x) < 1e-9 && abs(a.y - b.y) < 1e-9

    /** Look for a closed loop of 3 to 6 lines through the line just drawn. */
    private fun findLoop(newSeg: Fig): Pair<List<Pt>, List<Fig>>? {
        val segs = shapes.filter { it.t == "seg" && it.dieAt == 0L }
        for (total in 3..6) {
            val verts = mutableListOf(newSeg.p[0], newSeg.p[1])
            val used = mutableListOf(newSeg)
            fun dfs(): Boolean {
                val cur = verts.last()
                if (used.size == total - 1) {
                    for (s in segs) {
                        if (s in used) continue
                        val closes = (sameP(s.p[0], cur) && sameP(s.p[1], verts[0])) ||
                            (sameP(s.p[1], cur) && sameP(s.p[0], verts[0]))
                        if (closes) {
                            used.add(s)
                            return true
                        }
                    }
                    return false
                }
                for (s in segs) {
                    if (s in used) continue
                    val nxt = if (sameP(s.p[0], cur)) s.p[1] else if (sameP(s.p[1], cur)) s.p[0] else continue
                    if (verts.any { sameP(it, nxt) }) continue
                    verts.add(nxt)
                    used.add(s)
                    if (dfs()) return true
                    verts.removeAt(verts.lastIndex)
                    used.removeAt(used.lastIndex)
                }
                return false
            }
            if (dfs()) return verts.toList() to used.toList()
        }
        return null
    }

    private fun polygonKind(v: List<Pt>): Pair<String, List<Pt>> = when (v.size) {
        3 -> "tri" to v
        4 -> {
            val r = rectOf(v)
            if (r != null) "rect" to r else "quad" to v
        }
        5 -> "pent" to v
        else -> "hex" to v
    }

    /** Three lines that meet become a triangle, four a quadrilateral, and so on. */
    private fun tryJoin(f: Fig): Boolean {
        val loop = findLoop(f) ?: return false
        val verts = loop.first
        if (polyArea(verts) <= 1e-9) return false
        val kind = polygonKind(verts)
        val pointsHere = shapes.filter { it.t == "pt" && it.dieAt == 0L && verts.any { v -> sameP(v, it.p[0]) } }
        val removed = (loop.second + pointsHere).sortedBy { shapes.indexOf(it) }
        val removedIdx = removed.map { shapes.indexOf(it) }
        val born = System.nanoTime()
        val poly = Fig(kind.first, kind.second, born, LB[lbN++ % LB.size])
        poly.layer = f.layer
        poly.hide = defaultHide(poly)
        removed.forEach { softRemove(it) }
        shapes.add(poly)
        val addedIdx = listOf(shapes.size - 1)
        bump(born)
        record(ReplaceAct(removed, removedIdx, listOf(poly), addedIdx, false, "Joined lines into a " + figName(poly)))
        showToast("Joined into a ${figName(poly)} — undo (↶) gives the lines back")
        return true
    }

    // ---------- parallel lines ----------
    /** The lines to look at: edges of the selected content when something is selected, otherwise every drawn line. */
    private fun parSegs(): List<Fig> {
        val live = group.filter { it.dieAt == 0L }
        if (live.isNotEmpty()) {
            val out = mutableListOf<Fig>()
            for (f in live) {
                when (f.t) {
                    "seg" -> out.add(f)
                    "tri", "quad", "rect", "rtri", "pent", "hex" -> {
                        val q = pts(f)
                        for (i in q.indices) out.add(Fig("seg", listOf(q[i], q[(i + 1) % q.size]), 0L))
                    }
                }
            }
            if (out.size >= 3) return out
        }
        // nothing selected: every drawn line AND the sides of every drawn shape (a rectangle has parallel sides too)
        val out = mutableListOf<Fig>()
        for (f in shapes) {
            if (f.dieAt != 0L) continue
            when (f.t) {
                "seg" -> out.add(f)
                "tri", "quad", "rect", "rtri", "pent", "hex" -> {
                    val q = pts(f)
                    for (i in q.indices) out.add(Fig("seg", listOf(q[i], q[(i + 1) % q.size]), 0L))
                }
            }
        }
        return out
    }

    var pxAllSets by mutableStateOf(false)

    // ---------- labels: every line gets its own letters (A B, C D … then A′ B′ …) ----------
    private var segLabN = 0
    var namesOpen by mutableStateOf(false)

    /** The two end letters of the next line: "AB", "CD" … after Z comes A′, B′ … */
    fun nextSegNames(): String {
        val a = runLabel(segLabN++)
        val b = runLabel(segLabN++)
        return a + b
    }

    /** Change the letters of a figure (corners, or the two ends of a line). */
    fun setNames(f: Fig, text: String) {
        edit(f, "Changed the letters") { f.nm = text.ifBlank { null } }
    }

    /** A′ B′ C′: add a prime to every letter. */
    fun primeNames(f: Fig) {
        val l = splitLabels(f.nm ?: (if (f.t == "seg") "AB" else "ABCDEF")).take(if (f.t == "seg") 2 else pts(f).size)
        setNames(f, l.joinToString("") { it + "′" })
    }

    /**
     * The eye of a Content selection. It is kept on each figure (key "eye" in [Fig.ov]), so the details stay on the
     * board when the box is gone and come back with the board; they only go when the eye is switched off.
     */
    var grpDetailsOpen by mutableStateOf(false)

    private fun eyeable(f: Fig) = f.dieAt == 0L && f.t != "ink" && f.t != "txt" && f.t != "img" && f.t != "fx"

    /** True when every figure of the selection that has details has its eye on. */
    val grpEye: Boolean
        get() {
            val fs = group.filter { eyeable(it) }
            return fs.isNotEmpty() && fs.all { "eye" in it.ov }
        }

    /** The figures of the selection that can show details (the Details box lists these). */
    fun grpDetailFigs(): List<Fig> = group.filter { eyeable(it) && it.t in setOf("seg", "tri", "quad", "rect", "rtri", "pent", "hex", "circ") }

    fun toggleGroupEye() {
        val fs = group.filter { eyeable(it) }
        if (fs.isEmpty()) {
            showToast("The selected things have no details to show")
            return
        }
        if (grpEye && !grpDetailsOpen) {
            // details are already on: the eye just brings the Details box back
            grpDetailsOpen = true
            return
        }
        val on = !grpEye
        val before = fs.map { it.snapshot() }
        for (f in fs) {
            if (on) {
                if ("eye" !in f.ov) {
                    f.eyeBefore = f.hide
                    f.hide = emptySet()
                    f.only = -1
                    f.ov = f.ov + "eye"
                }
            } else {
                f.hide = f.eyeBefore ?: f.hide
                f.eyeBefore = null
                f.only = -1
                f.ov = f.ov - "eye"
            }
        }
        record(MultiEditAct(fs, before, fs.map { it.snapshot() }, if (on) "Details on" else "Details off"))
        grpDetailsOpen = on
        bump()
        showToast(if (on) "👁 Details on — they stay on the board until you switch the eye off" else "Details off")
    }

    /** Angles at the joints of a line (where another line meets one of its ends), for the details panel. */
    fun jointsFor(f: Fig): List<JointInfo> = jointsOf(shapes.toList(), f, 6.0 * camera.density / camera.u)

    /** Change the angle between two joined lines: the other line turns about the joint, keeping its length. */
    fun setJointAngle(f: Fig, j: JointInfo, deg: Double) {
        val g = j.other
        val pf = pts(f)
        val pg = pts(g)
        val v = pf[j.e]
        val a = pf[1 - j.e]
        val b = pg[1 - j.oe]
        val ta = atan2(a.y - v.y, a.x - v.x)
        val tb = atan2(b.y - v.y, b.x - v.x)
        var d = tb - ta
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        val sgn = if (d >= 0) 1.0 else -1.0
        val len = hypot(b.x - v.x, b.y - v.y)
        val ang = ta + sgn * deg * PI / 180
        val np = pg.toMutableList()
        np[1 - j.oe] = Pt(v.x + len * cos(ang), v.y + len * sin(ang))
        edit(g, "Angle ${j.name}") {
            g.p = np
            g.base = null
            g.sz = 1.0
            g.rot = 0.0
        }
    }

    private var parSets: List<ParGeo> = emptyList()
    private var parSegCache: List<Fig> = emptyList()
    private var parTapAt = 0L
    private var parTapSeg: Fig? = null

    /**
     * Double-tap a line that belongs to another set of parallel lines + transversal: that set is shown and the
     * one that was showing is hidden. Returns true when the tap was used for that.
     */
    private fun tryParSwitch(w: Pt): Boolean {
        val sets = parAll()
        if (sets.size < 2) return false
        val tol = 18.0 * camera.density / camera.u
        var best: Fig? = null
        var bd = tol
        for (sg in parSegCache) {
            val d = segDist(w, sg.p[0], sg.p[1])
            if (d < bd) {
                bd = d
                best = sg
            }
        }
        val hit = best
        if (hit == null) {
            parTapSeg = null
            return false
        }
        val now = System.nanoTime()
        val second = parTapSeg === hit && now - parTapAt < 450_000_000L
        parTapSeg = hit
        parTapAt = now
        if (!second) return false
        parTapSeg = null
        val cur = parIdx.coerceIn(0, sets.size - 1)
        val idx = sets.indices.firstOrNull { it != cur && sets[it].tr === hit }
            ?: sets.indices.firstOrNull { it != cur && (sets[it].a === hit || sets[it].b === hit) }
            ?: return false
        parIdx = idx
        pxHl = 0
        pxOn = true
        showToast("∥ Showing the angles for this line — the other set is hidden")
        return true
    }

    fun parAll(): List<ParGeo> {
        val segs = parSegs()
        val realIds = HashSet<Int>()
        for (f in shapes) realIds.add(System.identityHashCode(f))
        val key = segs.joinToString("|") {
            val h = System.identityHashCode(it)
            "${if (realIds.contains(h)) h else 0}:${it.p[0].x},${it.p[0].y},${it.p[1].x},${it.p[1].y}"
        }
        if (key != parKey) {
            parKey = key
            parSegCache = segs
            parSets = findAllPar(segs)
            parVal = parSets.firstOrNull()
        }
        return parSets
    }

    fun parGeo(): ParGeo? {
        val all = parAll()
        if (all.isEmpty()) return null
        return all[parIdx.coerceIn(0, all.size - 1)]
    }

    fun nextParSet() {
        val n = parAll().size
        if (n > 1) {
            parIdx = (parIdx + 1) % n
            pxHl = 0
        }
    }

    /** Switch the angle tool on by itself when two parallel lines and a transversal appear. */
    fun refreshPar() {
        val g = parGeo()
        if (g != null && !pxSeen) {
            pxSeen = true
            pxOn = true
            val n = parAll().size
            showToast(
                if (n > 1) "∥ $n sets of parallel lines + transversal found — tap Set ▸ to switch, or All sets to see them together"
                else "∥ Parallel lines + transversal found — angles ∠1 to ∠8 are shown. Tap one to see what it equals",
            )
        }
        if (g == null) {
            pxSeen = false
            pxOn = false
            pxHl = 0
        }
    }

    /** Web: pxPick - tap near a crossing to pick the angle there. */
    fun pxPick(px: Offset): Boolean {
        val g = parGeo() ?: return false
        val raw = rawWorld(px)
        for (i in 0..1) {
            val x = g.xs[i]
            val vx = raw.x - x.x
            val vy = raw.y - x.y
            val ds = hypot(vx, vy) * camera.u / camera.density
            if (ds < 55 && ds > 3) {
                val det = g.t.x * g.d.y - g.t.y * g.d.x
                val aa = (vx * g.d.y - vy * g.d.x) / det
                val bb = (g.t.x * vy - g.t.y * vx) / det
                val k = (if (i == 1) 5 else 1) + (if (aa > 0) 0 else 2) + (if (bb > 0) 1 else 0)
                pxHl = if (pxHl == k) 0 else k
                return true
            }
        }
        return false
    }

    // ---------- graphs ----------
    fun lastFx(): Fig? = shapes.lastOrNull { it.t == "fx" && it.dieAt == 0L && it.gd?.dv == 0 }

    fun plot() = plotSource(eq.trim())

    private fun plotSource(src: String) {
        forceAxes = true
        val dr = derivativeRequest(src)
        if (dr != null) {
            plotDerivative(dr.first, dr.second)
            return
        }
        val r = parseEq(src)
        if (r == null) {
            msg = "Could not read that. Try: 2x^2 - 3x + 1 , x^2+y^2=25 , |x|+|y|=4 , (cos(t), sin(t)) , r=2sin(theta) , dy/dx of x^3"
            return
        }
        msg = ""
        val born = System.nanoTime()
        val f = Fig("fx", emptyList(), born)
        f.layer = activeLayer
        f.gd = GraphDef(src, r.fn, r.imp, 0, null, r.par)
        f.ga = gpA
        f.gb = gpB
        f.gc = gpC
        f.col = GraphColors[shapes.count { it.t == "fx" } % GraphColors.size]
        shapes.add(f)
        bump(born)
        record(AddAct(listOf(f), "Plotted a graph"))
    }

    /** "dy/dx of x^3": the function and its slope curve together (order 0: just plot what is on the right of "="). */
    private fun plotDerivative(order: Int, inner: String) {
        if (order == 0) {
            plotSource(inner)
            showToast("Plotting the slope function you typed")
            return
        }
        val r = parseEq(inner)
        if (r == null || r.imp || r.par != null) {
            msg = "Type a function of x after dy/dx, for example  dy/dx of x^3 - 3x"
            return
        }
        msg = ""
        val born = System.nanoTime()
        val base = Fig("fx", emptyList(), born)
        base.layer = activeLayer
        base.gd = GraphDef(inner, r.fn, false)
        base.ga = gpA
        base.gb = gpB
        base.gc = gpC
        base.col = GraphColors[shapes.count { it.t == "fx" } % GraphColors.size]
        val d = Fig("fx", emptyList(), born + 1)
        d.layer = activeLayer
        d.gd = GraphDef(inner, null, false, order, base)
        d.col = if (order == 1) Color(0xFFF472B6) else Color(0xFF34D399)
        shapes.add(base)
        shapes.add(d)
        bump(born)
        record(AddAct(listOf(base, d), if (order == 1) "Plotted dy/dx" else "Plotted d²y/dx²"))
        showToast(if (order == 1) "Pink curve = dy/dx, the slope of the other curve" else "Green curve = d²y/dx²")
    }

    fun preset(i: Int) {
        val p = PRESETS[i]
        eq = p.eq
        gpA = p.a
        gpB = p.b
        gpC = p.c
        plot()
    }

    /** Web: addDv - add the f' or f'' curve of the last graph. */
    fun addDv(n: Int) {
        val b = lastFx() ?: return
        val bg = b.gd ?: return
        if (bg.imp || bg.par != null) {
            showToast("Derivative needs a graph like y = f(x)")
            return
        }
        val born = System.nanoTime()
        val f = Fig("fx", emptyList(), born)
        f.layer = activeLayer
        f.gd = GraphDef(bg.src, null, false, n, b)
        f.col = if (n == 1) Color(0xFFF472B6) else Color(0xFF34D399)
        shapes.add(f)
        bump(born)
        record(AddAct(listOf(f), if (n == 1) "Added f′ curve" else "Added f″ curve"))
    }

    /** The a / b / c sliders of the graph panel. */
    fun setParam(k: String, v: Double) {
        when (k) {
            "a" -> gpA = v
            "b" -> gpB = v
            else -> gpC = v
        }
        if (isGraphTool) lastFx()?.let {
            when (k) {
                "a" -> it.ga = v
                "b" -> it.gb = v
                else -> it.gc = v
            }
        }
    }

    /** Graph / Derivative tool: tap to put the tangent on the last graph. */
    fun onGraphTap(px: Offset) {
        val g = lastFx() ?: return
        if (g.gd?.imp == true) return
        trace = Trace(g, rawWorld(px).x)
    }

    /** Web: act() - the figure whose description the info bar shows. */
    fun activeFig(): Fig? =
        editing ?: mv ?: (
            if (isGraphTool) lastFx()
            else if (tool == "trig") trigFig()
            else shapes.lastOrNull { it.dieAt == 0L && it.t != "ink" }
        )

    // ---------- free-hand: pen, highlighter, eraser, sketch ----------
    fun beginFree(pos: Offset) {
        skP.clear()
        skP.add(pos)
        penAt = pos
        if (tool == "er") {
            eraseSnap = shapes.filter { it.t == "ink" && it.dieAt == 0L }
            eraseOrig.clear()
            eraseSnap?.forEach { eraseOrig[it] = shapes.indexOf(it) }
            erase(rawWorld(pos))
        }
    }

    fun moveFree(pos: Offset, minStep: Float) {
        val last = skP.lastOrNull()
        if (last == null || (pos - last).getDistance() >= minStep) skP.add(pos)
        penAt = pos
        if (tool == "er") erase(rawWorld(pos))
    }

    fun cancelFree() {
        skP.clear()
        penAt = null
        endErase()
    }

    /** The eraser lifted: record everything it rubbed out as one undo step. */
    private fun endErase() {
        val before = eraseSnap ?: return
        eraseSnap = null
        val now = shapes.filter { it.t == "ink" && it.dieAt == 0L }
        val removed = before.filter { it !in now }
        val added = now.filter { it !in before }
        if (removed.isEmpty() && added.isEmpty()) return
        val rem = removed.sortedBy { eraseOrig[it] ?: 0 }
        val remIdx = rem.map { eraseOrig[it] ?: 0 }
        val add = added.sortedBy { shapes.indexOf(it) }
        val addIdx = add.map { shapes.indexOf(it) }
        record(ReplaceAct(rem, remIdx, add, addIdx, true, "Erased part of the drawing"))
        eraseOrig.clear()
    }

    /** Rub out the part of every stroke under the eraser (or the whole stroke, in "Whole stroke" mode). */
    fun erase(c: Pt) {
        val r = eraserDp.toDouble() * camera.density / camera.u
        for (s in shapes.toList()) {
            if (s.t != "ink" || s.dieAt != 0L) continue
            if (!figVisible(s) || figLocked(s)) continue
            val q = s.p
            val touched = q.indices.any { k -> dseg(c, q[k], q.getOrElse(k + 1) { q[k] }) <= r }
            if (!touched) continue
            val idx = shapes.indexOf(s)
            shapes.removeAt(idx)
            if (!erasePart) continue
            val pieces = cutStroke(q, c, r)
            pieces.forEachIndexed { k, pp ->
                val n = s.copyOf(System.nanoTime())
                n.p = pp
                shapes.add(idx + k, n)
            }
        }
    }

    private fun cutStroke(p: List<Pt>, c: Pt, r: Double): List<List<Pt>> {
        val dense = ArrayList<Pt>()
        for (i in p.indices) {
            dense.add(p[i])
            if (i + 1 < p.size) {
                val a = p[i]
                val b = p[i + 1]
                val n = minOf(200, ceil(dist(a, b) / (r * 0.4)).toInt())
                for (k in 1 until n) {
                    val t = k.toDouble() / n
                    dense.add(Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
                }
            }
        }
        val out = ArrayList<List<Pt>>()
        var cur = ArrayList<Pt>()
        for (q in dense) {
            if (dist(q, c) > r) {
                cur.add(q)
            } else {
                if (cur.size >= 2) out.add(cur)
                cur = ArrayList()
            }
        }
        if (cur.size >= 2) out.add(cur)
        return out
    }

    /** The finger lifted after a free-hand stroke: save ink, or recognise a sketch. */
    fun finishFree() {
        val sp = skP.toList()
        cancelFree()
        if (sp.isEmpty()) return
        if (tool == "pen" || tool == "hl") {
            val born = System.nanoTime()
            val raw = if (sp.size < 2) listOf(sp[0], sp[0]) else sp
            val f = Fig("ink", raw.map { rawWorld(it) }, born)
            f.layer = activeLayer
            f.col = penCol
            f.inkW = penW.toDouble()
            f.hl = tool == "hl"
            f.inkStyle = if (tool == "hl") 0 else penStyle
            shapes.add(f)
            bump(born)
            record(AddAct(listOf(f), if (tool == "hl") "Highlighter stroke" else "Pen stroke"))
            return
        }
        if (tool == "er") return
        val res = recognize(sp, this)
        if (res == null) {
            showToast("Couldn't recognise that — try a clearer shape")
            return
        }
        msg = ""
        ghost = sp.map { rawWorld(it) }
        ghostAt = System.nanoTime()
        addFig(res.first, res.second)
        if (msg.isEmpty()) {
            showToast("Looks like: ${FigNames[res.first] ?: res.first} — adjust it below")
            val ns = shapes.lastOrNull { it.dieAt == 0L }
            selectTool("sel")
            if (ns != null) openEdit(ns)
        }
    }

    // ---------- hit testing ----------
    fun hit(w: Pt): Fig? {
        val tol = 16.0 * camera.density / camera.u
        for (i in shapes.indices.reversed()) {
            val s = shapes[i]
            if (s.dieAt != 0L || s.t == "ink" || s.t == "unit") continue
            if (!figVisible(s) || figLocked(s)) continue
            val q = pts(s)
            val h = when (s.t) {
                "pt" -> dist(w, q[0]) < tol * 1.4
                "seg" -> dseg(w, q[0], q[1]) < tol
                "circ" -> dist(q[0], w) <= dist(q[0], q[1]) + tol
                "fx" -> hitGraph(s, w)
                "img", "txt" -> {
                    val x0 = minOf(q[0].x, q[1].x) - tol * 0.5
                    val x1 = maxOf(q[0].x, q[1].x) + tol * 0.5
                    val y0 = minOf(q[0].y, q[1].y) - tol * 0.5
                    val y1 = maxOf(q[0].y, q[1].y) + tol * 0.5
                    w.x in x0..x1 && w.y in y0..y1
                }
                "sol" -> inSolidBox(s, Offset(camera.sx(w.x), camera.sy(w.y)))
                else -> inPoly(w, q) || q.indices.any { k -> dseg(w, q[k], q[(k + 1) % q.size]) < tol }
            }
            if (h) return s
        }
        return null
    }

    private fun inSolidBox(s: Fig, px: Offset): Boolean {
        val b = solidBox(camera, s)
        val tol = 10f * camera.density.toFloat()
        return px.x >= b[0] - tol && px.x <= b[2] + tol && px.y >= b[1] - tol && px.y <= b[3] + tol
    }

    /** The solid under a screen point (for rotating it with a drag). */
    fun hitSolid(px: Offset): Fig? {
        for (i in shapes.indices.reversed()) {
            val s = shapes[i]
            if (s.t != "sol" || s.dieAt != 0L) continue
            if (!figVisible(s) || figLocked(s)) continue
            if (inSolidBox(s, px)) return s
        }
        return null
    }

    /** Web: rotS - dragging a solid turns it. */
    fun rotateSolid(f: Fig, dx: Float, dy: Float) {
        val k = 0.012 / camera.density
        edit(f, "Rotated " + figName(f)) {
            f.ry += dx * k
            f.rx = (f.rx + dy * k).coerceIn(-1.5, 1.5)
        }
    }

    /** Web: addSol - a solid tool tapped on the board puts a solid there. */
    fun onSolidTap(px: Offset) {
        val w = rawWorld(px)
        val h = hit(w)
        if (h != null && h.t == "sol") {
            openEdit(h)
            return
        }
        val q = camera.snapStep
        val at = Pt(floor(w.x / q + 0.5) * q, floor(w.y / q + 0.5) * q)
        val born = System.nanoTime()
        val f = Fig("sol", listOf(at), born)
        f.layer = activeLayer
        f.sk = tool
        f.sd = SOLD[tool] ?: mapOf("a" to 3.0)
        shapes.add(f)
        bump(born)
        record(AddAct(listOf(f), "Added " + (SOLN[tool] ?: "solid")))
        openEdit(f)
        showToast("Drag the solid to rotate it 🔄")
    }

    /** Web: doAct ext / apx - turn a flat figure into a prism or a pyramid on that base. */
    fun makeSolid(f: Fig, kind: String) {
        if (f.t !in POLY) {
            showToast("Pick a flat shape first")
            return
        }
        val c = centerOf(f.t, f.p)
        val born = System.nanoTime()
        val ns = Fig("sol", listOf(c), born)
        ns.sk = kind
        ns.sd = mapOf("h" to 3.0)
        ns.poly = pts(f).map { Pt(it.x - c.x, it.y - c.y) }
        ns.col = f.col ?: figColor(f.t)
        ns.layer = f.layer
        val idx = shapes.indexOf(f)
        softRemove(f)
        shapes.add(ns)
        bump(born)
        record(ReplaceAct(listOf(f), listOf(idx), listOf(ns), listOf(shapes.size - 1), false, "3D " + (SOLN[kind] ?: "solid")))
        openEdit(ns)
        showToast("3D solid made — set the height, drag to rotate 🔄")
    }

    private fun hitGraph(s: Fig, p: Pt): Boolean {
        val g = s.gd ?: return false
        val tol = 18.0 * camera.density
        val pd = g.par
        if (pd != null) {
            val sx0 = camera.sx(p.x)
            val sy0 = camera.sy(p.y)
            var best = Double.MAX_VALUE
            val n = 500
            for (i in 0..n) {
                val q = s.parAt(pd.t0 + (pd.t1 - pd.t0) * i / n) ?: continue
                val d = hypot((camera.sx(q.x) - sx0).toDouble(), (camera.sy(q.y) - sy0).toDouble())
                if (d < best) best = d
            }
            return best < tol
        }
        return if (g.imp) {
            val e = 1e-4
            val v = s.vAt(p.x, p.y)
            val gx = (s.vAt(p.x + e, p.y) - s.vAt(p.x - e, p.y)) / 2 / e
            val gy = (s.vAt(p.x, p.y + e) - s.vAt(p.x, p.y - e)) / 2 / e
            val gl = hypot(gx, gy).let { if (it == 0.0) 1.0 else it }
            v.isFinite() && abs(v) / gl * camera.u < tol
        } else {
            val y = s.yAt(p.x)
            y.isFinite() && abs(y - p.y) * camera.u < tol
        }
    }

    fun findHandle(px: Offset): Int {
        val e = editing ?: return -1
        if (!shapes.contains(e)) return -1
        var best = 28f * camera.density.toFloat()
        var idx = -1
        for (i in 0 until hcount(e)) {
            val q = e.p[i]
            val d = hypot(camera.sx(q.x) - px.x, camera.sy(q.y) - px.y)
            if (d < best) {
                best = d
                idx = i
            }
        }
        return idx
    }

    // ---------- editing ----------
    fun openEdit(f: Fig) {
        editing = f
        sel = f
        f.base = null
        f.sz = 1.0
        f.rot = 0.0
    }

    fun closeEdit(clearSel: Boolean = true) {
        editing = null
        detailsOn = false
        labelsOpen = false
        if (clearSel) {
            sel = null
            trace = null
        }
    }

    fun dragHandle(i: Int, px: Offset) {
        val f = editing ?: return
        if (f.t == "img" || f.t == "txt") {
            dragBoxHandle(f, i, px)
            return
        }
        val np = f.p.toMutableList()
        np[i] = snapPt(rawWorld(px))
        if (!figOk(f.t, np)) return
        edit(f, "Reshaped " + figName(f)) {
            f.p = np
            f.base = null
            f.sz = 1.0
            f.rot = 0.0
        }
    }

    fun beginMove(f: Fig, px: Offset) {
        mvO = f.p
        mvS = rawWorld(px)
    }

    fun dragMove(f: Fig, px: Offset) {
        val q = camera.snapStep
        val w = rawWorld(px)
        val dx = floor((w.x - mvS.x) / q + 0.5) * q
        val dy = floor((w.y - mvS.y) / q + 0.5) * q
        edit(f, "Moved " + figName(f)) {
            f.p = mvO.map { Pt(it.x + dx, it.y + dy) }
            f.base = null
        }
    }

    fun beginResize(f: Fig, px: Offset) {
        rsO = f.p
        rsH = f.gb
        rsSd = f.sd
        rsFsz = f.fsz
        rsC = if (f.t == "trig") center(f) else centerOf(f.t, f.p)
        rsD = maxOf(dist(rawWorld(px), rsC), 1e-9)
    }

    fun dragResize(f: Fig, px: Offset) {
        if (f.t == "trig") {
            val kk = dist(rawWorld(px), rsC) / rsD
            edit(f, "Resized " + figName(f)) { f.gb = (rsH * kk).coerceIn(0.5, 12.0) }
            return
        }
        if (f.t == "sol") {
            val kk = dist(rawWorld(px), rsC) / rsD
            edit(f, "Resized " + figName(f)) { f.sd = rsSd.mapValues { e -> (e.value * kk).coerceIn(0.3, 12.0) } }
            return
        }
        val k = dist(rawWorld(px), rsC) / rsD
        if (k < 0.05 || k > 50) return
        if (f.t == "txt") {
            val np2 = rsO.map { Pt(rsC.x + (it.x - rsC.x) * k, rsC.y + (it.y - rsC.y) * k) }
            edit(f, "Resized text") {
                f.p = np2
                f.fsz = (rsFsz * k).coerceIn(0.02, 60.0)
            }
            return
        }
        val np = rsO.map { Pt(rsC.x + (it.x - rsC.x) * k, rsC.y + (it.y - rsC.y) * k) }
        if (figOk(f.t, np)) {
            edit(f, "Resized " + figName(f)) {
                f.p = np
                f.base = null
            }
        }
    }

    /** Web: editField - a number box or slider in the edit panel changed. */
    fun editField(f: Fig, k: String, v: Double) {
        edit(f, "Changed " + figName(f)) { applyField(f, k, v) }
    }

    /** Make a polygon regular (equal sides and angles). */
    fun makeRegular(f: Fig) {
        edit(f, "Made " + figName(f) + " regular") {
            f.p = regularPolygon(f.p)
            f.base = null
            f.sz = 1.0
            f.rot = 0.0
        }
    }

    private fun applyField(f: Fig, k: String, v: Double) {
        if (!v.isFinite()) return
        if (f.t == "fx") {
            when (k) {
                "a" -> { f.ga = v; gpA = v }
                "b" -> { f.gb = v; gpB = v }
                "c" -> { f.gc = v; gpC = v }
            }
            return
        }
        if (f.t == "sol") {
            when (k) {
                "ry" -> f.ry = v * PI / 180
                "rx" -> f.rx = (v * PI / 180).coerceIn(-1.5, 1.5)
                else -> if (v > 0) f.sd = f.sd + (k to v.coerceAtMost(12.0))
            }
            return
        }
        if (f.t == "trig") {
            when (k) {
                "th" -> f.ga = v.coerceIn(0.0, 90.0)
                "h" -> f.gb = maxOf(0.5, v)
            }
            return
        }
        if (k == "size" || k == "rot") {
            if (k == "rot" && (f.t == "rect" || f.t == "rtri")) {
                f.p = pts(f)
                f.t = if (f.t == "rect") "quad" else "tri"
                f.base = null
                showToast("Now a free shape, so it can rotate")
            }
            val o = f.base ?: f.p.also { f.base = it }
            if (k == "size") f.sz = v / 100 else f.rot = v
            val c = centerOf(f.t, o)
            val r = f.sz
            val th = f.rot * PI / 180
            val co = cos(th)
            val si = sin(th)
            f.p = o.map { q ->
                val dx = (q.x - c.x) * r
                val dy = (q.y - c.y) * r
                Pt(c.x + dx * co - dy * si, c.y + dx * si + dy * co)
            }
            return
        }
        if (v <= 0 && k != "x" && k != "y" && k != "ang") return
        val np = f.p.toMutableList()
        when (f.t) {
            "pt" -> np[0] = if (k == "x") Pt(v, np[0].y) else Pt(np[0].x, v)
            "seg" -> {
                val a = np[0]
                val b = np[1]
                var l = dist(a, b)
                var t = atan2(b.y - a.y, b.x - a.x)
                if (k == "len") l = v else t = v * PI / 180
                np[1] = Pt(a.x + l * cos(t), a.y + l * sin(t))
            }
            "circ" -> {
                val a = np[0]
                val b = np[1]
                val t = atan2(b.y - a.y, b.x - a.x)
                np[1] = Pt(a.x + v * cos(t), a.y + v * sin(t))
            }
            "rect", "rtri" -> {
                val a = np[0]
                val b = np[1]
                val gx = if (b.x - a.x < 0) -1.0 else 1.0
                val gy = if (b.y - a.y < 0) -1.0 else 1.0
                np[1] = if (k == "w") Pt(a.x + gx * v, b.y) else Pt(b.x, a.y + gy * v)
            }
            "tri" -> {
                val r = solveTri(np, k, v)
                if (r == null) {
                    showToast("Those sides cannot make a triangle")
                    return
                }
                r.forEachIndexed { i, q -> np[i] = q }
            }
            "quad" -> {
                val r = solveQuad(np, k, v)
                if (r == null) {
                    showToast("Those lengths cannot make a quadrilateral")
                    return
                }
                r.forEachIndexed { i, q -> np[i] = q }
            }
            else -> return
        }
        if (figOk(f.t, np)) {
            f.p = np
            f.base = null
            f.sz = 1.0
            f.rot = 0.0
        }
    }

    // ---------- select tool: multi-tap ----------
    fun onSelTap(px: Offset) {
        val w = rawWorld(px)
        if (tryParSwitch(w)) return
        val h = hit(w)
        if (h == null) {
            mv = null
            rs = null
            closeEdit()
            sel = null
            tapCount = 0
            tapFig = null
            onBlankTap(px)
            return
        }
        if (mv != null || rs != null) return
        if (h in group) {
            closeEdit()
            showToast("Content is selected — use the bar below, or drag the box")
            return
        }
        registerTap(h, vertexAt(h, px), px, w)
    }

    /** Tapping a corner handle of the figure that is already open (the handle swallows the tap, so count it here). */
    fun onHandleTap(px: Offset) {
        val e = editing ?: return
        if (mv != null || rs != null || e.t !in POLY) return
        val vi = vertexAt(e, px)
        if (vi < 0) return
        registerTap(e, vi, px, rawWorld(px))
    }

    private fun registerTap(h: Fig, vi: Int, px: Offset, w: Pt) {
        if (h === tapFig && tapCount > 0 && vi == tapVtx) tapCount++ else {
            tapCount = 1
            tapFig = h
            tapVtx = vi
        }
        tapAt = px
        tapWorld = w
        tapJob?.cancel()
        tapJob = scope.launch {
            delay(350)
            resolveTap()
        }
    }

    /** Index of the corner of a polygon that [px] is on (within a fingertip), or -1. */
    private fun vertexAt(f: Fig, px: Offset): Int {
        if (f.t !in POLY) return -1
        val q = pts(f)
        var best = 28f * camera.density.toFloat()
        var idx = -1
        for (i in q.indices) {
            val d = hypot(camera.sx(q[i].x) - px.x, camera.sy(q[i].y) - px.y)
            if (d < best) {
                best = d
                idx = i
            }
        }
        return idx
    }

    private fun resolveTap() {
        val h = tapFig
        val n = tapCount
        val vi = tapVtx
        tapCount = 0
        tapVtx = -1
        if (n == 0 || h == null || !shapes.contains(h) || h.dieAt != 0L) return
        if (vi >= 0 && n >= 2) {
            // two or more taps on a corner: select the whole figure, then show just that one angle
            if (editing !== h) openEdit(h) else sel = h
            val on = h.only != vi
            isolateAngle(h, vi)
            labelsOpen = true
            showToast(if (on) "Only this angle ∠" + labelAt(h.nm, vi) + " — tap the corner twice again to show all" else "All labels back")
            return
        }
        if (n == 1) {
            if (!(vi >= 0 && editing === h)) openEdit(h)
            if (h.t == "fx" && h.gd?.imp == false && h.gd?.par == null) trace = Trace(h, tapWorld.x)
            return
        }
        closeEdit(clearSel = false)
        sel = h
        when (n) {
            2 -> menu = MenuState(tapAt.x, tapAt.y, cmItems(), tapWorld)
            3 -> {
                mv = h
                showToast("Move mode ✥ — drag the figure, tap empty space when done")
            }
            4 -> if (h.t == "pt") showToast("Resize works on figures") else {
                rs = h
                showToast("Resize ⤢ — drag outward or inward, tap empty space when done")
            }
            else -> showToast("Bisectors: switch on ∠ Bisectors in the parallel-lines panel")
        }
    }

    // ---------- label switches (the eye box) ----------
    fun labelKeys(f: Fig): List<String> {
        val n = pts(f).size
        return (0 until n).map { "s$it" } + (0 until n).map { "a$it" } + "v"
    }

    fun toggleLabel(f: Fig, key: String) {
        edit(f, "Toggled a label") {
            f.hide = if (key in f.hide) f.hide - key else f.hide + key
            f.only = -1
        }
    }

    /**
     * Show all / Hide all. Hiding also switches off the extras (diagonals, midpoints, circles...) but keeps the
     * lines of the figure; showing brings those extras back as they were.
     */
    fun showAllLabels(f: Fig, show: Boolean) {
        edit(f, if (show) "Showed the details" else "Hid the details") {
            if (show) {
                f.hide = emptySet()
                f.ov = f.ov + f.stash
                f.stash = emptySet()
            } else {
                f.hide = if (f.t == "seg") setOf("len") else if (f.t == "circ") emptySet() else labelKeys(f).toSet()
                // the Content eye ("eye") is a mode of its own: it is not an extra to stash
                val extras = f.ov - "eye"
                if (extras.isNotEmpty()) {
                    f.stash = f.stash + extras
                    f.ov = f.ov - extras
                }
            }
            f.only = -1
        }
    }

    fun toggleOverlay(f: Fig, key: String) {
        edit(f, "Toggled " + key) { f.ov = if (key in f.ov) f.ov - key else f.ov + key }
    }

    /** The eye of a line: show or hide its length. */
    fun toggleLength(f: Fig) {
        edit(f, "Toggled the length") {
            f.hide = if ("len" in f.hide) f.hide - "len" else f.hide + "len"
        }
    }

    /** Show only the angle at corner [i]; do it again to bring everything back. */
    fun isolateAngle(f: Fig, i: Int) {
        edit(f, "Single angle") { f.only = if (f.only == i) -1 else i }
    }

    /** Set the angle at corner [i] in degrees by turning the next side around that corner (its length stays). */
    fun setAngleAt(f: Fig, i: Int, deg: Double) {
        if (f.t == "rect" || f.t == "rtri" || f.t !in POLY) return
        val q = pts(f)
        val n = q.size
        if (i !in 0 until n || !deg.isFinite()) return
        val d = deg.coerceIn(1.0, 179.0)
        val p = q[i]
        val prev = q[(i + n - 1) % n]
        val next = q[(i + 1) % n]
        val a1 = atan2(prev.y - p.y, prev.x - p.x)
        val a2 = atan2(next.y - p.y, next.x - p.x)
        val sgn = if (sin(a2 - a1) >= 0) 1.0 else -1.0
        val len = hypot(next.x - p.x, next.y - p.y)
        val na = a1 + sgn * d * PI / 180.0
        val np = q.toMutableList()
        np[(i + 1) % n] = Pt(p.x + len * cos(na), p.y + len * sin(na))
        if (!figOk(f.t, np)) return
        edit(f, "Angle " + labelAt(f.nm, i)) {
            f.p = np
            f.base = null
            f.sz = 1.0
            f.rot = 0.0
        }
    }

    // ---------- hold menu / double-tap menu ----------
    fun armHold(px: Offset) {
        holdFired = false
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(800)
            holdFired = true
            cursor = null
            draft = null
            holdAct(px)
        }
    }

    fun cancelHold() {
        holdJob?.cancel()
    }

    private fun holdAct(px: Offset) {
        val w = rawWorld(px)
        val h = hit(w)
        sel = h
        val items = buildList {
            if (h != null) {
                add(MenuItem("copy", "Copy", "📋"))
                add(MenuItem("cut", "Cut", "✂️"))
                add(MenuItem("del", "Delete", "🗑️"))
            }
            add(MenuItem("paste", "Paste", "📥"))
            if (h == null) add(MenuItem("pic", "Picture", "🖼️"))
        }
        menu = MenuState(px.x, px.y, items, w)
    }

    private fun cmItems(): List<MenuItem> = buildList {
        add(MenuItem("copy", "Copy", "📋"))
        add(MenuItem("cut", "Cut", "✂️"))
        add(MenuItem("del", "Delete", "🗑️"))
        add(MenuItem("det", "Details", "⋯"))
        if (hasClip) add(MenuItem("paste", "Paste", "📥"))
    }

    fun hideMenu() {
        menu = null
        sel = null
    }

    fun menuAction(key: String) {
        val m = menu
        val s = sel
        if (key == "det") {
            menu = null
            sel = null
            if (s != null && shapes.contains(s)) {
                detailsOn = true
                openEdit(s)
            }
            return
        }
        when (key) {
            "copy", "cut" -> if (s != null) copySel(s, key == "cut")
            "del" -> if (s != null) {
                delete(s)
                showToast("Deleted 🗑️ — undo (↶) brings it back")
            }
            "paste" -> if (m != null) pasteHere(m.at)
            "pic" -> pickImage()
        }
        hideMenu()
    }

    fun copySel(f: Fig, cut: Boolean) {
        val c = f.copyOf(System.nanoTime())
        clip = c
        clipGroup = listOf(c)
        internalStamp = clipStamp()
        if (cut) delete(f)
        showToast(if (cut) "Cut ✂️ — hold on the board to paste" else "Copied 📋 — hold on the board to paste")
    }

    fun paste(at: Pt) = pasteHere(at)

    /** Paste: the newest thing wins - something copied on the phone (words or a picture) or a figure copied here. */
    fun pasteHere(at: Pt?) {
        val sysNewer = clipStamp() != internalStamp
        if (hasClip && !sysNewer) {
            pasteFigs(clipGroup, at)
            return
        }
        when (val item = readClipboard()) {
            is ClipItem.Picture -> addImage(item.bmp, at)
            is ClipItem.Words -> addText(item.s, at)
            null -> if (hasClip) pasteFigs(clipGroup, at) else showToast("Nothing to paste yet — copy some text or a picture first")
        }
    }

    private fun viewCenter(): Pt = Pt(camera.wx(camera.w.toFloat() / 2f), camera.wy(camera.h.toFloat() / 2f))

    private fun pasteFigs(src: List<Fig>, at: Pt?) {
        val target = at ?: viewCenter()
        val bs = src.mapNotNull { figBounds(it) }
        if (bs.isEmpty()) return
        val cx = (bs.minOf { it[0] } + bs.maxOf { it[2] }) / 2
        val cy = (bs.minOf { it[1] } + bs.maxOf { it[3] }) / 2
        var dx = target.x - cx
        var dy = target.y - cy
        if (src.size == 1 && src[0].t != "ink") {
            val q = camera.snapStep
            dx = floor(dx / q + 0.5) * q
            dy = floor(dy / q + 0.5) * q
        }
        val born = System.nanoTime()
        val made = src.map { c ->
            val n = c.copyOf(born)
            n.layer = activeLayer
            n.p = c.p.map { Pt(it.x + dx, it.y + dy) }
            if (c.nm != null) n.nm = if (c.t == "seg") nextSegNames() else LB[lbN++ % LB.size]
            n
        }
        made.forEach { shapes.add(it) }
        bump(born)
        record(AddAct(made, if (made.size == 1) "Pasted " + figName(made[0]) else "Pasted ${made.size} things"))
        if (tool == "sk" && skDrag) group = made
        showToast("Pasted ✅")
    }

    // ---------- pictures and text ----------
    /** Put a picture on the board (from the clipboard or the gallery), centred on [at] or on the screen. */
    fun addImage(bmp: Bitmap, at: Pt? = null) {
        if (bmp.width < 1 || bmp.height < 1) return
        val key = ImageStore.put(bmp)
        val c = at ?: viewCenter()
        val wU = 0.6 * camera.w / camera.u
        val hU = wU * bmp.height / bmp.width
        val born = System.nanoTime()
        val f = Fig("img", listOf(Pt(c.x - wU / 2, c.y + hU / 2), Pt(c.x + wU / 2, c.y - hU / 2)), born)
        f.imgKey = key
        f.layer = activeLayer
        shapes.add(f)
        bump(born)
        record(AddAct(listOf(f), "Added a picture"))
        if (tool == "sk" && skDrag) {
            group = listOf(f)
        } else {
            selectTool("sel")
            openEdit(f)
        }
        showToast("Picture added — drag the corners to resize, ✂ to crop")
    }

    /** Estimate of how tall wrapped text is, so the box around it fits. */
    private fun fitText(f: Fig) {
        val w = (f.p[1].x - f.p[0].x).coerceAtLeast(f.fsz)
        val mt = MathCache.text(f.txt, (w / f.fsz).toFloat())
        val hWorld = (mt.h / MathCache.REF * f.fsz).toDouble().coerceAtLeast(f.fsz * 1.25)
        f.p = listOf(f.p[0], Pt(f.p[0].x + w, f.p[0].y - hWorld))
    }

    fun addText(s: String, at: Pt? = null) {
        val t = s.trim('\n', '\r').take(4000)
        if (t.isBlank()) {
            showToast("Nothing to paste yet — copy some text or a picture first")
            return
        }
        val c = at ?: viewCenter()
        val wU = 0.7 * camera.w / camera.u
        val born = System.nanoTime()
        val f = Fig("txt", listOf(Pt(c.x - wU / 2, c.y), Pt(c.x + wU / 2, c.y)), born)
        f.txt = t
        f.fsz = wU / 20
        f.col = penCol
        f.layer = activeLayer
        fitText(f)
        shapes.add(f)
        bump(born)
        record(AddAct(listOf(f), "Pasted text"))
        if (tool == "sk" && skDrag) {
            group = listOf(f)
        } else {
            selectTool("sel")
            openEdit(f)
        }
        showToast("Text pasted — drag it to place it, ⤢ to resize")
    }

    fun setText(f: Fig, s: String) {
        edit(f, "Edited text") {
            f.txt = s.take(4000)
            fitText(f)
        }
    }

    fun setFontSize(f: Fig, size: Double) {
        edit(f, "Text size") {
            f.fsz = size.coerceIn(0.05, 20.0)
            fitText(f)
        }
    }

    /** Corner handles of a picture (keeps its shape) or text box (changes the width). */
    private fun dragBoxHandle(f: Fig, i: Int, px: Offset) {
        val w = rawWorld(px)
        val a = f.p[0]
        val b = f.p[1]
        if (f.t == "txt") {
            if (i == 1) {
                val nx = maxOf(w.x, a.x + f.fsz * 2)
                edit(f, "Text width") {
                    f.p = listOf(a, Pt(nx, b.y))
                    fitText(f)
                }
            } else {
                // top-left corner: slide the whole text
                val dx = w.x - a.x
                val dy = w.y - a.y
                edit(f, "Moved text") { f.p = listOf(Pt(a.x + dx, a.y + dy), Pt(b.x + dx, b.y + dy)) }
            }
            return
        }
        val aspect = (b.x - a.x) / (a.y - b.y)
        if (!aspect.isFinite() || aspect <= 0.0) return
        if (i == 1) {
            val nw = maxOf(w.x - a.x, 0.1)
            edit(f, "Resized picture") { f.p = listOf(a, Pt(a.x + nw, a.y - nw / aspect)) }
        } else {
            val nw = maxOf(b.x - w.x, 0.1)
            edit(f, "Resized picture") { f.p = listOf(Pt(b.x - nw, b.y + nw / aspect), b) }
        }
    }

    // ---------- crop ----------
    fun startCrop(f: Fig) {
        if (f.t != "img") return
        cropping = f
        mv = null
        rs = null
        showToast("Crop ✂ — drag a corner inwards, press ✓ when done")
    }

    fun endCrop() {
        cropping = null
    }

    fun resetCrop(f: Fig) {
        val c = f.crop
        val w = f.p[1].x - f.p[0].x
        val h = f.p[0].y - f.p[1].y
        val sx = w / (c[2] - c[0])
        val sy = h / (c[3] - c[1])
        edit(f, "Reset crop") {
            f.p = listOf(Pt(f.p[0].x - c[0] * sx, f.p[0].y + c[1] * sy), Pt(f.p[1].x + (1f - c[2]) * sx, f.p[1].y - (1f - c[3]) * sy))
            f.crop = listOf(0f, 0f, 1f, 1f)
        }
    }

    /** Which corner (0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left) of the picture being cropped is under the finger. */
    fun cropHandleAt(px: Offset): Int {
        val f = cropping ?: return -1
        if (!shapes.contains(f)) return -1
        val a = f.p[0]
        val b = f.p[1]
        val corners = listOf(Pt(a.x, a.y), Pt(b.x, a.y), Pt(b.x, b.y), Pt(a.x, b.y))
        var best = 34f * camera.density.toFloat()
        var idx = -1
        corners.forEachIndexed { i, q ->
            val d = hypot(camera.sx(q.x) - px.x, camera.sy(q.y) - px.y)
            if (d < best) {
                best = d
                idx = i
            }
        }
        return idx
    }

    fun dragCrop(i: Int, px: Offset) {
        val f = cropping ?: return
        val w = rawWorld(px)
        val c = f.crop
        val a = f.p[0]
        val b = f.p[1]
        val sxF = (b.x - a.x) / (c[2] - c[0])
        val syF = (a.y - b.y) / (c[3] - c[1])
        if (!sxF.isFinite() || !syF.isFinite() || sxF <= 0 || syF <= 0) return
        val minF = 0.08
        var nl = c[0].toDouble()
        var nt = c[1].toDouble()
        var nr = c[2].toDouble()
        var nb = c[3].toDouble()
        var ax = a.x
        var ay = a.y
        var bx = b.x
        var by = b.y
        if (i == 0 || i == 3) {
            val x = w.x.coerceIn(a.x - c[0] * sxF, b.x - minF * sxF)
            nl = c[0] + (x - a.x) / sxF
            ax = x
        }
        if (i == 1 || i == 2) {
            val x = w.x.coerceIn(a.x + minF * sxF, b.x + (1 - c[2]) * sxF)
            nr = c[2] + (x - b.x) / sxF
            bx = x
        }
        if (i == 0 || i == 1) {
            val y = w.y.coerceIn(b.y + minF * syF, a.y + c[1] * syF)
            nt = c[1] + (a.y - y) / syF
            ay = y
        }
        if (i == 2 || i == 3) {
            val y = w.y.coerceIn(b.y - (1 - c[3]) * syF, a.y - minF * syF)
            nb = c[3] + (b.y - y) / syF
            by = y
        }
        edit(f, "Cropped picture") {
            f.crop = listOf(nl.toFloat(), nt.toFloat(), nr.toFloat(), nb.toFloat())
            f.p = listOf(Pt(ax, ay), Pt(bx, by))
        }
    }

    // ---------- tapping empty board: 2 taps = paste menu, 3 taps = choose a picture ----------
    fun onBlankTap(px: Offset) {
        if (tool != "sel" && tool != "pan" && !(tool == "sk" && skDrag)) return
        blankCount = if (blankCount > 0) blankCount + 1 else 1
        blankAt = px
        blankJob?.cancel()
        blankJob = scope.launch {
            delay(350)
            val n = blankCount
            blankCount = 0
            val w = rawWorld(blankAt)
            if (n == 2) {
                menu = MenuState(blankAt.x, blankAt.y, listOf(MenuItem("paste", "Paste", "📥"), MenuItem("pic", "Picture", "🖼️")), w)
            } else if (n >= 3) {
                pickImage()
            }
        }
    }

    // ---------- sketch "Drag": a box that selects what is inside ----------
    fun beginBox(pos: Offset) {
        group = emptyList()
        pending = emptyList()
        pendRect = null
        boxA = pos
        boxB = pos
    }

    fun moveBox(pos: Offset) {
        boxB = pos
    }

    fun endBox(moved: Boolean) {
        val a = boxA
        val b = boxB
        boxA = null
        boxB = null
        if (a == null || b == null || !moved) return
        val p = rawWorld(a)
        val q = rawWorld(b)
        val x0 = minOf(p.x, q.x)
        val x1 = maxOf(p.x, q.x)
        val y0 = minOf(p.y, q.y)
        val y1 = maxOf(p.y, q.y)
        val found = shapes.filter { f ->
            f.dieAt == 0L && f.t != "fx" && f.t != "unit" && figVisible(f) && !figLocked(f) && run {
                val ss = samplesOf(f)
                ss.isNotEmpty() && ss.count { it.x in x0..x1 && it.y in y0..y1 } * 2 > ss.size
            }
        }
        group = emptyList()
        if (found.isEmpty()) {
            showToast("Nothing inside that box — draw it again")
            return
        }
        pending = found
        pendRect = doubleArrayOf(x0, y0, x1, y1)
        showToast("${found.size} caught — tap ✓ to select them, ✗ to cancel")
    }

    /** The tick: the caught things become the selection (and are checked for parallel lines). */
    fun confirmPending() {
        if (pending.isEmpty()) return
        group = pending
        grpDetailsOpen = false
        pending = emptyList()
        pendRect = null
        if (tool == "sel") selContent = false
        parIdx = 0
        pxSeen = false
        refreshPar()
        showToast("Selected ✅ — drag to move, corner to resize, use the bar below")
    }

    /** The cross: forget the box. */
    fun cancelPending() {
        pending = emptyList()
        pendRect = null
    }

    /** The box round the selection (board units), or null. */
    fun groupBounds(): DoubleArray? {
        val bs = group.filter { it.dieAt == 0L }.mapNotNull { figBounds(it) }
        if (bs.isEmpty()) return null
        return doubleArrayOf(bs.minOf { it[0] }, bs.minOf { it[1] }, bs.maxOf { it[2] }, bs.maxOf { it[3] })
    }

    /** 0 = outside, 1 = inside (move), 2 = on the resize corner. */
    fun groupZone(px: Offset): Int {
        val b = groupBounds() ?: return 0
        val pad = 4f * camera.density.toFloat()
        val l = camera.sx(b[0]) - pad
        val t = camera.sy(b[3]) - pad
        val r = camera.sx(b[2]) + pad
        val bt = camera.sy(b[1]) + pad
        if (hypot(px.x - r, px.y - bt) < 30f * camera.density.toFloat()) return 2
        return if (px.x in (l - pad)..(r + pad) && px.y in (t - pad)..(bt + pad)) 1 else 0
    }

    private fun beginGroupDrag(px: Offset) {
        gBefore = group.map { it.snapshot() }
        gOrig = group.map { GOrig(it, it.p, it.sd, it.gb, it.fsz) }
        gStart = rawWorld(px)
    }

    fun beginGroupMove(px: Offset) = beginGroupDrag(px)

    fun dragGroupMove(px: Offset) {
        val w = rawWorld(px)
        val dx = w.x - gStart.x
        val dy = w.y - gStart.y
        for (o in gOrig) {
            o.f.p = o.p.map { Pt(it.x + dx, it.y + dy) }
            o.f.base = null
        }
    }

    fun beginGroupResize(px: Offset) {
        beginGroupDrag(px)
        val b = groupBounds() ?: return
        gAnchor = Pt(b[0], b[3])
        gD0 = maxOf(dist(Pt(b[2], b[1]), gAnchor), 1e-9)
    }

    fun dragGroupResize(px: Offset) {
        val k = (dist(rawWorld(px), gAnchor) / gD0).coerceIn(0.1, 10.0)
        for (o in gOrig) {
            val f = o.f
            f.p = o.p.map { Pt(gAnchor.x + (it.x - gAnchor.x) * k, gAnchor.y + (it.y - gAnchor.y) * k) }
            f.base = null
            when (f.t) {
                "sol" -> f.sd = o.sd.mapValues { e -> (e.value * k).coerceIn(0.3, 12.0) }
                "trig" -> f.gb = (o.gb * k).coerceIn(0.5, 12.0)
                "txt" -> f.fsz = (o.fsz * k).coerceIn(0.02, 60.0)
            }
        }
    }

    /** The finger lifted after moving or resizing the selection: one undo step for all of it. */
    fun endGroupDrag(label: String) {
        if (gOrig.isEmpty()) return
        val fs = gOrig.map { it.f }
        val after = fs.map { it.snapshot() }
        if (after != gBefore) record(MultiEditAct(fs, gBefore, after, label))
        gOrig = emptyList()
        bump()
    }

    fun copyGroup(cut: Boolean) {
        val fs = group.filter { it.dieAt == 0L }
        if (fs.isEmpty()) return
        clipGroup = fs.map { it.copyOf(System.nanoTime()) }
        clip = null
        internalStamp = clipStamp()
        if (cut) deleteGroup()
        showToast(if (cut) "Cut ✂️ — tap Paste to put it back" else "Copied 📋 — tap Paste")
    }

    fun deleteGroup() {
        val fs = group.filter { it.dieAt == 0L }
        if (fs.isEmpty()) return
        record(RemoveAct(fs, fs.map { shapes.indexOf(it) }, "Deleted ${fs.size} things"))
        group = emptyList()
        fs.forEach { softRemove(it) }
    }

    fun clearGroup() {
        group = emptyList()
        grpDetailsOpen = false
        pending = emptyList()
        pendRect = null
        pxSeen = false
        refreshPar()
    }

    // ---------- the bar under a selection: colour, size, nudge, maths ----------
    fun setGroupColor(c: Color) {
        val fs = group.filter { it.dieAt == 0L && it.t != "img" }
        if (fs.isEmpty()) return
        val before = fs.map { it.snapshot() }
        fs.forEach { it.col = c }
        record(MultiEditAct(fs, before, fs.map { it.snapshot() }, "Colour"))
        bump()
    }

    fun beginGroupScale() {
        gBefore = group.map { it.snapshot() }
        gOrig = group.map { GOrig(it, it.p, it.sd, it.gb, it.fsz) }
        val b = groupBounds() ?: return
        gAnchor = Pt((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
    }

    fun scaleGroupTo(k0: Double) {
        val k = k0.coerceIn(0.1, 10.0)
        for (o in gOrig) {
            val f = o.f
            f.p = o.p.map { Pt(gAnchor.x + (it.x - gAnchor.x) * k, gAnchor.y + (it.y - gAnchor.y) * k) }
            f.base = null
            when (f.t) {
                "sol" -> f.sd = o.sd.mapValues { e -> (e.value * k).coerceIn(0.3, 12.0) }
                "trig" -> f.gb = (o.gb * k).coerceIn(0.5, 12.0)
                "txt" -> f.fsz = (o.fsz * k).coerceIn(0.02, 60.0)
            }
        }
    }

    /** Move the whole selection by a few finger-widths. */
    fun nudgeGroup(dxPx: Float, dyPx: Float) {
        val fs = group.filter { it.dieAt == 0L }
        if (fs.isEmpty()) return
        val dx = dxPx / camera.u
        val dy = -dyPx / camera.u
        val before = fs.map { it.snapshot() }
        fs.forEach { f ->
            f.p = f.p.map { Pt(it.x + dx, it.y + dy) }
            f.base = null
        }
        record(MultiEditAct(fs, before, fs.map { it.snapshot() }, "Moved selection"))
        bump()
    }

    fun inkThicker(factor: Double) {
        group.filter { it.t == "ink" }.forEach { it.inkW = (it.inkW * factor).coerceIn(1.0, 40.0) }
        bump()
    }

    /** The K button: text such as x^2 - 1 or \frac{a}{b} becomes neat symbols. Works on selected or opened text. */
    fun applyMath() {
        val targets = (if (group.isNotEmpty()) group else listOfNotNull(editing)).filter { it.t == "txt" && it.dieAt == 0L }
        if (targets.isEmpty()) {
            showToast("K works on text — add or paste some text, then select it")
            return
        }
        targets.forEach { fitText(it) }
        bump()
        showToast("Maths shows neatly by itself: type a/b, sqrt(x), x^2, pi … (see ? → KaTeX)")
    }

    /** Crop button of the selection bar. */
    fun cropGroup() {
        val img = group.singleOrNull { it.t == "img" } ?: editing?.takeIf { it.t == "img" }
        if (img != null && group.size <= 1) {
            openEdit(img)
            startCrop(img)
        } else {
            showToast("Crop trims one picture. To keep less of a selection, draw a smaller box")
        }
    }

    // ---------- Answer tab: pick a figure, type its sizes, it is drawn with its numbers ----------
    private fun fitView(w: Double, h: Double) {
        val ext = maxOf(w, h, 1e-6)
        val tu = 0.55 * minOf(camera.w, camera.h) / ext
        camera.z = (tu / camera.u0).coerceIn(0.01, 500.0)
        camera.ox = 0.0
        camera.oy = if (tool in setOf("fx", "dv", "trig", "unit")) -70.0 * camera.density else 0.0
    }

    fun makeAnswer(kind: String, v: Map<String, Double>): Boolean {
        fun g(k: String) = v[k] ?: 0.0
        val born = System.nanoTime()
        var pts0: List<Pt>? = null
        var type = kind
        when (kind) {
            "tri" -> {
                val a = g("a")
                val b = g("b")
                val c = g("c")
                if (a <= 0 || b <= 0 || c <= 0 || a + b <= c || a + c <= b || b + c <= a) {
                    showToast("Those sides can not make a triangle — each side must be shorter than the other two together")
                    return false
                }
                val x = (c * c + b * b - a * a) / (2 * c)
                val y = sqrt(maxOf(b * b - x * x, 0.0))
                pts0 = listOf(Pt(0.0, 0.0), Pt(c, 0.0), Pt(x, y))
            }
            "rtri" -> {
                if (g("b") <= 0 || g("h") <= 0) return badSize()
                pts0 = listOf(Pt(0.0, 0.0), Pt(g("b"), g("h")))
            }
            "rect" -> {
                if (g("w") <= 0 || g("h") <= 0) return badSize()
                pts0 = listOf(Pt(0.0, 0.0), Pt(g("w"), g("h")))
            }
            "sq" -> {
                if (g("s") <= 0) return badSize()
                type = "rect"
                pts0 = listOf(Pt(0.0, 0.0), Pt(g("s"), g("s")))
            }
            "quad" -> {
                val ab = g("ab")
                val bc = g("bc")
                val cd = g("cd")
                val da = g("da")
                val ac = g("ac")
                if (ab <= 0 || bc <= 0 || cd <= 0 || da <= 0 || ac <= 0 ||
                    ab + bc <= ac || ab + ac <= bc || bc + ac <= ab ||
                    cd + da <= ac || cd + ac <= da || da + ac <= cd
                ) {
                    showToast("Those sizes can not make a quadrilateral — check the diagonal AC against the sides")
                    return false
                }
                val bx = ab
                val cx = (ab * ab + ac * ac - bc * bc) / (2 * ab)
                val cy = sqrt(maxOf(ac * ac - cx * cx, 0.0))
                val ux = cx / ac
                val uy = cy / ac
                val nx = -uy
                val ny = ux
                val xd = (da * da + ac * ac - cd * cd) / (2 * ac)
                val yd = sqrt(maxOf(da * da - xd * xd, 0.0))
                val side = if (nx * bx > 0) 1.0 else -1.0
                pts0 = listOf(Pt(0.0, 0.0), Pt(bx, 0.0), Pt(cx, cy), Pt(xd * ux - side * yd * nx, xd * uy - side * yd * ny))
            }
            "pent", "hex" -> {
                val n = if (kind == "pent") 5 else 6
                val side = g("s")
                if (side <= 0) return badSize()
                val r = side / (2 * sin(PI / n))
                pts0 = List(n) { i -> Pt(r * cos(PI / 2 + 2 * PI * i / n), r * sin(PI / 2 + 2 * PI * i / n)) }
            }
            "circ" -> {
                if (g("r") <= 0) return badSize()
                pts0 = listOf(Pt(0.0, 0.0), Pt(g("r"), 0.0))
            }
            "cube", "cuboid", "pyr", "prism", "cyl", "cone", "sph" -> {
                if (v.values.any { it <= 0 }) return badSize()
                val f = Fig("sol", listOf(Pt(0.0, 0.0)), born)
                f.layer = activeLayer
                f.sk = kind
                f.sd = v
                if (kind in setOf("cyl", "cone", "pyr", "cuboid", "prism")) f.sflags = setOf("hgt")
                shapes.add(f)
                bump(born)
                record(AddAct(listOf(f), "Answer: " + (SOLN[kind] ?: "solid")))
                fitView(2.6 * (v.values.maxOrNull() ?: 3.0), 2.6 * (v.values.maxOrNull() ?: 3.0))
                selectTool("sel")
                openEdit(f)
                showToast("Drawn ✅ — rotate it by dragging, edit the sizes in the panel")
                return true
            }
            else -> return false
        }
        val p = pts0 ?: return false
        if (!figOk(type, p)) {
            showToast("Those sizes do not make a valid figure — check them")
            return false
        }
        val xs = p.map { it.x }
        val ys = p.map { it.y }
        val xmn = xs.minOrNull() ?: 0.0
        val xmx = xs.maxOrNull() ?: 0.0
        val ymn = ys.minOrNull() ?: 0.0
        val ymx = ys.maxOrNull() ?: 0.0
        var cx0 = (xmn + xmx) / 2
        var cy0 = (ymn + ymx) / 2
        var w0 = xmx - xmn
        var h0 = ymx - ymn
        if (type == "circ") {
            val r = dist(p[0], p[1])
            cx0 = p[0].x
            cy0 = p[0].y
            w0 = 2 * r
            h0 = 2 * r
        }
        val moved = p.map { Pt(it.x - cx0, it.y - cy0) }
        val nm = if (type in POLY) LB[lbN++ % LB.size] else if (type == "seg") nextSegNames() else null
        val f = Fig(type, moved, born, nm)
        f.layer = activeLayer
        if (type == "circ") f.ov = setOf("rad")
        shapes.add(f)
        bump(born)
        record(AddAct(listOf(f), "Answer: " + figName(f)))
        fitView(w0, h0)
        selectTool("sel")
        openEdit(f)
        showToast("Drawn ✅ — write on it with the pen, or pick Answer again for another")
        return true
    }

    /** What the Question tab put on the board last: the figures and the solution text written beside them. */
    private var qFigs: List<Fig> = emptyList()
    private var qText: Fig? = null
    private var qBox = doubleArrayOf(0.0, 0.0, 1.0, 1.0) // left, top, width, font size of the solution text

    private fun removeQuestionDrawing() {
        val all = qFigs + listOfNotNull(qText)
        val alive = all.filter { it.dieAt == 0L && shapes.contains(it) }
        if (alive.isNotEmpty()) {
            record(RemoveAct(alive, alive.map { shapes.indexOf(it) }, "Question: removed the old drawing"))
            alive.forEach { softRemove(it) }
        }
        qFigs = emptyList()
        qText = null
    }

    /** Question tab: clear what the last question drew. */
    fun clearQuestionDrawing() {
        removeQuestionDrawing()
    }

    /**
     * Question tab, step 1: take away the old drawing, put the checked figure on the board (if the question has one)
     * and reserve the place under it where the solution will be written line by line.
     */
    fun beginQuestion(plan: QPlan?): Boolean {
        removeQuestionDrawing()
        if (plan == null) {
            val c = viewCenter()
            val wU = 0.8 * camera.w / camera.u
            qBox = doubleArrayOf(c.x - wU / 2, c.y + wU * 0.25, wU, wU / 24)
            return true
        }
        val specs: List<QFigSpec> = if (plan.figs.isNotEmpty()) plan.figs else listOf(
            QFigSpec(if (plan.pts.size == 4) "quad" else "tri", plan.pts, plan.names, plan.showSides, plan.showAngles, plan.extras),
        )
        val all = specs.flatMap { it.pts }
        if (all.isEmpty()) return false
        val xs = all.map { it.x }
        val ys = all.map { it.y }
        val cx0 = ((xs.minOrNull() ?: 0.0) + (xs.maxOrNull() ?: 0.0)) / 2
        val cy0 = ((ys.minOrNull() ?: 0.0) + (ys.maxOrNull() ?: 0.0)) / 2
        val w0 = (xs.maxOrNull() ?: 0.0) - (xs.minOrNull() ?: 0.0)
        val h0 = (ys.maxOrNull() ?: 0.0) - (ys.minOrNull() ?: 0.0)
        val size = maxOf(w0, h0)
        if (size <= 1e-9) return false
        val gap = 0.12 * size
        val textW = maxOf(1.7 * size, w0)
        val textH = 1.3 * size
        val figTop = (h0 + gap + textH) / 2
        val figCy = figTop - h0 / 2
        val born = System.nanoTime()
        val made = mutableListOf<Fig>()
        for ((i, sp) in specs.withIndex()) {
            val moved = sp.pts.map { Pt(it.x - cx0, it.y - cy0 + figCy) }
            if (!figOk(sp.t, moved)) continue
            val f = Fig(sp.t, moved, born + i, sp.nm)
            f.layer = activeLayer
            val hide = defaultHide(f).toMutableSet()
            for (k in sp.showSides) hide.remove("s$k")
            for (k in sp.showAngles) hide.remove("a$k")
            f.hide = hide
            f.ov = sp.ov
            made.add(f)
        }
        if (made.isEmpty()) {
            showToast("The figure was too thin to draw")
            return false
        }
        for (f in made) shapes.add(f)
        bump(born + made.size)
        record(AddAct(made, "Question: " + plan.title))
        qFigs = made
        qBox = doubleArrayOf(-textW / 2, figCy - h0 / 2 - gap, textW, size * 0.075)
        fitView(textW, h0 + gap + textH)
        return true
    }

    /** Question tab, step 2: write one more line of the solution on the board (typed maths is drawn as fractions, powers, roots). */
    fun writeSolution(line: String) {
        val cur = qText
        if (cur == null || cur.dieAt != 0L || !shapes.contains(cur)) {
            val born = System.nanoTime()
            val f = Fig("txt", listOf(Pt(qBox[0], qBox[1]), Pt(qBox[0] + qBox[2], qBox[1])), born)
            f.txt = line
            f.fsz = qBox[3]
            f.col = Color(0xFFFFE2B8)
            f.layer = activeLayer
            fitText(f)
            shapes.add(f)
            bump(born)
            record(AddAct(listOf(f), "Question: solution"))
            qText = f
        } else {
            cur.txt = cur.txt + "\n" + line
            fitText(cur)
            bump()
        }
    }

    /** The person pressed ✓ — the figure and the solution stay; the next question will not remove them. */
    fun keepQuestionDrawing() {
        qFigs = emptyList()
        qText = null
        showToast("Kept on the board")
    }

    private fun badSize(): Boolean {
        showToast("Type sizes bigger than zero")
        return false
    }
}
