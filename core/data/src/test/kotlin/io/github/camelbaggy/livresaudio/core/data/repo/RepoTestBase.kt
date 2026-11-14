package io.github.camelbaggy.livresaudio.core.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** In-memory Room under Robolectric. */
@RunWith(RobolectricTestRunner::class)
abstract class RepoTestBase {
    protected lateinit var db: LibraryDatabase
    protected val dispatcher = Dispatchers.Unconfined

    @Before
    fun openDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }
}
