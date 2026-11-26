package com.boost12.rd12.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.boost12.rd12.data.local.entity.CommandHistoryEntity
import com.boost12.rd12.data.local.entity.CommandStateEntity
import com.boost12.rd12.data.local.entity.CustomCommandEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandStateDao {
    @Query("SELECT * FROM command_states")
    fun observeAll(): Flow<List<CommandStateEntity>>

    @Query("SELECT * FROM command_states WHERE commandId = :commandId LIMIT 1")
    suspend fun getById(commandId: String): CommandStateEntity?

    @Query("SELECT * FROM command_states")
    suspend fun getAllSnapshot(): List<CommandStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CommandStateEntity)

    @Query("DELETE FROM command_states WHERE commandId = :commandId")
    suspend fun deleteById(commandId: String)
}

@Dao
interface CommandHistoryDao {
    @Query("SELECT * FROM command_history ORDER BY timestampEpochMs DESC LIMIT 300")
    fun observeRecent(): Flow<List<CommandHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CommandHistoryEntity)

    @Query("DELETE FROM command_history")
    suspend fun clearAll()
}

@Dao
interface CustomCommandDao {
    @Query("SELECT * FROM custom_commands ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<CustomCommandEntity>>

    @Query("SELECT * FROM custom_commands")
    suspend fun getAllSnapshot(): List<CustomCommandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CustomCommandEntity)

    @Query("DELETE FROM custom_commands WHERE id = :id")
    suspend fun deleteById(id: String)
}
