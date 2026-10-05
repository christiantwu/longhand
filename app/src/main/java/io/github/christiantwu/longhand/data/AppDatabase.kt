package io.github.christiantwu.longhand.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Recording::class, Segment::class, SpeakerName::class, SpeakerVoice::class,
        KnownVoice::class, VoiceSample::class, VoiceRejection::class, Correction::class,
    ],
    version = 7,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun voices(): VoiceDao
    abstract fun corrections(): CorrectionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "transcripts.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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

        /**
         * 3 (0.9.0): Recognise voices. The people the user names (known_voices), the speakers whose
         * voices were learned under each name (voice_samples), and the suggestions turned down for a
         * speaker (voice_rejections). Nothing is learned from earlier names: the setting starts off.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `known_voices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_known_voices_nameKey` ON `known_voices` (`nameKey`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voice_samples` (`recordingId` INTEGER NOT NULL, `speaker` INTEGER NOT NULL, " +
                        "`voiceId` INTEGER NOT NULL, PRIMARY KEY(`recordingId`, `speaker`), " +
                        "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`voiceId`) REFERENCES `known_voices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_voice_samples_voiceId` ON `voice_samples` (`voiceId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voice_rejections` (`recordingId` INTEGER NOT NULL, `speaker` INTEGER NOT NULL, " +
                        "`voiceId` INTEGER NOT NULL, PRIMARY KEY(`recordingId`, `speaker`, `voiceId`), " +
                        "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`voiceId`) REFERENCES `known_voices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_voice_rejections_voiceId` ON `voice_rejections` (`voiceId`)")
            }
        }

        /**
         * 4 (0.9.0): common corrections and editing transcripts. Each line may keep its words' timings
         * (for splitting it), what the recogniser wrote before corrections or a hand edit, and whether it
         * was typed by hand; a call notes when it was last changed by hand; and the corrections themselves.
         * Earlier lines have none of these: their text is as recognised, never edited, without timings.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `segments` ADD COLUMN `words` TEXT")
                db.execSQL("ALTER TABLE `segments` ADD COLUMN `recognized` TEXT")
                db.execSQL("ALTER TABLE `segments` ADD COLUMN `edited` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `editedAt` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `corrections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `heard` TEXT NOT NULL, " +
                        "`heardKey` TEXT NOT NULL, `written` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_corrections_heardKey` ON `corrections` (`heardKey`)")
            }
        }

        /**
         * 5 (0.10.0): transcribing a call again in another language. Each call notes the language its transcript was
         * made in, and the language chosen for it by hand, if any. Earlier transcripts don't say; no call has a choice.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `language` TEXT")
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `pinnedLanguage` TEXT")
            }
        }

        /**
         * 6 (0.10.0): detecting each call's language. The languages heard in a call and how much speech it has, once
         * detected, and whether its transcript's language came from that. No call has been detected yet.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `spokenLanguages` TEXT")
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `speechSeconds` REAL")
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `languageDetected` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 7: shrinking WAV recordings after they're transcribed (off by default). A shrunk call keeps its call time and
         * notes its compressed copy's own file time and the WAV's size; each counts the times shrinking it started. No
         * call has been shrunk or tried yet.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `fileModified` INTEGER")
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `originalBytes` INTEGER")
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `shrinkAttempts` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
