package com.example.familytree

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "persons")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val gender: String = "M", // "M" = পুরুষ, "F" = মহিলা
    val birthYear: Int? = null,
    val fatherId: Long? = null,
    val motherId: Long? = null,
    val spouseId: Long? = null,
    // ভাইবোনদের মধ্যে সাজানোর ক্রম (টেনে বদলানো যায়)
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0
)
