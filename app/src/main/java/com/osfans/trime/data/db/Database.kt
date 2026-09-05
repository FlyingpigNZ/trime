// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DatabaseBean::class], version = 4)
@TypeConverters(DatabaseBean.Converters::class)
abstract class Database : RoomDatabase() {
    abstract fun databaseDao(): DatabaseDao

    companion object {
        /**
         * Legacy (pre-Room) `DbHelper` shipped `clipboard.db`/`collection.db`
         * with SQLiteOpenHelper versions 1-3: v1 stored the table as
         * `t_clipboard`, v2+ as `t_data` (id, text, html, type, time, all
         * nullable). These migrations bring such installs forward so old
         * upgrades do not crash with "A migration from 1 to 4 was required
         * but not found" (there is no fallbackToDestructiveMigration).
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE t_clipboard RENAME TO t_data")
                }
            }

        /** v2 → v3 was a version bump only; the table shape is unchanged. */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // no-op: legacy v2 already created t_data
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE ${DatabaseBean.TABLE_NAME} RENAME TO _t_data")
                    db.execSQL("ALTER TABLE _t_data ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS ${DatabaseBean.TABLE_NAME} (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            text TEXT,
                            html TEXT,
                            type INTEGER NOT NULL,
                            time INTEGER NOT NULL,
                            pinned INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO ${DatabaseBean.TABLE_NAME} (id, text, html, type, time, pinned)
                        SELECT id, text, html,
                               CASE WHEN type BETWEEN 0 AND 1 THEN type ELSE 0 END,
                               COALESCE(time, 0), pinned FROM _t_data
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE _t_data")
                }
            }
    }
}
