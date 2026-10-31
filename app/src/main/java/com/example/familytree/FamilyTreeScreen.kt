package com.example.familytree

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.unit.sp

/** ডায়ালগ খোলার তথ্য: person = null মানে নতুন সদস্য, সাথে বাবা/মায়ের ডিফল্ট */
private data class DialogState(
    val person: Person?,
    val defaultFatherId: Long? = null,
    val defaultMotherId: Long? = null
)

private enum class Pos { NONE, ONLY, FIRST, MIDDLE, LAST }

/** সেটিংস অনুযায়ী ট্রিতে কী দেখানো হবে: স্ত্রীর নাম, আর যাদের লুকানো হয়েছে */
private data class TreeOpts(val showWives: Boolean, val hidden: Set<Long>)

/**
 * ডায়ালগের বাবা/মা/স্ত্রীর ঘর: id থাকলে তালিকা থেকে বাছা, না থাকলে লেখা নাম।
 * origId: স্ত্রীর আগের সদস্যের id (নাম বদলালে নতুন সদস্য না বানিয়ে নামটাই বদলাবে)
 */
private data class PickEntry(val id: Long?, val text: String, val origId: Long? = null)

private fun toLink(e: PickEntry, options: List<Person>): Link {
    val t = e.text.trim()
    if (e.id != null) return Link(e.id, null)
    if (t.isEmpty()) return Link(null, null)
    if (e.origId != null) return Link(e.origId, null, rename = t)
    val m = options.filter { it.name == t }
    return if (m.size == 1) Link(m[0].id, null) else Link(null, t)
}

/** প্রতিটি পরিবারের (একই পিতা-মাতার সন্তানদের) জন্য আলাদা রঙ */
private val Palette = listOf(
    Color(0xFF1E88E5), Color(0xFF00897B), Color(0xFFEF6C00), Color(0xFF8E24AA),
    Color(0xFFE53935), Color(0xFF43A047), Color(0xFFD81B60), Color(0xFF3949AB),
    Color(0xFF6D4C41), Color(0xFF00838F)
)

/** একজনের সব স্বামী/স্ত্রী, ক্রম অনুযায়ী */
private fun spousesOf(p: Person, all: List<Person>): List<Person> {
    val map = LinkedHashMap<Long, Person>()
    p.spouseId?.let { sid -> all.find { it.id == sid } }?.let { map[it.id] = it }
    all.filter { it.spouseId == p.id && it.id != p.id }.forEach { map.putIfAbsent(it.id, it) }
    return map.values.sortedWith(compareBy({ it.spouseOrder }, { it.id }))
}

private fun hasParents(p: Person, byId: Map<Long, Person>): Boolean =
    (p.fatherId?.let { byId[it] } != null) || (p.motherId?.let { byId[it] } != null)

/** যাদের বাবা-মা নেই, তারাই ট্রির শুরু */
private fun findRoots(all: List<Person>): List<Person> {
    val byId = all.associateBy { it.id }
    return all.filter { p ->
        if (hasParents(p, byId)) return@filter false
        val sp = spousesOf(p, all)
        if (sp.any { hasParents(it, byId) }) return@filter false
        sp.none { s -> (s.gender == p.gender && s.id < p.id) || (p.gender == "F" && s.gender == "M") }
    }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
}

private fun childrenOf(person: Person, spouses: List<Person>, all: List<Person>, exclude: Set<Long>): List<Person> {
    val ids = HashSet<Long>().apply { add(person.id); spouses.forEach { add(it.id) } }
    return all.filter { c ->
        c.id !in exclude && c.id !in ids &&
            (c.fatherId?.let { it in ids } == true || c.motherId?.let { it in ids } == true)
    }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
}

/** কার বাটন কোন রঙের: একই পিতা-মাতার সব সন্তানের একই রঙ */
private fun assignColors(roots: List<Person>, all: List<Person>): Map<Long, Color> {
    val map = HashMap<Long, Color>()
    roots.forEach { map[it.id] = Palette[0] }
    var counter = 1
    val visited = HashSet<Long>()
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        val p = queue.removeFirst()
        if (!visited.add(p.id)) continue
        val own = map[p.id] ?: Palette[0]
        val spouses = spousesOf(p, all)
        spouses.forEach { map.putIfAbsent(it.id, own) }
        val kids = childrenOf(p, spouses, all, visited)
        if (kids.isNotEmpty()) {
            val color = Palette[counter++ % Palette.size]
            kids.forEach { map.putIfAbsent(it.id, color); queue.add(it) }
        }
    }
    return map
}

/** কার সন্তান হবে: (বাবা, মা) */
private fun parentsOf(p: Person, spouse: Person?): Pair<Long?, Long?> =
    if (p.gender == "M") p.id to spouse?.id else spouse?.id to p.id

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun nextLayout(cur: String) = when (cur) {
    "top" -> "left"
    "left" -> "list"
    "list" -> "round"
    else -> "top"
}

// ---------- লাইন অ্যানিমেশন ----------

/**
 * লাইনগুলো একটার পর একটা আঁকা হয় (কোনো নির্দিষ্ট মোট সময় নেই, প্রতিটি ধাপের একই গতি)।
 * t = এখন কত ধাপ চলছে; p(start, len) = start ধাপ থেকে len ধাপ সময়ে কতটা আঁকা হয়েছে (০ থেকে ১)।
 * লাইনের মাথায় একটু বড় বল টানতে টানতে যায়।
 */
private class LineAnim(val t: State<Float>) {
    fun p(start: Float, len: Float = 1f): Float = ((t.value - start) / len).coerceIn(0f, 1f)
}

private fun DrawScope.seg(color: Color, a: Offset, b: Offset, p: Float, sw: Float, ball: Float) {
    if (p <= 0f) return
    val end = Offset(a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p)
    drawLine(color, a, end, sw)
    if (p < 1f) drawCircle(color, ball, end)
}

/**
 * এক নাম থেকে সব সন্তানের দিকের লাইন: মাঝখান (center) থেকে শুরু হয়ে একসাথে দুই দিকে ছড়ায়,
 * ছড়ানো লাইন যে সন্তানের কাছে পৌঁছায় তার নামার লাইন নিজে থেকেই শুরু হয়।
 * vertical = false: আড়াআড়ি লাইন, নামার লাইন নিচের দিকে (উপর থেকে নিচে ধরন)
 * vertical = true: খাড়া লাইন, নামার লাইন ডানের দিকে (বাম থেকে ডানে ধরন)
 * centers = প্রতি সন্তানের কেন্দ্র (লাইনের অক্ষ বরাবর), pb = ছড়ানোর অগ্রগতি (০ থেকে ১)
 */
private fun DrawScope.drawSpread(
    color: Color,
    vertical: Boolean,
    centers: List<Float>,
    center: Float,
    pb: Float,
    drop: Float,
    sw: Float,
    ball: Float
) {
    val n = centers.size
    if (n == 0 || pb <= 0f) return
    fun pt(axis: Float, across: Float) = if (vertical) Offset(across, axis) else Offset(axis, across)
    val left = centers.first()
    val right = centers.last()
    val lSpan = center - left
    val rSpan = right - center
    if (n > 1) {
        if (lSpan > 0f) seg(color, pt(center, 0f), pt(left, 0f), pb, sw, ball)
        if (rSpan > 0f) seg(color, pt(center, 0f), pt(right, 0f), pb, sw, ball)
    }
    centers.forEach { c ->
        val f = when {
            c <= center -> if (lSpan > 0f) (center - c) / lSpan else 0f
            else -> if (rSpan > 0f) (c - center) / rSpan else 0f
        }
        val pd = if (n == 1) pb else ((pb - f * 0.65f) / 0.35f).coerceIn(0f, 1f)
        seg(color, pt(c, 0f), pt(c, drop), pd, sw, ball)
    }
}

