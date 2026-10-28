package com.example.familytree

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** ডায়ালগ খোলার তথ্য: person = null মানে নতুন সদস্য, সাথে বাবা/মায়ের ডিফল্ট */
private data class DialogState(
    val person: Person?,
    val defaultFatherId: Long? = null,
    val defaultMotherId: Long? = null
)

private enum class Pos { NONE, ONLY, FIRST, MIDDLE, LAST }

/** সেটিংস অনুযায়ী ট্রিতে কী কী দেখানো হবে */
private data class TreeOpts(val showDaughters: Boolean, val showWives: Boolean)

/** ডায়ালগের বাবা/মা/স্ত্রী/স্বামীর ঘর: id থাকলে তালিকা থেকে বাছা, না থাকলে লেখা নাম */
private data class PickEntry(val id: Long?, val text: String)

private fun toLink(e: PickEntry, options: List<Person>): Link {
    val t = e.text.trim()
    if (e.id != null) return Link(e.id, null)
    if (t.isEmpty()) return Link(null, null)
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

@Composable
fun FamilyTreeScreen(viewModel: FamilyViewModel, settings: AppSettings) {
    val persons by viewModel.persons.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    // নতুন সদস্য যোগের সময় বাবা-মায়ের ডিফল্ট (আগের বার যা ছিল)
    var defaultParents by remember { mutableStateOf<Pair<Long?, Long?>>(null to null) }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.backupTo(uri) { ok ->
                Toast.makeText(context, if (ok) "ব্যাকআপ সম্পন্ন হয়েছে" else "ব্যাকআপ ব্যর্থ হয়েছে", Toast.LENGTH_LONG).show()
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingRestore = uri }

    Scaffold(
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // স্ক্রিন ঘোরানোর বাটন
                SmallFloatingActionButton(onClick = {
                    val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    activity?.requestedOrientation =
                        if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }) {
                    Icon(Icons.Default.ScreenRotation, contentDescription = "স্ক্রিন ঘোরান")
                }
                FloatingActionButton(onClick = {
                    dialog = DialogState(null, defaultParents.first, defaultParents.second)
                }) {
                    Icon(Icons.Default.Add, contentDescription = "সদস্য যোগ করুন")
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (persons.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "এখনো কোনো সদস্য নেই।\nনিচের + বোতামে চাপ দিয়ে প্রথম ব্যক্তিকে যোগ করুন।",
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val roots = remember(persons) { findRoots(persons) }
                val colors = remember(persons) { assignColors(roots, persons) }
                val opts = TreeOpts(settings.showDaughters, settings.showWives)
                val onEdit: (Person, Person?) -> Unit = { p, sp ->
                    defaultParents = parentsOf(p, sp)
                    dialog = DialogState(p)
                }
                when (settings.layout) {
                    "list" -> {
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 96.dp)
                        ) {
                            roots.forEach { root ->
                                ListNode(root, persons, emptySet(), 0, colors, opts, onEdit)
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                    "left" -> {
                        ZoomPanBox(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                roots.forEach { root ->
                                    HTreeNode(root, persons, emptySet(), Pos.NONE, colors, opts, onEdit)
                                }
                            }
                        }
                    }
                    else -> {
                        ZoomPanBox(Modifier.fillMaxSize()) {
                            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.Top) {
                                roots.forEach { root ->
                                    Box(Modifier.padding(horizontal = if (roots.size > 1) 16.dp else 0.dp)) {
                                        TreeNode(
                                            person = root,
                                            all = persons,
                                            path = emptySet(),
                                            pos = Pos.NONE,
                                            colors = colors,
                                            opts = opts,
                                            onEdit = onEdit,
                                            onReorder = { viewModel.reorder(it) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // উপরের ডানে ছোট মেনু (সেটিংস / ব্যাকআপ / রিস্টোর)
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "মেনু")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("সেটিংস") },
                        onClick = { menuOpen = false; settingsOpen = true }
                    )
                    DropdownMenuItem(
                        text = { Text("ব্যাকআপ নিন") },
                        onClick = { menuOpen = false; backupLauncher.launch("family-tree-backup.json") }
                    )
                    DropdownMenuItem(
                        text = { Text("ব্যাকআপ রিস্টোর করুন") },
                        onClick = { menuOpen = false; restoreLauncher.launch(arrayOf("*/*")) }
                    )
                    DropdownMenuItem(
                        text = { Text("সংস্করণ $version") },
                        enabled = false,
                        onClick = {}
                    )
                }
            }
        }
    }

    if (settingsOpen) {
        SettingsDialog(settings) { settingsOpen = false }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("রিস্টোর নিশ্চিত করুন") },
            text = { Text("বর্তমান সব তথ্য মুছে গিয়ে ব্যাকআপ ফাইলের তথ্য বসবে। চালিয়ে যাবেন?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    viewModel.restoreFrom(uri) { ok ->
                        Toast.makeText(context, if (ok) "রিস্টোর সম্পন্ন হয়েছে" else "রিস্টোর ব্যর্থ: ফাইলটি সঠিক নয়", Toast.LENGTH_LONG).show()
                    }
                }) { Text("হ্যাঁ, রিস্টোর করুন") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("বাতিল") } }
        )
    }

    dialog?.let { state ->
        PersonDialog(
            state = state,
            all = persons,
            onDismiss = { dialog = null },
            onSave = { p, father, mother, spouses, sons, daughters, childOrder ->
                viewModel.save(p, father, mother, spouses, sons, daughters, childOrder)
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
private fun SettingsDialog(settings: AppSettings, onDismiss: () -> Unit) {
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
                SwitchRow("মেয়েদের নাম দেখান", settings.showDaughters) { settings.updateShowDaughters(it) }
                SwitchRow("স্ত্রীর নাম দেখান", settings.showWives) { settings.updateShowWives(it) }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("ট্রির ধরন", style = MaterialTheme.typography.labelLarge)
                ChoiceRow("উপর থেকে নিচে", settings.layout == "top") { settings.updateLayout("top") }
                ChoiceRow("বাম থেকে ডানে", settings.layout == "left") { settings.updateLayout("left") }
                ChoiceRow("তালিকা", settings.layout == "list") { settings.updateLayout("list") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ঠিক আছে") } }
    )
}

/**
 * দুই আঙুলে জুম ইন/আউট ও টেনে সরানো। জুম আউট করলে সর্বোচ্চ ততটুকু যাবে
 * যতটুকুতে পুরো ট্রি স্ক্রিনে ধরে। শুরুতেই পুরো ট্রি স্ক্রিনে ফিট হয়।
 */
@Composable
private fun ZoomPanBox(modifier: Modifier, content: @Composable () -> Unit) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun fitScale(): Float {
        if (container == IntSize.Zero || contentSize == IntSize.Zero) return 1f
        val sx = container.width.toFloat() / contentSize.width
        val sy = container.height.toFloat() / contentSize.height
        return minOf(1f, sx, sy)
    }

    fun clampOffset(s: Float, o: Offset): Offset {
        val w = contentSize.width * s
        val h = contentSize.height * s
        val x = if (w <= container.width) (container.width - w) / 2f
        else o.x.coerceIn(container.width - w, 0f)
        val y = if (h <= container.height) 0f
        else o.y.coerceIn(container.height - h, 0f)
        return Offset(x, y)
    }

    // ট্রির আকার বা স্ক্রিন (portrait/landscape) বদলালে আবার পুরো ট্রি ফিট করা
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

/** ট্রি (উপর থেকে নিচে)। সবার উপরের নাম মাঝখানে, স্বামী/স্ত্রীরা তার নিচে */
@Composable
private fun TreeNode(
    person: Person,
    all: List<Person>,
    path: Set<Long>,
    pos: Pos,
    colors: Map<Long, Color>,
    opts: TreeOpts,
    onEdit: (Person, Person?) -> Unit,
    onReorder: (List<Long>) -> Unit,
    dragOffsetX: Float = 0f,
    dragHandler: DragCallbacks? = null
) {
    val spouses = spousesOf(person, all)
    val shownSpouses = if (opts.showWives) spouses else emptyList()
    val children = childrenOf(person, spouses, all, path)
        .filter { opts.showDaughters || it.gender != "F" }
    val color = colors[person.id] ?: Palette[0]
    val childColor = children.firstOrNull()?.let { colors[it.id] } ?: color
    val firstSpouse = spouses.firstOrNull()

    Column(
        modifier = Modifier
            .graphicsLayer { translationX = dragOffsetX }
            .drawBehind {
                // উপরের সংযোগ রেখা (পিতার দিকে), নিজের রঙে
                if (pos != Pos.NONE) {
                    val cx = size.width / 2
                    val h = 16.dp.toPx()
                    val sw = 3.dp.toPx()
                    drawLine(color, Offset(cx, 0f), Offset(cx, h), sw)
                    val x1 = if (pos == Pos.FIRST || pos == Pos.ONLY) cx else 0f
                    val x2 = if (pos == Pos.LAST || pos == Pos.ONLY) cx else size.width
                    drawLine(color, Offset(x1, 0f), Offset(x2, 0f), sw)
                }
            }
            .padding(
                start = if (pos == Pos.NONE) 0.dp else 8.dp,
                end = if (pos == Pos.NONE) 0.dp else 8.dp,
                top = if (pos == Pos.NONE) 0.dp else 16.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (pos == Pos.NONE && shownSpouses.isNotEmpty()) {
            // সবার উপরের নাম মাঝখানে, স্ত্রীরা নিচের সারিতে
            NameButton(person.name, color, dragHandler) { onEdit(person, firstSpouse) }
            Box(Modifier.width(3.dp).height(10.dp).background(color))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                shownSpouses.forEach { s ->
                    NameButton(s.name, color, null) { onEdit(s, person) }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NameButton(person.name, color, dragHandler) { onEdit(person, firstSpouse) }
                shownSpouses.forEach { s ->
                    Box(Modifier.width(10.dp).height(3.dp).background(color))
                    NameButton(s.name, color, null) { onEdit(s, person) }
                }
            }
        }

        if (children.isNotEmpty()) {
            Box(Modifier.width(3.dp).height(16.dp).background(childColor))

            var draggingId by remember { mutableStateOf<Long?>(null) }
            var dragX by remember { mutableFloatStateOf(0f) }
            val widths = remember { mutableStateMapOf<Long, Int>() }
            val latestChildren by rememberUpdatedState(children)
            val newPath = path + person.id + spouses.map { it.id }

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
                            all = all,
                            path = newPath,
                            pos = childPos,
                            colors = colors,
                            opts = opts,
                            onEdit = onEdit,
                            onReorder = onReorder,
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
                                        val centers = ws.indices.map { idx -> ws.take(idx).sum() + ws[idx] / 2f }
                                        val dragCenter = centers[d] + dragX
                                        val newIndex = list.indices.count { it != d && centers[it] < dragCenter }
                                        val reordered = list.filterIndexed { idx, _ -> idx != d }
                                            .toMutableList().apply { add(newIndex, list[d]) }
                                        if (reordered.map { it.id } != list.map { it.id }) {
                                            onReorder(reordered.map { it.id })
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
    all: List<Person>,
    path: Set<Long>,
    pos: Pos,
    colors: Map<Long, Color>,
    opts: TreeOpts,
    onEdit: (Person, Person?) -> Unit
) {
    val spouses = spousesOf(person, all)
    val shownSpouses = if (opts.showWives) spouses else emptyList()
    val children = childrenOf(person, spouses, all, path)
        .filter { opts.showDaughters || it.gender != "F" }
    val color = colors[person.id] ?: Palette[0]
    val childColor = children.firstOrNull()?.let { colors[it.id] } ?: color

    Row(
        modifier = Modifier
            .drawBehind {
                if (pos != Pos.NONE) {
                    val cy = size.height / 2
                    val sw = 3.dp.toPx()
                    drawLine(color, Offset(0f, cy), Offset(14.dp.toPx(), cy), sw)
                    val y1 = if (pos == Pos.FIRST || pos == Pos.ONLY) cy else 0f
                    val y2 = if (pos == Pos.LAST || pos == Pos.ONLY) cy else size.height
                    drawLine(color, Offset(0f, y1), Offset(0f, y2), sw)
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
            NameButton(person.name, color, null) { onEdit(person, spouses.firstOrNull()) }
            shownSpouses.forEach { s ->
                Box(Modifier.width(3.dp).height(8.dp).background(color))
                NameButton(s.name, color, null) { onEdit(s, person) }
            }
        }
        if (children.isNotEmpty()) {
            Box(Modifier.width(14.dp).height(3.dp).background(childColor))
            val newPath = path + person.id + spouses.map { it.id }
            Column {
                children.forEachIndexed { i, child ->
                    val childPos = when {
                        children.size == 1 -> Pos.ONLY
                        i == 0 -> Pos.FIRST
                        i == children.lastIndex -> Pos.LAST
                        else -> Pos.MIDDLE
                    }
                    HTreeNode(child, all, newPath, childPos, colors, opts, onEdit)
                }
            }
        }
    }
}

/** তালিকা: প্রজন্ম অনুযায়ী ভেতরে সরিয়ে সাজানো */
@Composable
private fun ListNode(
    person: Person,
    all: List<Person>,
    path: Set<Long>,
    level: Int,
    colors: Map<Long, Color>,
    opts: TreeOpts,
    onEdit: (Person, Person?) -> Unit
) {
    val spouses = spousesOf(person, all)
    val shownSpouses = if (opts.showWives) spouses else emptyList()
    val children = childrenOf(person, spouses, all, path)
        .filter { opts.showDaughters || it.gender != "F" }
    val color = colors[person.id] ?: Palette[0]
    val newPath = path + person.id + spouses.map { it.id }

    Column {
        Row(
            Modifier.padding(start = (level * 22).dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (level > 0) Box(Modifier.width(12.dp).height(3.dp).background(color))
            NameButton(person.name, color, null) { onEdit(person, spouses.firstOrNull()) }
            shownSpouses.forEach { s ->
                Box(Modifier.width(8.dp).height(3.dp).background(color))
                NameButton(s.name, color, null) { onEdit(s, person) }
            }
        }
        children.forEach { child ->
            ListNode(child, all, newPath, level + 1, colors, opts, onEdit)
        }
    }
}

/** শুধু নামের বাটন। চাপলে তথ্য খোলে; (সন্তান হলে) লম্বা চেপে ডানে-বামে টানলে স্থান বদলায় */
@Composable
private fun NameButton(name: String, color: Color, dragHandler: DragCallbacks?, onClick: () -> Unit) {
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
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(name, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

/**
 * বাবা/মা/স্বামী/স্ত্রীর ঘর। লিখলে মিলে যাওয়া নামের তালিকা আসে, তালিকা থেকে বাছা যায়।
 * তালিকায় না পেলে যেকোনো নাম লিখে দিলে সেভ করার সময় নতুন সদস্য হিসেবে যুক্ত হয়।
 */
@Composable
private fun PersonPicker(
    label: String,
    options: List<Person>,
    entry: PickEntry,
    modifier: Modifier = Modifier,
    onChange: (PickEntry) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val q = entry.text.trim()
    val matches = options.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    val show = focused && entry.id == null && matches.isNotEmpty()
    Box(modifier) {
        OutlinedTextField(
            value = entry.text,
            onValueChange = { onChange(PickEntry(null, it)) },
            label = { Text(label) },
            singleLine = true,
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
 * সন্তানের নাম লিখে কিবোর্ডের এন্টার চাপলে নামটি তালিকায় যুক্ত হয়ে ঘর ফাঁকা হবে।
 * ঘর ফাঁকা থাকা অবস্থায় আবার এন্টার চাপলে (মানে পর পর দুইবার) সব সংরক্ষিত হয়ে বন্ধ হবে।
 */
@Composable
private fun NameListEditor(
    title: String,
    names: SnapshotStateList<String>,
    pending: String,
    onPendingChange: (String) -> Unit,
    onSubmitEmpty: () -> Unit
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
            keyboardActions = KeyboardActions(onDone = {
                if (pending.trim().isNotEmpty()) add()
                else if (names.isNotEmpty()) onSubmitEmpty()
            }),
            trailingIcon = {
                IconButton(onClick = { add() }) {
                    Icon(Icons.Default.Add, contentDescription = "যোগ করুন")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PersonDialog(
    state: DialogState,
    all: List<Person>,
    onDismiss: () -> Unit,
    onSave: (Person, Link, Link, List<Link>, List<String>, List<String>, List<Long>) -> Unit,
    onDelete: (Person) -> Unit
) {
    val person = state.person
    var name by remember { mutableStateOf(person?.name ?: "") }
    var gender by remember { mutableStateOf(person?.gender ?: "M") }
    var year by remember { mutableStateOf(person?.birthYear?.toString() ?: "") }
    // বাবা-মা: আগে থেকে ঠিক করা থাকবে, চাইলে বদলানো যাবে বা অন্য নাম লেখা যাবে
    val initFatherId = person?.fatherId ?: state.defaultFatherId?.takeIf { id -> all.any { it.id == id } }
    val initMotherId = person?.motherId ?: state.defaultMotherId?.takeIf { id -> all.any { it.id == id } }
    var father by remember { mutableStateOf(PickEntry(initFatherId, all.find { it.id == initFatherId }?.name ?: "")) }
    var mother by remember { mutableStateOf(PickEntry(initMotherId, all.find { it.id == initMotherId }?.name ?: "")) }
    // স্বামী/স্ত্রী: পুরুষের একাধিক স্ত্রী যোগ করা যায়
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
    val sons = remember { mutableStateListOf<String>() }
    val daughters = remember { mutableStateListOf<String>() }
    var sonPending by remember { mutableStateOf("") }
    var daughterPending by remember { mutableStateOf("") }

    val others = all.filter { it.id != person?.id }
    val fatherOptions = others.filter { it.gender == "M" }
    val motherOptions = others.filter { it.gender == "F" }
    val spouseOptions = others.filter { it.gender != gender }

    val save: () -> Unit = {
        if (name.isNotBlank()) {
            val spouseEntries = if (gender == "M") spouses.toList() else spouses.take(1)
            onSave(
                Person(
                    id = person?.id ?: 0,
                    name = name.trim(),
                    gender = gender,
                    birthYear = year.toIntOrNull(),
                    sortOrder = person?.sortOrder ?: 0,
                    spouseOrder = person?.spouseOrder ?: 0
                ),
                toLink(father, fatherOptions),
                toLink(mother, motherOptions),
                spouseEntries.map { toLink(it, spouseOptions) }.filter { it.id != null || it.newName != null },
                sons.toList() + sonPending.trim(),
                daughters.toList() + daughterPending.trim(),
                if (childOrder.toList() != myChildren) childOrder.toList() else emptyList()
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // বাইরে চাপলে বা কিবোর্ড খুললে ডায়ালগ বন্ধ হবে না
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (person == null) "নতুন সদস্য" else "সদস্যের তথ্য") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("নাম") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = year, onValueChange = { year = it.filter(Char::isDigit).take(4) },
                    label = { Text("জন্মসাল (ঐচ্ছিক)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = gender == "M", onClick = { gender = "M" })
                    Text("পুরুষ")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(selected = gender == "F", onClick = { gender = "F" })
                    Text("মহিলা")
                }
                PersonPicker("বাবা", fatherOptions, father) { father = it }
                PersonPicker("মা", motherOptions, mother) { mother = it }

                // স্বামী / স্ত্রী (একাধিক স্ত্রী)
                val spouseLabel = if (gender == "M") "স্ত্রী" else "স্বামী"
                val multi = gender == "M"
                spouses.forEachIndexed { i, entry ->
                    if (!multi && i > 0) return@forEachIndexed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PersonPicker(
                            label = if (multi && spouses.size > 1) "$spouseLabel ${i + 1}" else spouseLabel,
                            options = spouseOptions,
                            entry = entry,
                            modifier = Modifier.weight(1f)
                        ) { spouses[i] = it }
                        if (multi && spouses.size > 1) {
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
                if (multi) {
                    TextButton(onClick = { spouses.add(PickEntry(null, "")) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("আরেকজন স্ত্রী যোগ করুন")
                    }
                }

                // বর্তমান ছেলে-মেয়ে: ক্রম ঠিক করা যাবে
                if (childOrder.isNotEmpty()) {
                    HorizontalDivider()
                    Text("ছেলে-মেয়ে (ক্রম ঠিক করুন)", style = MaterialTheme.typography.labelMedium)
                    childOrder.forEachIndexed { i, cid ->
                        val c = all.find { it.id == cid }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}. ${c?.name.orEmpty()} (${if (c?.gender == "F") "মেয়ে" else "ছেলে"})",
                                Modifier.weight(1f)
                            )
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
                NameListEditor("ছেলে সন্তান", sons, sonPending, { sonPending = it }, save)
                NameListEditor("মেয়ে সন্তান", daughters, daughterPending, { daughterPending = it }, save)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { save() }) { Text("সংরক্ষণ") }
        },
        dismissButton = {
            Row {
                if (person != null) {
                    TextButton(onClick = { onDelete(person) }) {
                        Text("মুছুন", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("বাতিল") }
            }
        }
    )
}
