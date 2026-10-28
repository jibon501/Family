package com.example.familytree

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** ডায়ালগ খোলার তথ্য: person = null মানে নতুন সদস্য, আর বাবা/মায়ের ডিফল্ট */
private data class DialogState(
    val person: Person?,
    val defaultFatherId: Long? = null,
    val defaultMotherId: Long? = null
)

private enum class Pos { NONE, ONLY, FIRST, MIDDLE, LAST }

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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyTreeScreen(viewModel: FamilyViewModel) {
    val persons by viewModel.persons.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

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
        topBar = {
            TopAppBar(
                title = { Text("আমার বংশতালিকা") },
                actions = {
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
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialog = DialogState(null) }) {
                Icon(Icons.Default.Add, contentDescription = "সদস্য যোগ করুন")
            }
        }
    ) { padding ->
        if (persons.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "এখনো কোনো সদস্য নেই।\nনিচের + বোতামে চাপ দিয়ে প্রথম ব্যক্তিকে যোগ করুন।",
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val roots = remember(persons) { findRoots(persons) }
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val minWidth = maxWidth
                Box(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    // ট্রি ছোট হলেও যেন মাঝখানে থাকে
                    Column(
                        Modifier.widthIn(min = minWidth).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            roots.forEachIndexed { i, root ->
                                Box(Modifier.padding(horizontal = if (roots.size > 1) 12.dp else 0.dp)) {
                                    TreeNode(
                                        person = root,
                                        all = persons,
                                        path = emptySet(),
                                        pos = Pos.NONE,
                                        onEdit = { dialog = DialogState(it) },
                                        onAddChild = { parent, spouse ->
                                            val (f, m) = parentsOf(parent, spouse)
                                            dialog = DialogState(null, f, m)
                                        }
                                    )
                                }
                            }
                        }
                    }
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
                dialog = null
            },
            onDelete = { viewModel.delete(it); dialog = null }
        )
    }
}

/** কার সন্তান হবে: (বাবা, মা) */
private fun parentsOf(p: Person, spouse: Person?): Pair<Long?, Long?> =
    if (p.gender == "M") p.id to spouse?.id else spouse?.id to p.id

@Composable
private fun TreeNode(
    person: Person,
    all: List<Person>,
    path: Set<Long>,
    pos: Pos,
    onEdit: (Person) -> Unit,
    onAddChild: (Person, Person?) -> Unit
) {
    val spouse = all.find { it.id == person.spouseId }
    val children = all.filter { c ->
        c.id !in path && c.id != person.id && c.id != spouse?.id &&
            (c.fatherId == person.id || c.motherId == person.id ||
                (spouse != null && (c.fatherId == spouse.id || c.motherId == spouse.id)))
    }
    val lineColor = MaterialTheme.colorScheme.outline

    Column(
        modifier = Modifier
            .drawBehind {
                if (pos != Pos.NONE) {
                    val cx = size.width / 2
                    val h = 14.dp.toPx()
                    val sw = 2.dp.toPx()
                    drawLine(lineColor, Offset(cx, 0f), Offset(cx, h), sw)
                    val x1 = if (pos == Pos.FIRST || pos == Pos.ONLY) cx else 0f
                    val x2 = if (pos == Pos.LAST || pos == Pos.ONLY) cx else size.width
                    drawLine(lineColor, Offset(x1, 0f), Offset(x2, 0f), sw)
                }
            }
            .padding(
                start = if (pos == Pos.NONE) 0.dp else 6.dp,
                end = if (pos == Pos.NONE) 0.dp else 6.dp,
                top = if (pos == Pos.NONE) 0.dp else 14.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ব্যক্তি + স্বামী/স্ত্রী এক কার্ডে
        Card {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PersonChip(person, onEdit)
                if (spouse != null) {
                    Text("💍", Modifier.padding(horizontal = 6.dp))
                    PersonChip(spouse, onEdit)
                }
                IconButton(
                    onClick = { onAddChild(person, spouse) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "সন্তান যোগ করুন")
                }
            }
        }

        if (children.isNotEmpty()) {
            Box(Modifier.width(2.dp).height(14.dp).background(lineColor))
            // সব সন্তান এক লাইনে, তার নিচে পরের প্রজন্ম
            Row(verticalAlignment = Alignment.Top) {
                val newPath = path + person.id + (spouse?.id ?: -1L)
                children.forEachIndexed { i, child ->
                    val childPos = when {
                        children.size == 1 -> Pos.ONLY
                        i == 0 -> Pos.FIRST
                        i == children.lastIndex -> Pos.LAST
                        else -> Pos.MIDDLE
                    }
                    TreeNode(child, all, newPath, childPos, onEdit, onAddChild)
                }
            }
        }
    }
}

@Composable
private fun PersonChip(person: Person, onClick: (Person) -> Unit) {
    Column(
        Modifier.clickable { onClick(person) }.padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (person.gender == "F") "👩" else "👨")
        Text(person.name, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        person.birthYear?.let {
            Text("$it", style = MaterialTheme.typography.labelSmall)
        }
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
    var fatherId by remember { mutableStateOf(person?.fatherId ?: state.defaultFatherId) }
    var motherId by remember { mutableStateOf(person?.motherId ?: state.defaultMotherId) }
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
                            spouseId = spouseId
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