@Composable
private fun LineBox(color: Color, w: Dp, h: Dp, vertical: Boolean, progress: () -> Float) {
    Spacer(
        Modifier.size(w, h).drawBehind {
            val p = progress()
            if (p > 0f) {
                val a = if (vertical) Offset(size.width / 2, 0f) else Offset(0f, size.height / 2)
                val b = if (vertical) Offset(size.width / 2, size.height) else Offset(size.width, size.height / 2)
                seg(color, a, b, p, 3.dp.toPx(), 5.dp.toPx())
            }
        }
    )
}

/** একজনের সন্তানদের লাইন আঁকতে কত ধাপ সময় লাগে */
private fun connLen(layout: String, n: Int): Float = when (layout) {
    "list" -> 2f * n
    "round" -> 1f * n
    else -> if (n == 1) 1f else 1.5f + 0.5f * n
}

/**
 * এক ধাপ আঁকতে কত মিলিসেকেন্ড: ধাপ কম হলে (কম সদস্য) ধীরে, বেশি হলে দ্রুত।
 * ৪ ধাপে ≈৪৪০ms, ৬০ ধাপে ≈১৬৭ms, ২০০ ধাপে ≈৬৫ms, তার বেশিতে আরও দ্রুত।
 */
private fun msPerUnit(total: Float): Float =
    (500f / (1f + total / 30f)).coerceIn(20f, 450f)

/** প্রজন্ম ধরে দেখানো সদস্যের সংখ্যা (হাইড করা বাদ; স্ত্রীর নাম চালু থাকলে স্ত্রীসহ) */
private fun generationCounts(roots: List<Person>, all: List<Person>, opts: TreeOpts): List<Pair<String, Int>> {
    val counts = ArrayList<Pair<String, Int>>()
    var level: List<Pair<Person, Set<Long>>> = roots.map { it to emptySet<Long>() }
    while (level.isNotEmpty()) {
        var n = 0
        val next = ArrayList<Pair<Person, Set<Long>>>()
        level.forEach { (p, path) ->
            val sp = spousesOf(p, all)
            n += 1 + (if (opts.showWives) sp.size else 0)
            val np = path + p.id + sp.map { it.id }
            childrenOf(p, sp, all, path).filter { it.id !in opts.hidden }.forEach { next.add(it to np) }
        }
        counts.add(level.first().first.name to n)
        level = next
    }
    return counts
}

private fun bn(n: Int): String =
    n.toString().map { if (it in '0'..'9') "০১২৩৪৫৬৭৮৯"[it - '0'] else it }.joinToString("")

/** প্রতিটি সদস্যের লাইনগুলো কত ধাপ থেকে শুরু, আর মোট কত ধাপ */
private class Seq(val base: Map<Long, Float>, val total: Float)

/** প্রজন্ম ধরে, প্রতি প্রজন্মে বাম থেকে ডানে ধাপ বসানো */
private fun buildSeq(roots: List<Person>, all: List<Person>, opts: TreeOpts, layout: String): Seq {
    val hasStem = layout == "top" || layout == "left"
    val base = HashMap<Long, Float>()
    var next = 0f
    val queue = ArrayDeque<Pair<Person, Set<Long>>>()
    roots.forEach { queue.add(it to emptySet()) }
    while (queue.isNotEmpty()) {
        val (p, path) = queue.removeFirst()
        val sp = spousesOf(p, all)
        val kids = childrenOf(p, sp, all, path).filter { it.id !in opts.hidden }
        base[p.id] = next
        next += (if (hasStem && kids.isNotEmpty()) 1f else 0f) +
            (if (kids.isNotEmpty()) connLen(layout, kids.size) else 0f)
        val np = path + p.id + sp.map { it.id }
        kids.forEach { queue.add(it to np) }
    }
    return Seq(base, next)
}

private class TreeCtx(
    val all: List<Person>,
    val colors: Map<Long, Color>,
    val opts: TreeOpts,
    val anim: LineAnim,
    val layout: String,
    val base: Map<Long, Float>,
    val offsets: Map<Long, Offset>,
    val onOffset: (Long, Offset) -> Unit,
    val onEdit: (Person, Person?) -> Unit,
    val onReorder: (List<Long>) -> Unit
) {
    val hasStem: Boolean get() = layout == "top" || layout == "left"
}

/** এক সদস্যের নিজের লাইনগুলোর শুরুর ধাপ (-১ মানে এই লাইন নেই) */
private class NodeUnits(val spouse: Float, val stem: Float, val conn: Float)

private fun unitsOf(ctx: TreeCtx, id: Long, hasSp: Boolean, hasKids: Boolean): NodeUnits {
    // স্ত্রীর নাম এখন স্বামীর বাক্সের ভেতরেই, তাই আলাদা লাইন নেই (hasSp অগ্রাহ্য)
    var o = ctx.base[id] ?: 0f
    val sp = -1f
    var stem = -1f
    if (ctx.hasStem && hasKids) { stem = o; o += 1f }
    return NodeUnits(sp, stem, o)
}

/** ট্রির ধরনের বাটনের আইকন (৪ রকম) */
@Composable
private fun LayoutIcon(kind: String) {
    val c = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val sw = 2f * u
        fun box(x: Float, y: Float, w: Float, h: Float) =
            drawRect(c, Offset(x * u, y * u), Size(w * u, h * u))
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(c, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), sw)
        fun dot(x: Float, y: Float, r: Float) = drawCircle(c, r * u, Offset(x * u, y * u))
        when (kind) {
            "left" -> {
                box(2f, 9f, 6f, 6f)
                line(8f, 12f, 12f, 12f); line(12f, 5f, 12f, 19f)
                line(12f, 5f, 16f, 5f); line(12f, 19f, 16f, 19f)
                box(16f, 2f, 6f, 6f); box(16f, 16f, 6f, 6f)
            }
            "list" -> {
                box(2f, 2f, 8f, 5f)
                line(4f, 7f, 4f, 19f)
                line(4f, 12.5f, 9f, 12.5f); line(4f, 19f, 9f, 19f)
                box(9f, 10f, 13f, 5f); box(9f, 16.5f, 13f, 5f)
            }
            "round" -> {
                line(12f, 12f, 12f, 4.5f); line(12f, 12f, 18.5f, 16f); line(12f, 12f, 5.5f, 16f)
                dot(12f, 12f, 3f); dot(12f, 4.5f, 2.5f); dot(19f, 16.5f, 2.5f); dot(5f, 16.5f, 2.5f)
            }
            else -> {
                box(9f, 2f, 6f, 6f)
                line(12f, 8f, 12f, 12f); line(5f, 12f, 19f, 12f)
                line(5f, 12f, 5f, 16f); line(19f, 12f, 19f, 16f)
                box(2f, 16f, 6f, 6f); box(16f, 16f, 6f, 6f)
            }
        }
    }
}

private class EnterState { var last = 0L; var job: Job? = null }

