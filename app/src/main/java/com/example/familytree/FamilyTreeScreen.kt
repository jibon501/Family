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
import androidx.compose.ui.platform.LocalContext
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

private val BranchColor = Color(0xFF795548)

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

/** যাদের নিচে সন্তান আছে (শো/হাইড তালিকার জন্য), গভীরতাসহ */
private fun branchRows(all: List<Person>): List<Pair<Person, Int>> {
    val out = ArrayList<Pair<Person, Int>>()
    fun walk(p: Person, level: Int, path: Set<Long>) {
        val sp = spousesOf(p, all)
        val kids = childrenOf(p, sp, all, path)
        if (kids.isNotEmpty()) out.add(p to level)
        val np = path + p.id + sp.map { it.id }
        kids.forEach { walk(it, level + 1, np) }
    }
    findRoots(all).forEach { walk(it, 0, emptySet()) }
    return out
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
    "list" -> "tree"
    else -> "top"
}

// ---------- লাইন অ্যানিমেশন ----------

/**
 * লাইনগুলো একটার পর একটা আঁকা হয় (কোনো নির্দিষ্ট মোট সময় নেই, প্রতিটি লাইনের একই গতি)।
 * t = এখন কত নম্বর লাইন চলছে (০, ১, ২ ...); p(n) = n নম্বর লাইনের কতটা আঁকা হয়েছে (০ থেকে ১)।
 * লাইনের মাথায় একটু বড় বল টানতে টানতে যায়।
 */
private class LineAnim(val t: State<Float>) {
    fun p(unit: Int): Float = (t.value - unit).coerceIn(0f, 1f)
}

