package com.boost12.rd12.di

import android.content.Context
import androidx.room.Room
import com.boost12.rd12.data.adb.AdbRepositoryImpl
import com.boost12.rd12.data.adb.crypto.AdbCredentialStore
import com.boost12.rd12.data.local.Boost12Database
import com.boost12.rd12.data.local.dao.CommandHistoryDao
import com.boost12.rd12.data.local.dao.CommandStateDao
import com.boost12.rd12.data.local.dao.CustomCommandDao
import com.boost12.rd12.data.repository.CommandRepositoryImpl
import com.boost12.rd12.domain.repository.AdbRepository
import com.boost12.rd12.domain.repository.CommandRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAdbCredentialStore(
        @ApplicationContext context: Context
    ): AdbCredentialStore = AdbCredentialStore(context)

    @Provides
    @Singleton
    fun provideBoost12Database(
        @ApplicationContext context: Context
    ): Boost12Database = Room.databaseBuilder(
        context,
        Boost12Database::class.java,
        "boost12.db"
    ).fallbackToDestructiveMigration().build()

    @Provides
    fun provideCommandStateDao(db: Boost12Database): CommandStateDao = db.commandStateDao()

    @Provides
    fun provideCommandHistoryDao(db: Boost12Database): CommandHistoryDao = db.commandHistoryDao()

    @Provides
    fun provideCustomCommandDao(db: Boost12Database): CustomCommandDao = db.customCommandDao()

    @Provides
    @Singleton
    fun provideAdbRepository(
        @ApplicationContext context: Context,
        credentialStore: AdbCredentialStore
    ): AdbRepository = AdbRepositoryImpl(context, credentialStore)

    @Provides
    @Singleton
    fun provideCommandRepositoryImpl(
        adbRepository: AdbRepository,
        stateDao: CommandStateDao,
        historyDao: CommandHistoryDao,
        customCommandDao: CustomCommandDao
    ): CommandRepositoryImpl = CommandRepositoryImpl(
        adbRepository = adbRepository,
        stateDao = stateDao,
        historyDao = historyDao,
        customCommandDao = customCommandDao
    )

    @Provides
    @Singleton
    fun provideCommandRepository(impl: CommandRepositoryImpl): CommandRepository = impl
}