/**
 * কিবোর্ডের Enter:
 *  - একবার: onSingle চলে, একটু পরে কিবোর্ড হাইড হয় ও ঘর থেকে কার্সার উঠে যায়
 *  - প্রায় ১ সেকেন্ডের মধ্যে আবার (দুইবার) চাপলে onDouble চলে (সংরক্ষণ ও বের হওয়া)
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun enterActions(
    onSingle: () -> Unit = {},
    hideOnSingle: Boolean = true,
    onDouble: () -> Unit
): KeyboardActions {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val st = remember { EnterState() }
    val single by rememberUpdatedState(onSingle)
    val dbl by rememberUpdatedState(onDouble)
    return KeyboardActions(onDone = {
        val now = System.currentTimeMillis()
        if (now - st.last < 1000L) {
            st.job?.cancel()
            st.last = 0L
            dbl()
        } else {
            st.last = now
            single()
            // hideOnSingle = false হলে (সন্তানের নামের ঘর) কিবোর্ড ও কার্সার থেকে যায়
            if (hideOnSingle) {
                st.job = scope.launch {
                    delay(800)
                    keyboard?.hide()
                    focus.clearFocus()
                }
            }
        }
    })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FamilyTreeScreen(viewModel: FamilyViewModel, settings: AppSettings) {
    val persons by viewModel.persons.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var showHideOpen by remember { mutableStateOf(false) }
    var restoreListOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var actionFile by remember { mutableStateOf<File?>(null) }
    var deleteFile by remember { mutableStateOf<File?>(null) }
    var backupTick by remember { mutableIntStateOf(0) }
    // নতুন সদস্য যোগের সময় বাবা-মায়ের ডিফল্ট (আগের বার যা ছিল)
    var defaultParents by remember { mutableStateOf<Pair<Long?, Long?>>(null to null) }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val haptic = LocalHapticFeedback.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }

    // ট্রির তথ্য
    val allRoots = remember(persons) { findRoots(persons) }
    val colors = remember(persons) { assignColors(allRoots, persons) }
    // হাইড চালু থাকলে শো/হাইড তালিকার লুকানো নামগুলো বাদ, আনহাইড করলে সব দেখায়
    val hiddenNow = if (settings.hideActive) settings.hidden else emptySet()
    val roots = allRoots.filter { it.id !in hiddenNow }
    val treeOpts = TreeOpts(settings.showWives, hiddenNow)
    val seq = remember(persons, hiddenNow, settings.showWives, settings.layout) {
        buildSeq(roots, persons, treeOpts, settings.layout)
    }

    // লাইন অ্যানিমেশন: অ্যাপ খুললে, ট্রির ধরন বদলালে, বা ডিসপ্লেতে ট্যাপ করলে নতুন করে শুরু।
    // একটা লাইন শেষ হলে পরেরটা শুরু হয়, মোট সময় নির্দিষ্ট নয়।
    var replay by remember { mutableIntStateOf(0) }
    var animDone by remember { mutableStateOf(false) }
    val lineT = remember { Animatable(0f) }
    val totalNow by rememberUpdatedState(seq.total)
    LaunchedEffect(settings.layout, replay, persons.isNotEmpty()) {
        animDone = false
        lineT.snapTo(0f)
        // কম সদস্য দেখালে ধীরে, বেশি সদস্য দেখালে দ্রুত
        val ms = (totalNow * msPerUnit(totalNow)).toInt().coerceAtLeast(1)
        lineT.animateTo(totalNow, tween(ms, easing = LinearEasing))
        lineT.snapTo(totalNow)
        animDone = true
    }
    // অ্যানিমেশন শেষের পর সদস্য যোগ/হাইড-আনহাইডে নতুন লাইন সাথে সাথে দেখাবে
    LaunchedEffect(seq.total, animDone) {
        if (animDone) lineT.snapTo(seq.total)
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingUri = uri }

    fun shareBackup(f: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (f.extension == "zip") "application/zip" else "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "ব্যাকআপ শেয়ার করুন"))
        }.onFailure {
            Toast.makeText(context, "শেয়ার করা যায়নি", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ট্রি স্ক্রিন: নিচের বাটন-বক্সের উপর থেকে শুরু
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (persons.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "এখনো কোনো সদস্য নেই।\nনিচের + বোতামে চাপ দিয়ে প্রথম ব্যক্তিকে যোগ করুন।",
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    val onEdit: (Person, Person?) -> Unit = { p, sp ->
                        defaultParents = parentsOf(p, sp)
                        dialog = DialogState(p)
                    }
                    val ctx = TreeCtx(
                        all = persons,
                        colors = colors,
                        opts = treeOpts,
                        anim = LineAnim(lineT.asState()),
                        layout = settings.layout,
                        base = seq.base,
                        offsets = settings.offsets,
                        onOffset = { id, o -> settings.setOffset(id, o) },
                        onEdit = onEdit,
                        onReorder = { viewModel.reorder(it) }
                    )
                    // ফাঁকা জায়গায় একবার চাপলে লাইন অ্যানিমেশন আবার চলে
                    val onEmptyTap: () -> Unit = { replay++ }
                    // সব ধরনেই জুম ইন-আউট, ডাবল ট্যাপে ২x জুম, টেনে সরানো
                    when (settings.layout) {
                        "list" -> {
                            ZoomPanBox(Modifier.fillMaxSize(), fitWidthOnly = true, onTap = onEmptyTap) {
                                Column(
                                    Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    roots.forEach { root ->
                                        ListNode(root, ctx, emptySet(), true, true, -1f, -1f)
                                    }
                                }
                            }
                        }
                        "left" -> {
                            ZoomPanBox(Modifier.fillMaxSize(), onTap = onEmptyTap) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    roots.forEach { root ->
                                        HTreeNode(root, ctx, emptySet(), true)
                                    }
                                }
                            }
                        }
                        "round" -> {
                            ZoomPanBox(Modifier.fillMaxSize(), onTap = onEmptyTap) {
                                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                    roots.forEach { root ->
                                        RoundTree(root, ctx)
                                    }
                                }
                            }
                        }
                        else -> {
                            ZoomPanBox(Modifier.fillMaxSize(), onTap = onEmptyTap) {
                                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.Top) {
                                    roots.forEach { root ->
                                        Box(Modifier.padding(horizontal = if (roots.size > 1) 16.dp else 0.dp)) {
                                            TreeNode(root, ctx, emptySet(), true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // প্রতি প্রজন্মের প্রথম নাম ও মোট কতজন, নিচের ডান কোণে হালকা করে (ট্যাচ আটকায় না)
                    val gens = remember(persons, hiddenNow, settings.showWives) {
                        generationCounts(roots, persons, treeOpts)
                    }
                    if (gens.isNotEmpty()) {
                        Column(
                            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            gens.forEach { (name, n) ->
                                Text("$name: ${bn(n)} জন", color = faint, fontSize = 10.sp, lineHeight = 12.sp)
                            }
                            Text("মোট: ${bn(gens.sumOf { it.second })} জন", color = faint, fontSize = 10.sp,
                                lineHeight = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // নিচের বক্স: ৫টি বাটন সবসময় দেখা যায়, সমান ফাঁক:
            // ট্রি ভিউ, হাইড/আনহাইড (চেপে ধরলে শো/হাইড), +, রোটেট, সেটিংস
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ১. ট্রির ধরন বদলানো (চাপলে ১ → ২ → ৩ → ৪ → ১)
                    SmallFloatingActionButton(onClick = {
                        settings.updateLayout(nextLayout(settings.layout))
                    }) { LayoutIcon(settings.layout) }
                    // ২. হাইড/আনহাইড: চাপলে চালু-বন্ধ, চেপে ধরলে শো/হাইড তালিকা
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .combinedClickable(
                                onClick = { settings.toggleHideActive() },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showHideOpen = true
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (settings.hideActive) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (settings.hideActive) "আনহাইড" else "হাইড",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    // ৩. নতুন সদস্য (মাঝখানে)
                    FloatingActionButton(onClick = {
                        dialog = DialogState(null, defaultParents.first, defaultParents.second)
                    }) { Icon(Icons.Default.Add, contentDescription = "সদস্য যোগ করুন") }
                    // ৪. স্ক্রিন ঘোরানো
                    SmallFloatingActionButton(onClick = {
                        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        activity?.requestedOrientation =
                            if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }) { Icon(Icons.Default.ScreenRotation, contentDescription = "স্ক্রিন ঘোরান") }
                    // ৫. সেটিংস (একেবারে ডানে)
                    SmallFloatingActionButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "সেটিংস")
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        SettingsDialog(
            settings = settings,
            onBackup = {
                viewModel.backupToFolder { path ->
                    backupTick++
                    Toast.makeText(
                        context,
                        if (path != null) "ব্যাকআপ সংরক্ষিত হয়েছে:\n$path" else "ব্যাকআপ ব্যর্থ হয়েছে",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onRestore = { restoreListOpen = true },
            onAbout = { aboutOpen = true },
            onDismiss = { settingsOpen = false }
        )
    }

    if (showHideOpen) {
        ShowHideDialog(persons, settings) { showHideOpen = false }
    }

    if (restoreListOpen) {
        val files = remember(backupTick) { viewModel.listBackups() }
        RestoreListDialog(
            files = files,
            onPick = { restoreListOpen = false; pendingFile = it },
            onLongPress = { actionFile = it },
            onImport = { restoreListOpen = false; restoreLauncher.launch(arrayOf("*/*")) },
            onDismiss = { restoreListOpen = false }
        )
    }

    // ফাইল চেপে ধরলে: শেয়ার বা ডিলিট
    actionFile?.let { f ->
        AlertDialog(
            onDismissRequest = { actionFile = null },
            title = { Text(f.nameWithoutExtension) },
            text = {
                Column {
                    TextButton(
                        onClick = { shareBackup(f); actionFile = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("শেয়ার") }
                    TextButton(
                        onClick = { deleteFile = f; actionFile = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("ডিলিট", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { actionFile = null }) { Text("বাতিল") } }
        )
    }

    deleteFile?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteFile = null },
            title = { Text("ডিলিট করবেন?") },
            text = { Text("${f.name} ব্যাকআপ ফাইলটি মুছে যাবে।") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = runCatching { f.delete() }.getOrDefault(false)
                    deleteFile = null
                    backupTick++
                    Toast.makeText(
                        context,
                        if (ok) "ব্যাকআপ ফাইল মুছে ফেলা হয়েছে" else "ফাইল মোছা যায়নি",
                        Toast.LENGTH_LONG
                    ).show()
                }) { Text("ডিলিট", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("বাতিল") } }
        )
    }

    if (aboutOpen) {
        AboutScreen(version) { aboutOpen = false }
    }

    if (pendingUri != null || pendingFile != null) {
        val cancel = { pendingUri = null; pendingFile = null }
        AlertDialog(
            onDismissRequest = cancel,
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("রিস্টোর নিশ্চিত করুন") },
            text = { Text("বর্তমান সব তথ্য মুছে গিয়ে ব্যাকআপ ফাইলের তথ্য বসবে। চালিয়ে যাবেন?") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingUri
                    val file = pendingFile
                    pendingUri = null; pendingFile = null
                    val done: (Boolean) -> Unit = { ok ->
                        if (ok) { settings.clearHidden(); settings.resetOffsets() }
                        Toast.makeText(
                            context,
                            if (ok) "রিস্টোর সম্পন্ন হয়েছে" else "রিস্টোর ব্যর্থ: ফাইলটি সঠিক নয়",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (file != null) viewModel.restoreFromFile(file, done)
                    else if (uri != null) viewModel.restoreFrom(uri, done)
                }) { Text("হ্যাঁ, রিস্টোর করুন") }
            },
            dismissButton = { TextButton(onClick = cancel) { Text("বাতিল") } }
        )
    }

    dialog?.let { state ->
        PersonDialog(
            state = state,
            all = persons,
            onDismiss = { dialog = null },
            onSave = { p, father, mother, spouses, children, childOrder ->
                viewModel.save(p, father, mother, spouses, children, childOrder)
                // নতুন সদস্য যোগ করলে পরেরবারও একই বাবা-মা ডিফল্ট থাকবে
                if (p.id == 0L && (father.id != null || mother.id != null)) {
                    defaultParents = father.id to mother.id
                }
                dialog = null
            },
            onDelete = { viewModel.delete(it); dialog = null }
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsDialog(
    settings: AppSettings,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onAbout: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("সেটিংস") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("থিম", style = MaterialTheme.typography.labelLarge)
                ChoiceRow("ফোনের সেটিং অনুযায়ী", settings.theme == "system") { settings.updateTheme("system") }
                ChoiceRow("দিন (ডে)", settings.theme == "day") { settings.updateTheme("day") }
                ChoiceRow("রাত (নাইট)", settings.theme == "night") { settings.updateTheme("night") }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow("স্ত্রীর নাম দেখান", settings.showWives) { settings.updateShowWives(it) }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Text("ব্যাকআপ নিন") }
                OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("ব্যাকআপ রিস্টোর করুন") }
                OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("About") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ঠিক আছে") } }
    )
}

