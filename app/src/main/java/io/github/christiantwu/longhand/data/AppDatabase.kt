package io.github.christiantwu.longhand.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Recording::class, Segment::class, SpeakerName::class, SpeakerVoice::class],
    version = 2,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "transcripts.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }

        /**
         * 2 (0.5.0): which pipeline made each transcript, so the old ones can be redone with the
         * new speaker separation, and whether its notification has gone out. Existing transcripts
         * count as pipeline 1 and announced, except calls of the last two days still waiting for
         * their first summary (not the backlog queued when the summary model was installed), with
         * fresh attempts for their redo.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN pipeline INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE recordings ADD COLUMN announced INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE recordings SET attempts = 0 WHERE status = 'DONE'")
                db.execSQL(
                    "UPDATE recordings SET announced = 0 WHERE status = 'DONE' AND summaryStatus IN ('PENDING', 'PROCESSING') " +
                        "AND topic IS NULL AND transcribedAt >= (strftime('%s', 'now') - 2 * 86400) * 1000"
                )
            }
        }
    }
}
