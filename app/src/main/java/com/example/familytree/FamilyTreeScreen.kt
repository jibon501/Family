package com.example.familytree

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** ডায়ালগ খোলার তথ্য: person = null মানে নতুন সদস্য, সাথে বাবা/মায়ের ডিফল্ট */
private data class DialogState(
    val person: Person?,
    val defaultFatherId: Long? = null,
    val defaultMotherId: Long? = null
)

private enum class Pos { NONE, ONLY, FIRST, MIDDLE, LAST }

/** প্রতিটি পরিবারের (একই পিতা-মাতার সন্তানদের) জন্য আলাদা রঙ */
private val Palette = listOf(
    Color(0xFF1E88E5), Color(0xFF00897B), Color(0xFFEF6C00), Color(0xFF8E24AA),
    Color(0xFFE53935), Color(0xFF43A047), Color(0xFFD81B60), Color(0xFF3949AB),
    Color(0xFF6D4C41), Color(0xFF00838F)
)

/** যাদের বাবা-মা নেই, তারাই ট্রির শুরু */
private fun findRoots(all: List<Person>): List<Person> {
    val byId = all.associateBy { it.id }
    return all.filter { p ->
        val hasParents = byId[p.fatherId] != null || byId[p.motherId] != null
        if (hasParents) return@filter false
        val spouse = byId[p.spouseId]
        when {
            spouse == null -> true
            byId[spouse.fatherId] != null || byId[spouse.motherId] != null -> false
            else -> p.id < spouse.id
        }
    }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
}

private fun childrenOf(person: Person, spouse: Person?, all: List<Person>, exclude: Set<Long>): List<Person> =
    all.filter { c ->
        c.id !in exclude && c.id != person.id && c.id != spouse?.id &&
            (c.fatherId == person.id || c.motherId == person.id ||
                (spouse != null && (c.fatherId == spouse.id || c.motherId == spouse.id)))
    }.sortedWith(compareBy({ it.sortOrder }, { it.id }))

/** কার বাটন কোন রঙের: একই পিতা-মাতার সব সন্তানের একই রঙ */
private fun assignColors(roots: List<Person>, all: List<Person>): Map<Long, Color> {
    val byId = all.associateBy { it.id }
    val map = HashMap<Long, Color>()
    roots.forEach { map[it.id] = Palette[0] }
    var counter = 1
    val visited = HashSet<Long>()
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        val p = queue.removeFirst()
        if (!visited.add(p.id)) continue
        val own = map[p.id] ?: Palette[0]
        val spouse = byId[p.spouseId]
        spouse?.let { map.putIfAbsent(it.id, own) }
        val kids = childrenOf(p, spouse, all, visited)
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

@Composable
fun FamilyTreeScreen(viewModel: FamilyViewModel) {
    val persons by viewModel.persons.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    // নতুন সদস্য যোগের সময় বাবা-মায়ের ডিফল্ট (আগের বার যা ছিল)
    var defaultParents by remember { mutableStateOf<Pair<Long?, Long?>>(null to null) }
    val context = LocalContext.current
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
            FloatingActionButton(onClick = {
                dialog = DialogState(null, defaultParents.first, defaultParents.second)
            }) {
                Icon(Icons.Default.Add, contentDescription = "সদস্য যোগ করুন")
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
                                    onEdit = { p, spouse ->
                                        defaultParents = parentsOf(p, spouse)
                                        dialog = DialogState(p)
                                    },
                                    onReorder = { viewModel.reorder(it) }
                                )
                            }
                        }
                    }
                }
            }

            // উপরের ডানে ছোট মেনু (ব্যাকআপ / রিস্টোর)
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "মেনু")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
            onSave = { p, sons, daughters ->
                viewModel.saveWithChildren(p, sons, daughters)
                // নতুন সদস্য যোগ করলে পরেরবারও একই বাবা-মা ডিফল্ট থাকবে
                if (p.id == 0L && (p.fatherId != null || p.motherId != null)) {
                    defaultParents = p.fatherId to p.motherId
                }
                dialog = null
            },
            onDelete = { viewModel.delete(it); dialog = null }
        )
    }
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

