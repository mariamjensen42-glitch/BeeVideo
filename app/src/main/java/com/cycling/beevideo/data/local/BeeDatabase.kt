package com.cycling.beevideo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地数据库。
 *
 * **刻意不设** `fallbackToDestructiveMigration()`：它的效果是"找不到升级路径就整库删掉重建"，
 * 而观看进度和收藏是用户自己攒出来的数据、不是缓存 —— 用户升级 App 后无声清库，不报错、
 * 不提示，用户只会以为自己记错了。这里只允许逐版本写迁移：忘了写就会在启动时抛
 * "A migration from N to N+1 was required but not found"，在开发期就炸。
 *
 * `exportSchema = true` 让 KSP 把表结构导到 `app/schemas/`（**进版本库**）。
 * 没有这些 JSON，写迁移就只能靠记忆，而记错列名同样会在用户机器上丢数据。
 */
@Database(
    entities = [HistoryEntity::class, KeepEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class BeeDatabase : RoomDatabase() {

    abstract fun library(): LibraryDao

    companion object {

        private const val NAME = "beevideo.db"

        /**
         * v1 → v2：`history` 补上线路号与四个快照字段。
         *
         * 全部带默认值，于是**老记录自动降级成"没有快照"** —— 历史页会退回用 vodId 当标题，
         * 而进度本身（续播的判据）一个字节都没动。这比"清库重来"好得多：用户升级后
         * 续播仍然准，只是那几条旧记录的封面与片名要等下次播放时才补上。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN lineIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE history ADD COLUMN name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE history ADD COLUMN pic TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE history ADD COLUMN score TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE history ADD COLUMN remarks TEXT NOT NULL DEFAULT ''")
            }
        }

        fun create(context: Context): BeeDatabase =
            Room.databaseBuilder(context.applicationContext, BeeDatabase::class.java, NAME)
                // 刻意不写 .fallbackToDestructiveMigration()，理由见类注释
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