/** শো/হাইড তালিকার একটি নাম ও তার নিচের (যাদের নিজেদেরও সন্তান আছে) নামগুলো */
private class HideNode(val p: Person, val kids: List<HideNode>)

/** যাদের নিচে ট্রি (সন্তান) আছে শুধু তাদের নিয়ে গাছ */
private fun branchTree(all: List<Person>): List<HideNode> {
    fun build(p: Person, path: Set<Long>): HideNode? {
        val sp = spousesOf(p, all)
        val kids = childrenOf(p, sp, all, path)
        if (kids.isEmpty()) return null
        val np = path + p.id + sp.map { it.id }
        return HideNode(p, kids.mapNotNull { build(it, np) })
    }
    return findRoots(all).mapNotNull { build(it, emptySet()) }
}

/** একটি সারি: নাম + সুইচ, কানেকশন লাইনসহ; লুকানো নামের নিচের সারিগুলো দেখানো হয় না */
@Composable
private fun HideNodeRow(
    node: HideNode,
    settings: AppSettings,
    isRoot: Boolean,
    isLast: Boolean,
    lineColor: Color
) {
    var rowH by remember { mutableIntStateOf(0) }
    val hidden = node.p.id in settings.hidden
    Column(
        Modifier
            .drawBehind {
                if (!isRoot) {
                    val sw = 2.dp.toPx()
                    val x = 8.dp.toPx()
                    val cy = rowH / 2f
                    drawLine(lineColor, Offset(x, 0f), Offset(x, if (isLast) cy else size.height), sw)
                    drawLine(lineColor, Offset(x, cy), Offset(22.dp.toPx(), cy), sw)
                }
            }
            .padding(start = if (isRoot) 0.dp else 22.dp)
    ) {
        // সারিগুলোর মাঝে কোনো ফাঁক নেই
        Row(
            Modifier.fillMaxWidth().onSizeChanged { rowH = it.height },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                node.p.name,
                modifier = Modifier.weight(1f),
                color = if (hidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = !hidden,
                onCheckedChange = { settings.toggleHidden(node.p.id) }
            )
        }
        if (!hidden) {
            node.kids.forEachIndexed { i, k ->
                HideNodeRow(k, settings, false, i == node.kids.lastIndex, lineColor)
            }
        }
    }
}

/**
 * শো/হাইড: যাদের নিচে ট্রি আছে তাদের নাম, কানেকশন লাইনসহ। সুইচ অন/অফ করলেই সাথে সাথে ট্রিতে কার্যকর হয়।
 * "ঠিক আছে" বাটন নেই: প্যানেলের বাইরে ফাঁকা জায়গায় ট্যাপ করলে (বা ব্যাক চাপলে) প্যানেল আবার লুকিয়ে যায়।
 * পেছনে অন্ধকার পর্দা নেই, ট্রি স্ক্রিন পরিষ্কার দেখা যায়।
 */
