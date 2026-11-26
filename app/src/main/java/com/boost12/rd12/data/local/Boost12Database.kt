package com.boost12.rd12.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.boost12.rd12.data.local.dao.CommandHistoryDao
import com.boost12.rd12.data.local.dao.CommandStateDao
import com.boost12.rd12.data.local.dao.CustomCommandDao
import com.boost12.rd12.data.local.entity.CommandHistoryEntity
import com.boost12.rd12.data.local.entity.CommandStateEntity
import com.boost12.rd12.data.local.entity.CustomCommandEntity

@Database(
    entities = [
        CommandStateEntity::class,
        CommandHistoryEntity::class,
        CustomCommandEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class Boost12Database : RoomDatabase() {
    abstract fun commandStateDao(): CommandStateDao
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun customCommandDao(): CustomCommandDao
}