@Composable
private fun TreeNode(
    person: Person,
    all: List<Person>,
    path: Set<Long>,
    pos: Pos,
    colors: Map<Long, Color>,
    onEdit: (Person, Person?) -> Unit,
    onReorder: (List<Long>) -> Unit,
    dragOffsetX: Float = 0f,
    dragHandler: DragCallbacks? = null
) {
    val spouse = all.find { it.id == person.spouseId }
    val children = childrenOf(person, spouse, all, path)
    val color = colors[person.id] ?: Palette[0]
    val childColor = children.firstOrNull()?.let { colors[it.id] } ?: color

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            NameButton(person.name, color, dragHandler) { onEdit(person, spouse) }
            if (spouse != null) {
                Box(Modifier.width(10.dp).height(3.dp).background(color))
                NameButton(spouse.name, color, null) { onEdit(spouse, person) }
            }
        }

        if (children.isNotEmpty()) {
            Box(Modifier.width(3.dp).height(16.dp).background(childColor))

            var draggingId by remember { mutableStateOf<Long?>(null) }
            var dragX by remember { mutableFloatStateOf(0f) }
            val widths = remember { mutableStateMapOf<Long, Int>() }
            val latestChildren by rememberUpdatedState(children)
            val newPath = path + person.id + (spouse?.id ?: -1L)

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

@Composable
private fun PersonPicker(
    label: String,
    options: List<Person>,
    selectedId: Long?,
    onSelected: (Long?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selected = options.find { it.id == selectedId }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.name ?: "— নেই —")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("— নেই —") }, onClick = { onSelected(null); open = false })
                options.forEach {
                    DropdownMenuItem(text = { Text(it.name) }, onClick = { onSelected(it.id); open = false })
                }
            }
        }
    }
}

/**
 * সন্তানের নাম লিখে কিবোর্ডের এন্টার (Done) চাপলে নামটি তালিকায় যুক্ত হয়ে ঘর ফাঁকা হবে।
 */
@Composable
private fun NameListEditor(
    title: String,
    names: SnapshotStateList<String>,
    pending: String,
    onPendingChange: (String) -> Unit
) {
    fun commit() {
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
            keyboardActions = KeyboardActions(onDone = { commit() }),
            trailingIcon = {
                IconButton(onClick = { commit() }) {
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
    onSave: (Person, List<String>, List<String>) -> Unit,
    onDelete: (Person) -> Unit
) {
    val person = state.person
    var name by remember { mutableStateOf(person?.name ?: "") }
    var gender by remember { mutableStateOf(person?.gender ?: "M") }
    var year by remember { mutableStateOf(person?.birthYear?.toString() ?: "") }
    // বাবা-মা: আগে থেকে ঠিক করা থাকবে, চাইলে বদলানো যাবে
    var fatherId by remember { mutableStateOf(person?.fatherId ?: state.defaultFatherId?.takeIf { id -> all.any { it.id == id } }) }
    var motherId by remember { mutableStateOf(person?.motherId ?: state.defaultMotherId?.takeIf { id -> all.any { it.id == id } }) }
    var spouseId by remember { mutableStateOf(person?.spouseId) }
    val sons = remember { mutableStateListOf<String>() }
    val daughters = remember { mutableStateListOf<String>() }
    var sonPending by remember { mutableStateOf("") }
    var daughterPending by remember { mutableStateOf("") }

    val others = all.filter { it.id != person?.id }

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
                PersonPicker("বাবা", others.filter { it.gender == "M" }, fatherId) { fatherId = it }
                PersonPicker("মা", others.filter { it.gender == "F" }, motherId) { motherId = it }
                PersonPicker("স্বামী/স্ত্রী", others, spouseId) { spouseId = it }

                HorizontalDivider()
                NameListEditor("ছেলে সন্তান", sons, sonPending) { sonPending = it }
                NameListEditor("মেয়ে সন্তান", daughters, daughterPending) { daughterPending = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        Person(
                            id = person?.id ?: 0,
                            name = name.trim(),
                            gender = gender,
                            birthYear = year.toIntOrNull(),
                            fatherId = fatherId,
                            motherId = motherId,
                            spouseId = spouseId,
                            sortOrder = person?.sortOrder ?: 0
                        ),
                        sons.toList() + listOf(sonPending.trim()),
                        daughters.toList() + listOf(daughterPending.trim())
                    )
                }
            ) { Text("সংরক্ষণ") }
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
