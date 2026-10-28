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

class FamilyViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.personDao()

    val persons: StateFlow<List<Person>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** সদস্য সেভ করে, তারপর ছেলে/মেয়ে সন্তানদের নাম থাকলে তাদেরও যোগ করে */
    fun saveWithChildren(person: Person, sons: List<String>, daughters: List<String>) {
        viewModelScope.launch {
            val id = persist(person)
            val me = dao.getById(id) ?: return@launch
            val fatherId = if (me.gender == "M") me.id else me.spouseId
            val motherId = if (me.gender == "F") me.id else me.spouseId

            val newChildren = sons.map { it to "M" } + daughters.map { it to "F" }
            newChildren
                .filter { it.first.isNotBlank() }
                .forEach { (name, gender) ->
                    dao.insert(
                        Person(
                            name = name.trim(),
                            gender = gender,
                            fatherId = fatherId,
                            motherId = motherId
                        )
                    )
                }
        }
    }

    private suspend fun persist(person: Person): Long {
        val old = if (person.id != 0L) dao.getById(person.id) else null
        val id = if (person.id == 0L) dao.insert(person) else {
            dao.update(person)
            person.id
        }
        // আগের স্বামী/স্ত্রী বদলে গেলে পুরনো সংযোগ মুছে ফেলা
        val oldSpouse = old?.spouseId
        if (oldSpouse != null && oldSpouse != person.spouseId) {
            dao.getById(oldSpouse)?.let { dao.update(it.copy(spouseId = null)) }
        }
        // নতুন স্বামী/স্ত্রীর সাথে দুই দিকেই সংযোগ
        person.spouseId?.let { sid ->
            dao.getById(sid)?.let { s ->
                if (s.spouseId != id) dao.update(s.copy(spouseId = id))
            }
        }
        return id
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

    /** সব সদস্যের তথ্য JSON ফাইলে লেখে */
    fun backupTo(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val json = toJson(dao.getAll())
                    getApplication<Application>().contentResolver
                        .openOutputStream(uri, "wt")!!
                        .use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
            }
            onResult(ok)
        }
    }

    /** JSON ফাইল থেকে পড়ে বর্তমান সব তথ্য বদলে দেয় */
    fun restoreFrom(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri)!!
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                    val list = fromJson(text)
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
                }
            )
        }
        return JSONObject().put("app", "FamilyTree").put("version", 1).put("persons", arr).toString(2)
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
                spouseId = if (o.isNull("spouseId")) null else o.getLong("spouseId")
            )
        }
    }
}