private fun DrawScope.seg(color: Color, a: Offset, b: Offset, p: Float, sw: Float, ball: Float) {
    if (p <= 0f) return
    val end = Offset(a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p)
    drawLine(color, a, end, sw)
    if (p < 1f) drawCircle(color, ball, end)
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

/** একজনের সন্তানদের প্রতিটির জন্য কয়টা লাইন-ধাপ লাগে (আড়াআড়ি/খাড়া লাইন + নামার লাইন, বা শুধু একটি) */
private fun perChildUnits(layout: String, kids: Int): Int = when (layout) {
    "tree" -> 1
    "list" -> 2
    else -> if (kids == 1) 1 else 2
}

/** প্রতিটি সদস্যের লাইনগুলো কত নম্বর ধাপ থেকে শুরু, আর মোট কত ধাপ */
private class Seq(val base: Map<Long, Int>, val total: Int)

/** প্রজন্ম ধরে, প্রতি প্রজন্মে বাম থেকে ডানে ধাপ নম্বর বসানো */
private fun buildSeq(roots: List<Person>, all: List<Person>, opts: TreeOpts, layout: String): Seq {
    val hasStem = layout == "top" || layout == "left"
    val base = HashMap<Long, Int>()
    var next = 0
    val queue = ArrayDeque<Triple<Person, Set<Long>, Int>>()
    roots.forEach { queue.add(Triple(it, emptySet(), 0)) }
    while (queue.isNotEmpty()) {
        val (p, path, depth) = queue.removeFirst()
        val sp = spousesOf(p, all)
        val hasSp = opts.showWives && sp.isNotEmpty()
        val kids = childrenOf(p, sp, all, path).filter { it.id !in opts.hidden }
        base[p.id] = next
        next += (if (layout == "tree" && depth == 0) 1 else 0) +
            (if (hasSp) 1 else 0) +
            (if (hasStem && kids.isNotEmpty()) 1 else 0) +
            perChildUnits(layout, kids.size) * kids.size
        val np = path + p.id + sp.map { it.id }
        kids.forEach { queue.add(Triple(it, np, depth + 1)) }
    }
    return Seq(base, next)
}

private class TreeCtx(
    val all: List<Person>,
    val colors: Map<Long, Color>,
    val opts: TreeOpts,
    val anim: LineAnim,
    val layout: String,
    val base: Map<Long, Int>,
    val onEdit: (Person, Person?) -> Unit,
    val onReorder: (List<Long>) -> Unit
) {
    val hasStem: Boolean get() = layout == "top" || layout == "left"
}

/** এক সদস্যের নিজের লাইনগুলোর ধাপ নম্বর (-১ মানে এই লাইন নেই) */
private class NodeUnits(val trunk: Int, val spouse: Int, val stem: Int, val child0: Int)

private fun unitsOf(ctx: TreeCtx, id: Long, root: Boolean, hasSp: Boolean, hasKids: Boolean): NodeUnits {
    var o = ctx.base[id] ?: 0
    var trunk = -1
    var sp = -1
    var stem = -1
    if (ctx.layout == "tree" && root) { trunk = o; o++ }
    if (hasSp) { sp = o; o++ }
    if (ctx.hasStem && hasKids) { stem = o; o++ }
    return NodeUnits(trunk, sp, stem, o)
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
            "tree" -> {
                line(12f, 22f, 12f, 11f)
                line(12f, 17f, 6.5f, 11f); line(12f, 14f, 17.5f, 8.5f)
                dot(6f, 8.5f, 3f); dot(12f, 5f, 3f); dot(18f, 6f, 3f)
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
private fun enterActions(onSingle: () -> Unit = {}, onDouble: () -> Unit): KeyboardActions {
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
            st.job = scope.launch {
                delay(800)
                keyboard?.hide()
                focus.clearFocus()
            }
        }
    })
}

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
        lineT.animateTo(totalNow.toFloat(), tween((totalNow * 140).coerceAtLeast(1), easing = LinearEasing))
        lineT.snapTo(totalNow.toFloat())
        animDone = true
    }
    // অ্যানিমেশন শেষের পর সদস্য যোগ/হাইড-আনহাইডে নতুন লাইন সাথে সাথে দেখাবে
    LaunchedEffect(seq.total, animDone) {
        if (animDone) lineT.snapTo(seq.total.toFloat())
    }

    // নিচের বাটনগুলো ৫ সেকেন্ড পরে লুকায়, ডিসপ্লেতে ট্যাপ করলে ফেরে
    var buttonsVisible by remember { mutableStateOf(true) }
    var showTick by remember { mutableIntStateOf(0) }
    val anyDialog = dialog != null || settingsOpen || showHideOpen || restoreListOpen || aboutOpen ||
        pendingUri != null || pendingFile != null || actionFile != null || deleteFile != null
    LaunchedEffect(showTick, anyDialog) {
        buttonsVisible = true
        if (!anyDialog) {
            delay(5000)
            buttonsVisible = false
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingUri = uri }

    fun shareBackup(f: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "ব্যাকআপ শেয়ার করুন"))
        }.onFailure {
            Toast.makeText(context, "শেয়ার করা যায়নি", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (persons.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(24.dp)
                        .pointerInput(Unit) { detectTapGestures { showTick++ } },
                    contentAlignment = Alignment.Center
                ) {
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
                    onEdit = onEdit,
                    onReorder = { viewModel.reorder(it) }
                )
                // ফাঁকা জায়গায় একবার চাপলে: বাটন ফেরে এবং লাইন অ্যানিমেশন আবার চলে
                val onEmptyTap: () -> Unit = { replay++; showTick++ }
                // সব ধরনেই জুম ইন-আউট, ডাবল ট্যাপে ২x জুম, টেনে সরানো
                when (settings.layout) {
                    "list" -> {
                        ZoomPanBox(Modifier.fillMaxSize(), fitWidthOnly = true, onTap = onEmptyTap) {
                            Column(
                                Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                roots.forEach { root ->
                                    ListNode(root, ctx, emptySet(), true, true, -1, -1)
                                }
                            }
                        }
                    }
                    "left" -> {
                        ZoomPanBox(Modifier.fillMaxSize(), onTap = onEmptyTap) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                roots.forEach { root ->
                                    HTreeNode(root, ctx, emptySet(), Pos.NONE, -1, -1)
                                }
                            }
                        }
                    }
                    "tree" -> {
                        ZoomPanBox(Modifier.fillMaxSize(), alignBottom = true, onTap = onEmptyTap) {
                            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.Bottom) {
                                roots.forEach { root ->
                                    Box(Modifier.padding(horizontal = if (roots.size > 1) 16.dp else 0.dp)) {
                                        GTreeNode(root, ctx, emptySet(), 0)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        ZoomPanBox(Modifier.fillMaxSize(), onTap = onEmptyTap) {
                            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.Top) {
                                roots.forEach { root ->
                                    Box(Modifier.padding(horizontal = if (roots.size > 1) 16.dp else 0.dp)) {
                                        TreeNode(root, ctx, emptySet(), Pos.NONE, -1, -1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // নিচের মাঝখানে ৫টি বাটন, সমান ফাঁক: ট্রি ভিউ, হাইড/আনহাইড, +, রোটেট, সেটিংস
            AnimatedVisibility(
                visible = buttonsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ১. ট্রির ধরন বদলানো (চাপলে ১ → ২ → ৩ → ৪ → ১)
                    SmallFloatingActionButton(onClick = {
                        showTick++
                        settings.updateLayout(nextLayout(settings.layout))
                    }) { LayoutIcon(settings.layout) }
                    // ২. হাইড/আনহাইড
                    SmallFloatingActionButton(onClick = {
                        showTick++
                        settings.toggleHideActive()
                    }) {
                        Icon(
                            if (settings.hideActive) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (settings.hideActive) "আনহাইড" else "হাইড"
                        )
                    }
                    // ৩. নতুন সদস্য (মাঝখানে)
                    FloatingActionButton(onClick = {
                        showTick++
                        dialog = DialogState(null, defaultParents.first, defaultParents.second)
                    }) { Icon(Icons.Default.Add, contentDescription = "সদস্য যোগ করুন") }
                    // ৪. স্ক্রিন ঘোরানো
                    SmallFloatingActionButton(onClick = {
                        showTick++
                        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        activity?.requestedOrientation =
                            if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }) { Icon(Icons.Default.ScreenRotation, contentDescription = "স্ক্রিন ঘোরান") }
                    // ৫. সেটিংস (একেবারে ডানে)
                    SmallFloatingActionButton(onClick = {
                        showTick++
                        settingsOpen = true
                    }) { Icon(Icons.Default.Settings, contentDescription = "সেটিংস") }
                }
            }
        }
    }

    if (settingsOpen) {
        SettingsDialog(
            settings = settings,
            onShowHide = { settingsOpen = false; showHideOpen = true },
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
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            title = { Text("আমার বংশতালিকা") },
            text = {
                Text("সংস্করণ $version\n\nপরিবারের সদস্যদের নিয়ে বংশতালিকা বানানোর অফলাইন অ্যাপ। সব তথ্য আপনার ফোনেই থাকে।")
            },
            confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text("ঠিক আছে") } }
        )
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
                        if (ok) settings.clearHidden()
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
    onShowHide: () -> Unit,
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
                OutlinedButton(onClick = onShowHide, modifier = Modifier.fillMaxWidth()) { Text("শো/হাইড") }
                OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Text("ব্যাকআপ নিন") }
                OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("ব্যাকআপ রিস্টোর করুন") }
                OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("About") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ঠিক আছে") } }
    )
}

/**
 * যাদের নিচে ট্রি আছে তাদের নাম; বন্ধ করলে সেই নাম ও তার নিচের সবাই লুকায়।
 * পেছনে অন্ধকার পর্দা নেই, ট্রি স্ক্রিন পরিষ্কার দেখা যায়, তাই সুইচ বদলালেই ট্রির পরিবর্তন চোখে পড়ে।
 */
@Composable
private fun ShowHideDialog(all: List<Person>, settings: AppSettings, onDismiss: () -> Unit) {
    val rows = remember(all) { branchRows(all) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("শো/হাইড", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("ঠিক আছে") }
                    }
                    if (rows.isEmpty()) {
                        Text("যাদের নিচে ট্রি আছে এমন কোনো নাম এখনো নেই।")
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                "বন্ধ করলে সেই নাম ও তার নিচের সব নাম লুকাবে।",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            rows.forEach { (p, level) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(start = (level * 16).dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(p.name, Modifier.weight(1f))
                                    Switch(
                                        checked = p.id !in settings.hidden,
                                        onCheckedChange = { settings.toggleHidden(p.id) }
                                    )
                                }
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
    val onMove: (Float) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit
)

/** ট্রি (উপর থেকে নিচে): প্রতিটি নাম মাঝখানে, স্বামী/স্ত্রীরা তার নিচে, তার নিচে সন্তানরা */
@Composable
private fun TreeNode(
    person: Person,
    ctx: TreeCtx,
    path: Set<Long>,
    pos: Pos,
    barUnit: Int,
    dropUnit: Int,
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
    val u = unitsOf(ctx, person.id, pos == Pos.NONE, shownSpouses.isNotEmpty(), children.isNotEmpty())

    Column(
        modifier = Modifier
            .graphicsLayer { translationX = dragOffsetX }
            .drawBehind {
                // উপরের সংযোগ রেখা (পিতার দিকে), নিজের রঙে
                if (pos != Pos.NONE) {
                    val cx = size.width / 2
                    val h = 16.dp.toPx()
                    val sw = 3.dp.toPx()
                    val ball = 5.dp.toPx()
                    val x1 = if (pos == Pos.FIRST || pos == Pos.ONLY) cx else 0f
                    val x2 = if (pos == Pos.LAST || pos == Pos.ONLY) cx else size.width
                    if (x2 > x1) seg(color, Offset(x1, 0f), Offset(x2, 0f), ctx.anim.p(barUnit), sw, ball)
                    seg(color, Offset(cx, 0f), Offset(cx, h), ctx.anim.p(dropUnit), sw, ball)
                }
            }
            .padding(
                start = if (pos == Pos.NONE) 0.dp else 8.dp,
                end = if (pos == Pos.NONE) 0.dp else 8.dp,
                top = if (pos == Pos.NONE) 0.dp else 16.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NameButton(person.name, color, dragHandler) { ctx.onEdit(person, firstSpouse) }
        if (shownSpouses.isNotEmpty()) {
            LineBox(color, 3.dp, 10.dp, true) { ctx.anim.p(u.spouse) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                shownSpouses.forEach { s ->
                    NameButton(s.name, color, null) { ctx.onEdit(s, person) }
                }
            }
        }

        if (children.isNotEmpty()) {
            LineBox(childColor, 3.dp, 16.dp, true) { ctx.anim.p(u.stem) }

            var draggingId by remember { mutableStateOf<Long?>(null) }
            var dragX by remember { mutableFloatStateOf(0f) }
            val widths = remember { mutableStateMapOf<Long, Int>() }
            val latestChildren by rememberUpdatedState(children)
            val newPath = path + person.id + spouses.map { it.id }
            val per = perChildUnits(ctx.layout, children.size)

            // সব সন্তান এক লাইনে, তার নিচে পরের প্রজন্ম
            Row(verticalAlignment = Alignment.Top) {
                children.forEachIndexed { i, child ->
                    val childPos = when {
                        children.size == 1 -> Pos.ONLY
                        i == 0 -> Pos.FIRST
                        i == children.lastIndex -> Pos.LAST
                        else -> Pos.MIDDLE
                    }
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
                            pos = childPos,
                            barUnit = if (per == 2) u.child0 + 2 * i else -1,
                            dropUnit = u.child0 + per * i + (per - 1),
                            dragOffsetX = if (dragging) dragX else 0f,
                            dragHandler = DragCallbacks(
                                onStart = { draggingId = child.id; dragX = 0f },
                                onMove = { dragX += it },
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

/** ট্রি (বাম থেকে ডানে): প্রতি প্রজন্ম একটি কলামে, সন্তানরা ওপর-নিচে সাজানো */
@Composable
private fun HTreeNode(
    person: Person,
    ctx: TreeCtx,
    path: Set<Long>,
    pos: Pos,
    barUnit: Int,
    dropUnit: Int
) {
    val all = ctx.all
    val spouses = spousesOf(person, all)
    val shownSpouses = if (ctx.opts.showWives) spouses else emptyList()
    val children = childrenOf(person, spouses, all, path).filter { it.id !in ctx.opts.hidden }
    val color = ctx.colors[person.id] ?: Palette[0]
    val childColor = children.firstOrNull()?.let { ctx.colors[it.id] } ?: color
    val u = unitsOf(ctx, person.id, pos == Pos.NONE, shownSpouses.isNotEmpty(), children.isNotEmpty())

    Row(
        modifier = Modifier
            .drawBehind {
                if (pos != Pos.NONE) {
                    val cy = size.height / 2
                    val sw = 3.dp.toPx()
                    val ball = 5.dp.toPx()
                    val y1 = if (pos == Pos.FIRST || pos == Pos.ONLY) cy else 0f
                    val y2 = if (pos == Pos.LAST || pos == Pos.ONLY) cy else size.height
                    if (y2 > y1) seg(color, Offset(0f, y1), Offset(0f, y2), ctx.anim.p(barUnit), sw, ball)
                    seg(color, Offset(0f, cy), Offset(14.dp.toPx(), cy), ctx.anim.p(dropUnit), sw, ball)
                }
            }
            .padding(
                start = if (pos == Pos.NONE) 0.dp else 14.dp,
                top = if (pos == Pos.NONE) 0.dp else 5.dp,
                bottom = if (pos == Pos.NONE) 0.dp else 5.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NameButton(person.name, color, null) { ctx.onEdit(person, spouses.firstOrNull()) }
            shownSpouses.forEach { s ->
                LineBox(color, 3.dp, 8.dp, true) { ctx.anim.p(u.spouse) }
                NameButton(s.name, color, null) { ctx.onEdit(s, person) }
            }
        }
        if (children.isNotEmpty()) {
            LineBox(childColor, 14.dp, 3.dp, false) { ctx.anim.p(u.stem) }
            val newPath = path + person.id + spouses.map { it.id }
            val per = perChildUnits(ctx.layout, children.size)
            Column {
                children.forEachIndexed { i, child ->
                    val childPos = when {
                        children.size == 1 -> Pos.ONLY
                        i == 0 -> Pos.FIRST
                        i == children.lastIndex -> Pos.LAST
                        else -> Pos.MIDDLE
                    }
                    HTreeNode(
                        child, ctx, newPath, childPos,
                        if (per == 2) u.child0 + 2 * i else -1,
                        u.child0 + per * i + (per - 1)
                    )
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
    barUnit: Int,
    dropUnit: Int
) {
    val all = ctx.all
    val spouses = spousesOf(person, all)
    val shownSpouses = if (ctx.opts.showWives) spouses else emptyList()
    val children = childrenOf(person, spouses, all, path).filter { it.id !in ctx.opts.hidden }
    val color = ctx.colors[person.id] ?: Palette[0]
    val newPath = path + person.id + spouses.map { it.id }
    val u = unitsOf(ctx, person.id, isRoot, shownSpouses.isNotEmpty(), children.isNotEmpty())
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
            NameButton(person.name, color, null) { ctx.onEdit(person, spouses.firstOrNull()) }
            shownSpouses.forEach { s ->
                LineBox(color, 8.dp, 3.dp, false) { ctx.anim.p(u.spouse) }
                NameButton(s.name, color, null) { ctx.onEdit(s, person) }
            }
        }
        children.forEachIndexed { i, child ->
            ListNode(child, ctx, newPath, false, i == children.lastIndex, u.child0 + 2 * i, u.child0 + 2 * i + 1)
        }
    }
}

/** গাছের মতো ভিউ: গোড়া (মূল ব্যক্তি) নিচে, ডালপালা বেয়ে সন্তানরা উপরে; নামগুলো পাতার ভেতরে */
@Composable
private fun GTreeNode(person: Person, ctx: TreeCtx, path: Set<Long>, depth: Int) {
    val all = ctx.all
    val spouses = spousesOf(person, all)
    val shownSpouses = if (ctx.opts.showWives) spouses else emptyList()
    val children = childrenOf(person, spouses, all, path).filter { it.id !in ctx.opts.hidden }
    val color = ctx.colors[person.id] ?: Palette[0]
    val widths = remember { mutableStateMapOf<Long, Int>() }
    val newPath = path + person.id + spouses.map { it.id }
    val branchH = 56.dp
    val u = unitsOf(ctx, person.id, depth == 0, shownSpouses.isNotEmpty(), children.isNotEmpty())

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (children.isNotEmpty()) {
            Box(
                Modifier.drawBehind {
                    val hpx = branchH.toPx()
                    val px = size.width / 2
                    val py = size.height
                    val mid = py - hpx / 2
                    val rowBottom = py - hpx
                    val swPx = maxOf(3f, 8f - depth * 1.5f).dp.toPx()
                    val ball = swPx / 2 + 3.dp.toPx()
                    val pm = PathMeasure()
                    var acc = 0f
                    children.forEachIndexed { i, c ->
                        val w = (widths[c.id] ?: 0).toFloat()
                        val cx = acc + w / 2
                        acc += w
                        val p = ctx.anim.p(u.child0 + i)
                        if (p > 0f && w > 0f) {
                            val full = Path().apply {
                                moveTo(px, py)
                                cubicTo(px, mid, cx, mid, cx, rowBottom)
                            }
                            pm.setPath(full, false)
                            val len = pm.length
                            val part = Path()
                            pm.getSegment(0f, len * p, part, true)
                            drawPath(part, BranchColor, style = Stroke(width = swPx, cap = StrokeCap.Round))
                            if (p < 1f) drawCircle(BranchColor, ball, pm.getPosition(len * p))
                        }
                    }
                }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        children.forEach { c ->
                            Box(Modifier.onSizeChanged { widths[c.id] = it.width }) {
                                GTreeNode(c, ctx, newPath, depth + 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(branchH))
                }
            }
        }
        NameButton(person.name, color, null, leaf = true) { ctx.onEdit(person, spouses.firstOrNull()) }
        if (shownSpouses.isNotEmpty()) {
            LineBox(BranchColor, 3.dp, 10.dp, true) { ctx.anim.p(u.spouse) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                shownSpouses.forEach { s ->
                    NameButton(s.name, color, null, leaf = true) { ctx.onEdit(s, person) }
                }
            }
        }
        if (depth == 0) {
            // গাছের গুঁড়ি
            Spacer(
                Modifier.size(70.dp, 56.dp).drawBehind {
                    val p = ctx.anim.p(u.trunk)
                    val tw = 18.dp.toPx()
                    val hh = size.height * p
                    if (hh > 0f) {
                        drawRoundRect(
                            BranchColor,
                            topLeft = Offset((size.width - tw) / 2, size.height - hh),
                            size = Size(tw, hh),
                            cornerRadius = CornerRadius(4.dp.toPx())
                        )
                    }
                }
            )
        }
    }
}

private val LeafGreens = listOf(
    Color(0xFF2E7D32), Color(0xFF388E3C), Color(0xFF43A047), Color(0xFF558B2F),
    Color(0xFF689F38), Color(0xFF00796B), Color(0xFF33691E), Color(0xFF4CAF50)
)
private val LeafShape = RoundedCornerShape(topStart = 24.dp, topEnd = 4.dp, bottomEnd = 24.dp, bottomStart = 4.dp)

/**
 * শুধু নামের বাটন। চাপলে তথ্য খোলে; (সন্তান হলে) লম্বা চেপে ডানে-বামে টানলে স্থান বদলায়।
 * leaf = true হলে নামটি পাতার আকৃতির ভেতরে থাকে (গাছের ভিউয়ের জন্য)।
 */
@Composable
private fun NameButton(
    name: String,
    color: Color,
    dragHandler: DragCallbacks?,
    leaf: Boolean = false,
    onClick: () -> Unit
) {
    val currentClick by rememberUpdatedState(onClick)
    val currentDrag by rememberUpdatedState(dragHandler)
    val shape = if (leaf) LeafShape else RoundedCornerShape(10.dp)
    val bg = if (leaf) LeafGreens[Palette.indexOf(color).coerceAtLeast(0) % LeafGreens.size] else color
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .drawBehind {
                if (leaf) {
                    // পাতার শিরা
                    drawLine(
                        Color(0x66FFFFFF),
                        Offset(size.width * 0.15f, size.height * 0.85f),
                        Offset(size.width * 0.85f, size.height * 0.15f),
                        1.5.dp.toPx()
                    )
                }
            }
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
                                d.onMove(change.positionChange().x)
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
            .padding(horizontal = if (leaf) 20.dp else 14.dp, vertical = if (leaf) 10.dp else 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(name, color = Color.White, fontWeight = FontWeight.Medium)
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
 * সন্তানের নাম লিখে একবার Enter দিলে নামটি তালিকায় যুক্ত হয়, ঘর ফাঁকা হয়, কিবোর্ড হাইড হয়।
 * দ্রুত দুইবার Enter দিলে সব সংরক্ষিত হয়ে বন্ধ হয়।
 */
@Composable
private fun NameListEditor(
    title: String,
    names: SnapshotStateList<String>,
    pending: String,
    onPendingChange: (String) -> Unit,
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
            keyboardActions = enterActions(
                onSingle = { if (pending.trim().isNotEmpty()) add() },
                onDouble = onDoubleEnter
            ),
            trailingIcon = {
                IconButton(onClick = { add() }) {
                    Icon(Icons.Default.Add, contentDescription = "যোগ করুন")
                }
            },
            modifier = Modifier.fillMaxWidth()
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
                .pointerInput(Unit) { detectTapGestures { keyboard?.hide(); focus.clearFocus() } }
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
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
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
                        NameListEditor("সন্তান", newChildren, childPending, { childPending = it }, save)
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
