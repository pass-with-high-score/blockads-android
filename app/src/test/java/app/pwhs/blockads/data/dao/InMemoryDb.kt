package app.pwhs.blockads.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.AppDatabase

fun inMemoryDb(): AppDatabase = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext(),
    AppDatabase::class.java,
).allowMainThreadQueries().build()
