package com.example.familytree

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    @Query("SELECT * FROM persons ORDER BY id")
    fun observeAll(): Flow<List<Person>>

    @Query("SELECT * FROM persons ORDER BY id")
    suspend fun getAll(): List<Person>

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun getById(id: Long): Person?

    @Insert
    suspend fun insert(person: Person): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Person>)

    @Update
    suspend fun update(person: Person)

    @Delete
    suspend fun delete(person: Person)

    @Query("UPDATE persons SET sortOrder = :order WHERE id = :id")
    suspend fun setOrder(id: Long, order: Int)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM persons")
    suspend fun maxOrder(): Int

    @Query("DELETE FROM persons")
    suspend fun deleteAll()

    @Query("UPDATE persons SET fatherId = NULL WHERE fatherId = :id")
    suspend fun clearFather(id: Long)

    @Query("UPDATE persons SET motherId = NULL WHERE motherId = :id")
    suspend fun clearMother(id: Long)

    @Query("UPDATE persons SET spouseId = NULL WHERE spouseId = :id")
    suspend fun clearSpouse(id: Long)
}
