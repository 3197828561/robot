package com.robot.solar.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.robot.solar.entity.StructuredLogEntity

@Database(entities = [StructuredLogEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "solar_robot.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `eventId` TEXT NOT NULL,
                        `timestampMillis` INTEGER NOT NULL,
                        `deviceId` TEXT,
                        `productType` TEXT,
                        `source` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `severity` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `topic` TEXT,
                        `cmdId` TEXT,
                        `missionId` TEXT,
                        `action` TEXT,
                        `result` TEXT,
                        `summary` TEXT NOT NULL,
                        `detailJson` TEXT,
                        `dedupeKey` TEXT,
                        `repeatCount` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO app_logs (
                        id, eventId, timestampMillis, source, category, eventType,
                        severity, direction, summary, repeatCount
                    )
                    SELECT
                        id,
                        'legacy:' || id,
                        timestamp,
                        'LEGACY',
                        CASE type
                            WHEN 0 THEN 'AUTH'
                            WHEN 1 THEN 'DEVICE'
                            ELSE 'SYSTEM'
                        END,
                        'legacy',
                        'INFO',
                        'LOCAL',
                        content,
                        1
                    FROM solar_logs
                    WHERE content NOT IN (
                        '收到设备在线心跳',
                        '设备运行状态已更新',
                        '机器人地图位置已更新'
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    DELETE FROM app_logs
                    WHERE timestampMillis < (strftime('%s', 'now', '-30 days') * 1000)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    DELETE FROM app_logs
                    WHERE id NOT IN (
                        SELECT id FROM app_logs ORDER BY timestampMillis DESC LIMIT 2000
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_app_logs_eventId` ON `app_logs` (`eventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_logs_timestampMillis` ON `app_logs` (`timestampMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_logs_deviceId_timestampMillis` ON `app_logs` (`deviceId`, `timestampMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_logs_category_timestampMillis` ON `app_logs` (`category`, `timestampMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_logs_cmdId` ON `app_logs` (`cmdId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_logs_missionId` ON `app_logs` (`missionId`)")
            }
        }
    }
}
