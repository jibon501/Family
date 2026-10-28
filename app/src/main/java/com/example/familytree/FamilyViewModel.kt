package com.example.familytree

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** বাবা/মা/স্বামী/স্ত্রী: তালিকা থেকে বাছা (id) অথবা নতুন লেখা কাস্টম নাম (newName) */
data class Link(val id: Long?, val newName: String?)

class FamilyViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.personDao()

    val persons: StateFlow<List<Person>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun spouseIdsOf(p: Person, all: List<Person>): Set<Long> =
        (listOfNotNull(p.spouseId) + all.filter { it.spouseId == p.id && it.id != p.id }.map { it.id }).toSet()

    /**
     * সদস্য সেভ করে। বাবা/মা/স্ত্রী/স্বামীর নাম তালিকায় না থাকলে নতুন সদস্য হিসেবে বানায়।
     * তারপর নতুন ছেলে/মেয়ে যোগ করে এবং সন্তানদের নতুন ক্রম (childOrder) সেভ করে।
     */
    fun save(
        person: Person,
        father: Link,
        mother: Link,
        spouses: List<Link>,
        children: List<String>,
        childOrder: List<Long>
    ) {
        viewModelScope.launch {
            var next = dao.maxOrder() + 1

            suspend fun resolve(l: Link, gender: String): Long? =
                l.id ?: l.newName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    dao.insert(Person(name = it, gender = gender, sortOrder = next++))
                }

            val fatherId = resolve(father, "M")
            val motherId = resolve(mother, "F")
            val allBefore = dao.getAll()
            val old = if (person.id != 0L) dao.getById(person.id) else null
            val oldSpouseIds = old?.let { spouseIdsOf(it, allBefore) } ?: emptySet()

            val base = if (person.id == 0L) person.copy(sortOrder = next++) else person
            val spouseGender = if (base.gender == "M") "F" else "M"
            val spouseIds = spouses.mapNotNull { resolve(it, spouseGender) }
                .distinct().filter { it != person.id }

            val toSave = base.copy(
                fatherId = fatherId,
                motherId = motherId,
                // পুরুষের একাধিক স্ত্রী: স্ত্রীর দিকেই সংযোগ থাকে। মহিলার স্বামী: নিজের দিকে
                spouseId = if (base.gender == "M") null else spouseIds.firstOrNull()
            )
            val id = if (toSave.id == 0L) dao.insert(toSave) else {
                dao.update(toSave); toSave.id
            }

            // বাদ পড়া স্বামী/স্ত্রীর সংযোগ মুছে ফেলা
            (oldSpouseIds - spouseIds.toSet()).forEach { sid ->
                dao.getById(sid)?.let { s -> if (s.spouseId == id) dao.update(s.copy(spouseId = null)) }
            }
            // নতুন সংযোগ ও ক্রম
            if (base.gender == "M") {
                spouseIds.forEachIndexed { i, sid ->
                    dao.getById(sid)?.let { dao.update(it.copy(spouseId = id, spouseOrder = i)) }
                }
            } else {
                spouseIds.firstOrNull()?.let { sid ->
                    if (old?.spouseId != sid) {
                        val maxOrd = allBefore.filter { it.spouseId == sid }.maxOfOrNull { it.spouseOrder } ?: -1
                        dao.update(toSave.copy(id = id, spouseOrder = maxOrd + 1))
                    }
                }
            }

            // নতুন সন্তান (একাধিক স্ত্রী থাকলে প্রথম স্ত্রী মা হবে)
            val me = dao.getById(id) ?: return@launch
            val childFather = if (me.gender == "M") me.id else spouseIds.firstOrNull()
            val childMother = if (me.gender == "F") me.id else spouseIds.firstOrNull()
            children.filter { it.isNotBlank() }.forEach { name ->
                dao.insert(
                    Person(
                        name = name.trim(),
                        gender = "M",
                        fatherId = childFather,
                        motherId = childMother,
                        sortOrder = next++
                    )
                )
            }

            // সন্তানদের নতুন ক্রম
            childOrder.forEachIndexed { index, cid -> dao.setOrder(cid, index) }
        }
    }

    /** একই পিতা-মাতার সন্তানদের নতুন ক্রম সেভ করে (টেনে বদলানোর সময়) */
    fun reorder(orderedIds: List<Long>) {
        viewModelScope.launch {
            orderedIds.forEachIndexed { index, id -> dao.setOrder(id, index) }
        }
    }

    fun delete(person: Person) {
        viewModelScope.launch {
            dao.clearFather(person.id)
            dao.clearMother(person.id)
            dao.clearSpouse(person.id)
            dao.delete(person)
        }
    }

    // ---------- ব্যাকআপ / রিস্টোর ----------

    /** অ্যাপের নিজের ডাটা ফোল্ডার (Android/data/<প্যাকেজ>/files) */
    private fun backupDir(): File {
        val app = getApplication<Application>()
        return (app.getExternalFilesDir(null) ?: app.filesDir).also { it.mkdirs() }
    }

    /** ডাটা ফোল্ডারে আজকের তারিখ নামে (যেমন 2026-10-28.json) ব্যাকআপ রাখে। সফল হলে ফাইলের পথ দেয় */
    fun backupToFolder(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val name = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
                    val f = File(backupDir(), name)
                    f.writeText(toJson(dao.getAll()), Charsets.UTF_8)
                    f.absolutePath
                }.getOrNull()
            }
            onResult(path)
        }
    }

    /** ডাটা ফোল্ডারের ব্যাকআপ ফাইল, নতুন আগে */
    fun listBackups(): List<File> =
        backupDir().listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun restoreFromFile(file: File, onResult: (Boolean) -> Unit) {
        restore(onResult) { file.readText(Charsets.UTF_8) }
    }

    /** অন্য জায়গার JSON ফাইল থেকে রিস্টোর */
    fun restoreFrom(uri: Uri, onResult: (Boolean) -> Unit) {
        restore(onResult) {
            getApplication<Application>().contentResolver
                .openInputStream(uri)!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    /** পড়া তথ্য দিয়ে বর্তমান সব তথ্য বদলে দেয় */
    private fun restore(onResult: (Boolean) -> Unit, read: () -> String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val list = fromJson(read())
                    db.withTransaction {
                        dao.deleteAll()
                        dao.insertAll(list)
                    }
                }.isSuccess
            }
            onResult(ok)
        }
    }

    private fun toJson(list: List<Person>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("gender", p.gender)
                    put("birthYear", p.birthYear ?: JSONObject.NULL)
                    put("fatherId", p.fatherId ?: JSONObject.NULL)
                    put("motherId", p.motherId ?: JSONObject.NULL)
                    put("spouseId", p.spouseId ?: JSONObject.NULL)
                    put("sortOrder", p.sortOrder)
                    put("spouseOrder", p.spouseOrder)
                }
            )
        }
        return JSONObject().put("app", "FamilyTree").put("version", 2).put("persons", arr).toString(2)
    }

    private fun fromJson(text: String): List<Person> {
        val arr = JSONObject(text).getJSONArray("persons")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Person(
                id = o.getLong("id"),
                name = o.getString("name"),
                gender = o.optString("gender", "M"),
                birthYear = if (o.isNull("birthYear")) null else o.getInt("birthYear"),
                fatherId = if (o.isNull("fatherId")) null else o.getLong("fatherId"),
                motherId = if (o.isNull("motherId")) null else o.getLong("motherId"),
                spouseId = if (o.isNull("spouseId")) null else o.getLong("spouseId"),
                sortOrder = o.optInt("sortOrder", 0),
                spouseOrder = o.optInt("spouseOrder", 0)
            )
        }
    }
}