@Composable
private fun ShowHideDialog(all: List<Person>, settings: AppSettings, onDismiss: () -> Unit) {
    val tree = remember(all) { branchTree(all) }
    val lineColor = MaterialTheme.colorScheme.primary
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        Box(
            Modifier
                .fillMaxSize()
                // ফাঁকা জায়গায় ট্যাপ = বন্ধ
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    // প্যানেলের ভেতরের ফাঁকা জায়গায় ট্যাপে যেন বন্ধ না হয়
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("শো/হাইড", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "সুইচ বদলালেই কার্যকর হবে। বাইরে ট্যাপ করলে বন্ধ হবে।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    if (tree.isEmpty()) {
                        Text("যাদের নিচে ট্রি আছে এমন কোনো নাম এখনো নেই।")
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            tree.forEach { root ->
                                HideNodeRow(root, settings, true, true, lineColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ব্যাকআপ ফাইলের তালিকা: চাপলে রিস্টোর, চেপে ধরলে শেয়ার/ডিলিট, নিচের + দিয়ে অন্য জায়গা থেকে ইম্পোর্ট */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RestoreListDialog(
    files: List<File>,
    onPick: (File) -> Unit,
    onLongPress: (File) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ব্যাকআপ রিস্টোর") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (files.isEmpty()) {
                    Text("ডাটা ফোল্ডারে কোনো ব্যাকআপ পাওয়া যায়নি।")
                } else {
                    Text(
                        "ফাইলে চাপ দিলে রিস্টোর হবে, চেপে ধরলে শেয়ার বা ডিলিট করা যাবে।",
                        style = MaterialTheme.typography.bodySmall
                    )
                    files.forEach { f ->
                        Text(
                            f.nameWithoutExtension,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { onPick(f) }, onLongClick = { onLongPress(f) })
                                .padding(vertical = 14.dp)
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    FilledTonalIconButton(onClick = onImport) {
                        Icon(Icons.Default.Add, contentDescription = "অন্য জায়গা থেকে ফাইল আনুন")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("বন্ধ") } }
    )
}

/**
 * দুই আঙুলে জুম ইন/আউট, ডাবল ট্যাপে ২x জুম, টেনে সরানো। জুম আউট করলে সর্বোচ্চ ততটুকু যাবে
 * যতটুকুতে পুরো ট্রি স্ক্রিনে ধরে। শুরুতেই পুরো ট্রি স্ক্রিনে ফিট হয়।
 * fitWidthOnly = true হলে (তালিকার জন্য) শুধু প্রস্থ ফিট হয়, উপর-নিচে টেনে দেখা যায়।
 * ফাঁকা জায়গায় একবার চাপলে onTap চলে।
 */
@Composable
private fun ZoomPanBox(
    modifier: Modifier,
    fitWidthOnly: Boolean = false,
    alignBottom: Boolean = false,
    onTap: () -> Unit = {},
    content: @Composable () -> Unit
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val tapCallback by rememberUpdatedState(onTap)

    fun fitScale(): Float {
        if (container == IntSize.Zero || contentSize == IntSize.Zero) return 1f
        val sx = container.width.toFloat() / contentSize.width
        val sy = container.height.toFloat() / contentSize.height
        return if (fitWidthOnly) minOf(1f, sx) else minOf(1f, sx, sy)
    }

    fun clampOffset(s: Float, o: Offset): Offset {
        val w = contentSize.width * s
        val h = contentSize.height * s
        val x = if (w <= container.width) (container.width - w) / 2f
        else o.x.coerceIn(container.width - w, 0f)
        val y = if (h <= container.height) (if (alignBottom) container.height - h else 0f)
        else o.y.coerceIn(container.height - h, 0f)
        return Offset(x, y)
    }

    fun zoomTo(target: Float, anchor: Offset) {
        scope.launch {
            val s0 = scale
            val o0 = offset
            animate(0f, 1f, animationSpec = tween(250)) { v, _ ->
                val s = s0 + (target - s0) * v
                val f = s / s0
                offset = clampOffset(s, (o0 - anchor) * f + anchor)
                scale = s
            }
        }
    }

    // ট্রির আকার বা স্ক্রিন (portrait/landscape) বদলালে আবার ফিট করা
    LaunchedEffect(container, contentSize) {
        if (container != IntSize.Zero && contentSize != IntSize.Zero) {
            scale = fitScale()
            offset = clampOffset(scale, Offset.Zero)
        }
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { container = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(fitScale(), 5f)
                    val f = newScale / scale
                    val moved = (offset - centroid) * f + centroid + pan
                    scale = newScale
                    offset = clampOffset(newScale, moved)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        val fit = fitScale()
                        val target = if (scale * 2f > 5f) fit else scale * 2f
                        zoomTo(target, pos)
                    },
                    onTap = { tapCallback() }
                )
            }
    ) {
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .onSizeChanged { contentSize = it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = TransformOrigin(0f, 0f)
                }
        ) { content() }
    }
}

private class DragCallbacks(
    val onStart: () -> Unit,
    val onMove: (Float, Float) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit
)

/** ট্রি (উপর থেকে নিচে): প্রতিটি নাম মাঝখানে, স্বামী/স্ত্রীরা তার নিচে, তার নিচে সন্তানরা */
@Composable
private fun TreeNode(
    person: Person,
    ctx: TreeCtx,
    path: Set<Long>,
    isRoot: Boolean,
    dragOffsetX: Float = 0f,
    dragHandler: DragCallbacks? = null
) {
    val all = ctx.all
    val spouses = spousesOf(person, all)
    val shownSpouses = if (ctx.opts.showWives) spouses else emptyList()
    val children = childrenOf(person, spouses, all, path).filter { it.id !in ctx.opts.hidden }
    val color = ctx.colors[person.id] ?: Palette[0]
    val childColor = children.firstOrNull()?.let { ctx.colors[it.id] } ?: color
    val firstSpouse = spouses.firstOrNull()
    val u = unitsOf(ctx, person.id, shownSpouses.isNotEmpty(), children.isNotEmpty())

    Column(
        modifier = Modifier
            .graphicsLayer { translationX = dragOffsetX }
            .padding(horizontal = if (isRoot) 0.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NameButton(person.name, color, dragHandler, shownSpouses.map { it.name }) { ctx.onEdit(person, firstSpouse) }

        if (children.isNotEmpty()) {
            LineBox(childColor, 3.dp, 16.dp, true) { ctx.anim.p(u.stem) }

            var draggingId by remember { mutableStateOf<Long?>(null) }
            var dragX by remember { mutableFloatStateOf(0f) }
            val widths = remember { mutableStateMapOf<Long, Int>() }
            val latestChildren by rememberUpdatedState(children)
            val newPath = path + person.id + spouses.map { it.id }

            // আড়াআড়ি লাইন মাঝখান থেকে দুই দিকে ছড়ায়, পৌঁছানো সন্তানের নামার লাইন নিজে থেকে শুরু হয়
            Box(
                Modifier.drawBehind {
                    val ws = children.map { (widths[it.id] ?: 0).toFloat() }
                    if (ws.any { it <= 0f }) return@drawBehind
                    var acc = 0f
                    val cxs = ws.map { w -> val c = acc + w / 2; acc += w; c }
                    drawSpread(
                        childColor, false, cxs, size.width / 2,
                        ctx.anim.p(u.conn, connLen(ctx.layout, children.size)),
                        16.dp.toPx(), 3.dp.toPx(), 5.dp.toPx()
                    )
                }
            ) {
                // সব সন্তান এক লাইনে, তার নিচে পরের প্রজন্ম
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                    children.forEach { child ->
                        val dragging = draggingId == child.id
                        Box(
                            Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .onSizeChanged { widths[child.id] = it.width }
                        ) {
                            TreeNode(
                                person = child,
                                ctx = ctx,
                                path = newPath,
                                isRoot = false,
                                dragOffsetX = if (dragging) dragX else 0f,
                                dragHandler = DragCallbacks(
                                    onStart = { draggingId = child.id; dragX = 0f },
                                    onMove = { dx, _ -> dragX += dx },
                                    onEnd = {
                                        // ছাড়ার জায়গা দেখে নতুন ক্রম ঠিক করা
                                        val list = latestChildren
                                        val d = list.indexOfFirst { it.id == child.id }
                                        if (d >= 0) {
                                            val ws = list.map { widths[it.id] ?: 0 }
                                            val centers = ws.indices.map { k -> ws.take(k).sum() + ws[k] / 2f }
                                            val dragCenter = centers[d] + dragX
                                            val newIndex = list.indices.count { it != d && centers[it] < dragCenter }
                                            val reordered = list.filterIndexed { k, _ -> k != d }
                                                .toMutableList().apply { add(newIndex, list[d]) }
                                            if (reordered.map { it.id } != list.map { it.id }) {
                                                ctx.onReorder(reordered.map { it.id })
                                            }
                                        }
                                        draggingId = null; dragX = 0f
                                    },
                                    onCancel = { draggingId = null; dragX = 0f }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/** ট্রি (বাম থেকে ডানে): প্রতি প্রজন্ম একটি কলামে, সন্তানরা ওপর-নিচে সাজানো */
@Composable
private fun HTreeNode(person: Person, ctx: TreeCtx, path: Set<Long>, isRoot: Boolean) {
    val all = ctx.all
    val spouses = spousesOf(person, all)
    val shownSpouses = if (ctx.opts.showWives) spouses else emptyList()
    val children = childrenOf(person, spouses, all, path).filter { it.id !in ctx.opts.hidden }
    val color = ctx.colors[person.id] ?: Palette[0]
    val childColor = children.firstOrNull()?.let { ctx.colors[it.id] } ?: color
    val u = unitsOf(ctx, person.id, shownSpouses.isNotEmpty(), children.isNotEmpty())

    Row(
        modifier = Modifier.padding(vertical = if (isRoot) 0.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NameButton(person.name, color, null, shownSpouses.map { it.name }) { ctx.onEdit(person, spouses.firstOrNull()) }
        }
        if (children.isNotEmpty()) {
            LineBox(childColor, 14.dp, 3.dp, false) { ctx.anim.p(u.stem) }
            val newPath = path + person.id + spouses.map { it.id }
            val heights = remember { mutableStateMapOf<Long, Int>() }
            Box(
                Modifier.drawBehind {
                    val hs = children.map { (heights[it.id] ?: 0).toFloat() }
                    if (hs.any { it <= 0f }) return@drawBehind
                    var acc = 0f
                    val cys = hs.map { h -> val c = acc + h / 2; acc += h; c }
                    drawSpread(
                        childColor, true, cys, size.height / 2,
                        ctx.anim.p(u.conn, connLen(ctx.layout, children.size)),
                        14.dp.toPx(), 3.dp.toPx(), 5.dp.toPx()
                    )
                }
            ) {
                Column(Modifier.padding(start = 14.dp)) {
                    children.forEach { child ->
                        Box(Modifier.onSizeChanged { heights[child.id] = it.height }) {
                            HTreeNode(child, ctx, newPath, false)
                        }
                    }
                }
            }
        }
    }
}

/** তালিকা: প্রজন্ম অনুযায়ী ভেতরে সরিয়ে সাজানো, কানেকশন লাইনসহ */
@Composable
private fun ListNode(
    person: Person,
    ctx: TreeCtx,
    path: Set<Long>,
    isRoot: Boolean,
    isLast: Boolean,
    barUnit: Float,
    dropUnit: Float
) {
    val all = ctx.all
    val spouses = spousesOf(person, all)
    val shownSpouses = if (ctx.opts.showWives) spouses else emptyList()
    val children = childrenOf(person, spouses, all, path).filter { it.id !in ctx.opts.hidden }
    val color = ctx.colors[person.id] ?: Palette[0]
    val newPath = path + person.id + spouses.map { it.id }
    val u = unitsOf(ctx, person.id, shownSpouses.isNotEmpty(), children.isNotEmpty())
    var rowH by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .drawBehind {
                if (!isRoot) {
                    val sw = 3.dp.toPx()
                    val ball = 5.dp.toPx()
                    val x = 8.dp.toPx()
                    val cy = rowH / 2f
                    // উপর থেকে নামা লাইন (শেষ সন্তান হলে নিজের নাম পর্যন্ত), তারপর নামের দিকে আড়াআড়ি লাইন
                    seg(color, Offset(x, 0f), Offset(x, if (isLast) cy else size.height), ctx.anim.p(barUnit), sw, ball)
                    seg(color, Offset(x, cy), Offset(22.dp.toPx(), cy), ctx.anim.p(dropUnit), sw, ball)
                }
            }
            .padding(start = if (isRoot) 0.dp else 22.dp)
    ) {
        Row(
            Modifier.onSizeChanged { rowH = it.height }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NameButton(person.name, color, null, shownSpouses.map { it.name }) { ctx.onEdit(person, spouses.firstOrNull()) }
        }
        children.forEachIndexed { i, child ->
            ListNode(child, ctx, newPath, false, i == children.lastIndex, u.conn + 2f * i, u.conn + 2f * i + 1f)
        }
    }
}

// ---------- গোল (রাউন্ড) ভিউ ----------

private class RT(val p: Person, val kids: List<RT>, val leaves: Int, val spouses: Int)

private fun buildRT(p: Person, ctx: TreeCtx, path: Set<Long>): RT {
    val sp = spousesOf(p, ctx.all)
    val kids = childrenOf(p, sp, ctx.all, path).filter { it.id !in ctx.opts.hidden }
    val np = path + p.id + sp.map { it.id }
    val kt = kids.map { buildRT(it, ctx, np) }
    return RT(p, kt, if (kt.isEmpty()) 1 else kt.sumOf { it.leaves }, if (ctx.opts.showWives) sp.size else 0)
}

/** pos = বর্তমান জায়গা (টানা সরানোসহ), সবকিছু dp এককে */
private class RNode(val rt: RT, val base: Offset, val pos: Offset, val parent: Long?, val idx: Int)
private class RLayout(val nodes: List<RNode>, val w: Float, val h: Float)

private const val RING = 150f
private const val NODE_W = 160f
private const val MARGIN = 220f

/** মূল নাম মাঝখানে, সন্তানরা তাকে ঘিরে ৩৬০° বৃত্তে, নাতি-নাতনিরা আবার তাদের ঘিরে বাইরের দিকে */
private fun layoutRound(root: RT, offsets: Map<Long, Offset>): RLayout {
    val counts = HashMap<Int, Int>()
    fun count(n: RT, d: Int) {
        counts[d] = (counts[d] ?: 0) + 1
        n.kids.forEach { count(it, d + 1) }
    }
    count(root, 0)
    val maxD = counts.keys.maxOrNull() ?: 0
    val radius = FloatArray(maxD + 1)
    for (d in 1..maxD) {
        radius[d] = maxOf(radius[d - 1] + RING, (counts[d] ?: 0) * RING / (2f * Math.PI.toFloat()))
    }
    val raw = ArrayList<RNode>()
    fun place(n: RT, d: Int, a0: Float, a1: Float, parent: Long?, idx: Int, acc: Offset) {
        val ang = (a0 + a1) / 2f
        val bp = if (d == 0) Offset.Zero else Offset(radius[d] * cos(ang), radius[d] * sin(ang))
        val eff = acc + (offsets[n.p.id] ?: Offset.Zero)
        raw.add(RNode(n, bp, bp + eff, parent, idx))
        var s = a0
        val total = n.leaves.toFloat()
        n.kids.forEachIndexed { i, k ->
            val span = (a1 - a0) * k.leaves / total
            place(k, d + 1, s, s + span, n.p.id, i, eff)
            s += span
        }
    }
    val pi = Math.PI.toFloat()
    val span0 = root.kids.firstOrNull()?.let { 2f * pi * it.leaves / root.leaves } ?: 0f
    val start = -pi / 2f - span0 / 2f
    place(root, 0, start, start + 2f * pi, null, 0, Offset.Zero)

    val minX = raw.minOf { it.base.x }
    val maxX = raw.maxOf { it.base.x }
    val minY = raw.minOf { it.base.y }
    val maxY = raw.maxOf { it.base.y }
    val ox = minX - MARGIN
    val oy = minY - MARGIN
    val w = (maxX - minX) + 2 * MARGIN
    val h = (maxY - minY) + 2 * MARGIN
    val nodes = raw.map { n ->
        val x = (n.pos.x - ox).coerceIn(NODE_W / 2f, w - NODE_W / 2f)
        val y = (n.pos.y - oy).coerceIn(30f, h - 100f)
        RNode(n.rt, n.base, Offset(x, y), n.parent, n.idx)
    }
    return RLayout(nodes, w, h)
}

/**
 * গোল ভিউ: নামটি চেপে ধরে টেনে যেখানে খুশি সরানো যায়, তার নিচের নামগুলো সাথে সাথে চলে।
 * সরানো জায়গা ফোনে সংরক্ষিত থাকে।
 */
@Composable
private fun RoundTree(root: Person, ctx: TreeCtx) {
    val rt = remember(ctx.all, ctx.opts, root.id) { buildRT(root, ctx, emptySet()) }
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current.density
    val did = dragId
    val offs = if (did != null) ctx.offsets + (did to ((ctx.offsets[did] ?: Offset.Zero) + dragDelta)) else ctx.offsets
    val lay = layoutRound(rt, offs)
    val byId = lay.nodes.associateBy { it.rt.p.id }

    Box(
        Modifier
            .size(lay.w.dp, lay.h.dp)
            .drawBehind {
                // কেন্দ্র থেকে বাইরের দিকে একটার পর একটা লাইন
                lay.nodes.forEach { n ->
                    val par = n.parent?.let { byId[it] } ?: return@forEach
                    val color = ctx.colors[n.rt.p.id] ?: Palette[0]
                    val a = Offset(par.pos.x.dp.toPx(), par.pos.y.dp.toPx())
                    val b = Offset(n.pos.x.dp.toPx(), n.pos.y.dp.toPx())
                    val start = unitsOf(ctx, par.rt.p.id, par.rt.spouses > 0, true).conn + n.idx
                    val pr = ctx.anim.p(start)
                    seg(color, a, b, pr, 3.dp.toPx(), 5.dp.toPx())
                    // লাইনের মাঝখানে বাবা-মা থেকে সন্তানের দিকে তির
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val len = sqrt(dx * dx + dy * dy)
                    if (pr >= 0.5f && len > 1f) {
                        val ux = dx / len
                        val uy = dy / len
                        val m = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                        val sz = 8.dp.toPx()
                        val tip = Offset(m.x + ux * sz, m.y + uy * sz)
                        val bx = m.x - ux * sz * 0.7f
                        val by = m.y - uy * sz * 0.7f
                        val nx = -uy * sz * 0.7f
                        val ny = ux * sz * 0.7f
                        val path = Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(bx + nx, by + ny)
                            lineTo(bx - nx, by - ny)
                            close()
                        }
                        drawPath(path, color)
                    }
                }
            }
    ) {
        lay.nodes.forEach { n ->
            val id = n.rt.p.id
            val color = ctx.colors[id] ?: Palette[0]
            val sp = spousesOf(n.rt.p, ctx.all)
            val shown = if (ctx.opts.showWives) sp else emptyList()
            val u = unitsOf(ctx, id, shown.isNotEmpty(), n.rt.kids.isNotEmpty())
            Box(
                Modifier
                    .offset((n.pos.x - NODE_W / 2f).dp, (n.pos.y - 20f).dp)
                    .size(NODE_W.dp, (56f + shown.size * 16f).dp)
                    .zIndex(if (dragId == id) 1f else 0f),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NameButton(
                        n.rt.p.name, color,
                        dragHandler = DragCallbacks(
                            onStart = { dragId = id; dragDelta = Offset.Zero },
                            onMove = { dx, dy -> dragDelta += Offset(dx / density, dy / density) },
                            onEnd = {
                                ctx.onOffset(id, (ctx.offsets[id] ?: Offset.Zero) + dragDelta)
                                dragId = null
                                dragDelta = Offset.Zero
                            },
                            onCancel = { dragId = null; dragDelta = Offset.Zero }
                        ),
                        spouseNames = shown.map { it.name }
                    ) { ctx.onEdit(n.rt.p, sp.firstOrNull()) }
                }
            }
        }
    }
}

/** শুধু নামের বাটন। চাপলে তথ্য খোলে; লম্বা চেপে ধরে টানলে (dragHandler থাকলে) সরানো যায় */
@Composable
private fun NameButton(
    name: String,
    color: Color,
    dragHandler: DragCallbacks?,
    spouseNames: List<String> = emptyList(),
    onClick: () -> Unit
) {
    val currentClick by rememberUpdatedState(onClick)
    val currentDrag by rememberUpdatedState(dragHandler)
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress != null) {
                        val d = currentDrag
                        if (d != null) {
                            d.onStart()
                            val done = drag(longPress.id) { change ->
                                change.consume()
                                val dl = change.positionChange()
                                d.onMove(dl.x, dl.y)
                            }
                            if (done) d.onEnd() else d.onCancel()
                        } else {
                            waitForUpOrCancellation()?.let { it.consume(); currentClick() }
                        }
                    } else {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed) {
                            up.consume()
                            currentClick()
                        }
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = Color.White, fontWeight = FontWeight.Medium)
            // স্ত্রীর নাম স্বামীর ঘরেই, ছোট করে হালকা ধূসর
            spouseNames.forEach {
                Text(it, color = Color(0xFFD0D0D0), fontSize = 11.sp, lineHeight = 13.sp)
            }
        }
    }
}

/**
 * বাবা/মায়ের ঘর। টাইপ করার সময় মিলে যাওয়া নামের সাজেশন আসে (ঘর ফাঁকা থাকলে বা ক্লিক করলেই সব নাম আসে না)।
 * options খালি দিলে (স্ত্রীর ঘর) কোনো সাজেশন আসে না।
 * তালিকায় না পেলে যেকোনো নাম লিখে দিলে সেভ করার সময় নতুন সদস্য হিসেবে যুক্ত হয়।
 */
@Composable
private fun PersonPicker(
    label: String,
    options: List<Person>,
    entry: PickEntry,
    modifier: Modifier = Modifier,
    onDoubleEnter: () -> Unit,
    onChange: (PickEntry) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val q = entry.text.trim()
    val matches = if (q.isEmpty()) emptyList() else options.filter { it.name.contains(q, ignoreCase = true) }
    val show = focused && entry.id == null && matches.isNotEmpty()
    Box(modifier) {
        OutlinedTextField(
            value = entry.text,
            onValueChange = { onChange(PickEntry(null, it)) },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = enterActions(onDouble = onDoubleEnter),
            trailingIcon = if (entry.text.isNotEmpty()) {
                {
                    IconButton(onClick = { onChange(PickEntry(null, "")) }) {
                        Icon(Icons.Default.Close, contentDescription = "মুছুন")
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        )
        DropdownMenu(
            expanded = show,
            onDismissRequest = {},
            properties = PopupProperties(focusable = false)
        ) {
            matches.take(8).forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    onClick = { onChange(PickEntry(p.id, p.name)) }
                )
            }
        }
    }
}

/**
 * সন্তানের নাম লিখে একবার Enter দিলে নামটি তালিকায় যুক্ত হয়, ঘর ফাঁকা হয়, কিবোর্ড খোলা থাকে।
 * দ্রুত দুইবার Enter দিলে সব সংরক্ষিত হয়ে বন্ধ হয়।
 */
@Composable
private fun NameListEditor(
    title: String,
    names: SnapshotStateList<String>,
    pending: String,
    onPendingChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onDoubleEnter: () -> Unit
) {
    fun add() {
        val n = pending.trim()
        if (n.isNotEmpty()) names.add(n)
        onPendingChange("")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        names.forEachIndexed { i, n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("• $n", Modifier.weight(1f))
                IconButton(onClick = { names.removeAt(i) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "বাদ দিন")
                }
            }
        }
        OutlinedTextField(
            value = pending,
            onValueChange = onPendingChange,
            singleLine = true,
            label = { Text("নাম লিখে এন্টার চাপুন") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            // সন্তানের নামের ঘরে কিবোর্ড কখনো হাইড হয় না (শুধু Back বা সংরক্ষণে বন্ধ)
            keyboardActions = enterActions(
                onSingle = { if (pending.trim().isNotEmpty()) add() },
                hideOnSingle = false,
                onDouble = onDoubleEnter
            ),
            trailingIcon = {
                IconButton(onClick = { add() }) {
                    Icon(Icons.Default.Add, contentDescription = "যোগ করুন")
                }
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) }
        )
    }
}

/** সদস্যের তথ্য: পুরো পর্দা জুড়ে ডায়ালগ, ফাঁকা জায়গায় চাপলে কিবোর্ড হাইড হয় */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PersonDialog(
    state: DialogState,
    all: List<Person>,
    onDismiss: () -> Unit,
    onSave: (Person, Link, Link, List<Link>, List<String>, List<Long>) -> Unit,
    onDelete: (Person) -> Unit
) {
    val person = state.person
    var name by remember { mutableStateOf(person?.name ?: "") }
    var year by remember { mutableStateOf(person?.birthYear?.toString() ?: "") }
    // বাবা-মা: আগে থেকে ঠিক করা থাকবে, চাইলে বদলানো যাবে বা অন্য নাম লেখা যাবে
    val initFatherId = person?.fatherId ?: state.defaultFatherId?.takeIf { id -> all.any { it.id == id } }
    val initMotherId = person?.motherId ?: state.defaultMotherId?.takeIf { id -> all.any { it.id == id } }
    var father by remember { mutableStateOf(PickEntry(initFatherId, all.find { it.id == initFatherId }?.name ?: "")) }
    var mother by remember { mutableStateOf(PickEntry(initMotherId, all.find { it.id == initMotherId }?.name ?: "")) }
    // স্ত্রী: একাধিক যোগ করা যায়
    val spouses = remember {
        mutableStateListOf<PickEntry>().apply {
            if (person != null) spousesOf(person, all).forEach { add(PickEntry(it.id, it.name)) }
            if (isEmpty()) add(PickEntry(null, ""))
        }
    }
    // বর্তমান সন্তানদের ক্রম
    val myChildren = remember {
        person?.let { p -> childrenOf(p, spousesOf(p, all), all, emptySet()).map { it.id } } ?: emptyList()
    }
    val childOrder = remember { mutableStateListOf<Long>().apply { addAll(myChildren) } }
    val newChildren = remember { mutableStateListOf<String>() }
    var childPending by remember { mutableStateOf("") }
    var childFocused by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // সন্তানের নাম যোগ হলে, বা নামের ঘরে কার্সার এলে নিচে স্ক্রল করে ঘরটা সামনে রাখা
    LaunchedEffect(newChildren.size) {
        if (newChildren.isNotEmpty()) {
            delay(60)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    LaunchedEffect(childFocused) {
        if (childFocused) {
            delay(350)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    val others = all.filter { it.id != person?.id }

    val save: () -> Unit = {
        if (name.isNotBlank()) {
            onSave(
                Person(
                    id = person?.id ?: 0,
                    name = name.trim(),
                    gender = person?.gender ?: "M",
                    birthYear = year.toIntOrNull(),
                    sortOrder = person?.sortOrder ?: 0,
                    spouseOrder = person?.spouseOrder ?: 0
                ),
                toLink(father, others),
                toLink(mother, others),
                spouses.map { toLink(it, emptyList()) }.filter { it.id != null || it.newName != null },
                newChildren.toList() + childPending.trim(),
                if (childOrder.toList() != myChildren) childOrder.toList() else emptyList()
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
                // ঘরের বাইরে যেকোনো জায়গায় চাপলে কিবোর্ড হাইড
                .pointerInput(Unit) {
                    detectTapGestures {
                        // সন্তানের নামের ঘরে কার্সার থাকলে কিবোর্ড হাইড হয় না
                        if (!childFocused) { keyboard?.hide(); focus.clearFocus() }
                    }
                }
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        if (person == null) "নতুন সদস্য" else "সদস্যের তথ্য",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.height(16.dp))
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            label = { Text("নাম") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = enterActions(onDouble = save),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = year, onValueChange = { year = it.filter(Char::isDigit).take(4) },
                            label = { Text("জন্মসাল (ঐচ্ছিক)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = enterActions(onDouble = save),
                            modifier = Modifier.fillMaxWidth()
                        )
                        PersonPicker("বাবা", others, father, onDoubleEnter = save) { father = it }
                        PersonPicker("মা", others, mother, onDoubleEnter = save) { mother = it }

                        // স্ত্রী (একাধিক): এই ঘরে কোনো সাজেশন আসে না
                        spouses.forEachIndexed { i, entry ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PersonPicker(
                                    label = if (spouses.size > 1) "স্ত্রী ${i + 1}" else "স্ত্রী",
                                    options = emptyList(),
                                    entry = entry,
                                    modifier = Modifier.weight(1f),
                                    onDoubleEnter = save
                                ) { new ->
                                    // আগের স্ত্রীর নাম এডিট করলে নতুন সদস্য না বানিয়ে নামটাই বদলাবে
                                    spouses[i] = if (new.id == null && (entry.id != null || entry.origId != null))
                                        new.copy(origId = entry.origId ?: entry.id)
                                    else new
                                }
                                if (spouses.size > 1) {
                                    IconButton(
                                        enabled = i > 0,
                                        onClick = { val t = spouses[i]; spouses[i] = spouses[i - 1]; spouses[i - 1] = t },
                                        modifier = Modifier.size(32.dp)
                                    ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "উপরে") }
                                    IconButton(
                                        enabled = i < spouses.lastIndex,
                                        onClick = { val t = spouses[i]; spouses[i] = spouses[i + 1]; spouses[i + 1] = t },
                                        modifier = Modifier.size(32.dp)
                                    ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "নিচে") }
                                    IconButton(
                                        onClick = { spouses.removeAt(i) },
                                        modifier = Modifier.size(32.dp)
                                    ) { Icon(Icons.Default.Close, contentDescription = "বাদ দিন") }
                                }
                            }
                        }
                        TextButton(onClick = { spouses.add(PickEntry(null, "")) }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("আরেকজন স্ত্রী যোগ করুন")
                        }

                        // বর্তমান সন্তান: ক্রম ঠিক করা যাবে
                        if (childOrder.isNotEmpty()) {
                            HorizontalDivider()
                            Text("সন্তান (ক্রম ঠিক করুন)", style = MaterialTheme.typography.labelMedium)
                            childOrder.forEachIndexed { i, cid ->
                                val c = all.find { it.id == cid }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}. ${c?.name.orEmpty()}", Modifier.weight(1f))
                                    IconButton(
                                        enabled = i > 0,
                                        onClick = { val t = childOrder[i]; childOrder[i] = childOrder[i - 1]; childOrder[i - 1] = t },
                                        modifier = Modifier.size(32.dp)
                                    ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "উপরে") }
                                    IconButton(
                                        enabled = i < childOrder.lastIndex,
                                        onClick = { val t = childOrder[i]; childOrder[i] = childOrder[i + 1]; childOrder[i + 1] = t },
                                        modifier = Modifier.size(32.dp)
                                    ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "নিচে") }
                                }
                            }
                        }

                        HorizontalDivider()
                        NameListEditor("সন্তান", newChildren, childPending, { childPending = it }, { childFocused = it }, save)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (person != null) {
                            TextButton(onClick = { onDelete(person) }) {
                                Text("মুছুন", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(onClick = onDismiss) { Text("বাতিল") }
                        TextButton(enabled = name.isNotBlank(), onClick = { save() }) { Text("সংরক্ষণ") }
                    }
                }
            }
        }
    }
}


/**
 * About: my-id পেজ (assets/about) হুবুহু WebView-তে দেখায়; সম্পূর্ণ অফলাইন।
 * লিংকে (ফোন, ইমেইল, WhatsApp, Facebook) চাপলে সংশ্লিষ্ট অ্যাপ খোলে। একেবারে নিচে শুধু সংস্করণ নম্বর, হালকা ধূসর।
 */
@Composable
private fun AboutScreen(version: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)
    ) {
        val pageBg = Color(0xFF071226)
        Column(Modifier.fillMaxSize().background(pageBg)) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.parseColor("#071226"))
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                if (url.scheme == "file") return false
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                                return true
                            }
                        }
                        loadUrl("file:///android_asset/about/index.html")
                    }
                }
            )
            Text(
                version,
                color = Color(0xFF9AA4B2).copy(alpha = 0.6f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp)
            )
        }
    }
}
