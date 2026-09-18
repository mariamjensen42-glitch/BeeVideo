package com.cycling.beevideo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地数据库。
 *
 * **刻意不设** `fallbackToDestructiveMigration()`：它的效果是"找不到升级路径就整库删掉重建"，
 * 而观看进度和收藏是用户自己攒出来的数据、不是缓存 —— 用户升级 App 后无声清库，不报错、
 * 不提示，用户只会以为自己记错了。这里只允许逐版本写迁移：忘了写就会在启动时抛
 * "A migration from N to N+1 was required but not found"，在开发期就炸。
 *
 * ⚠️ 目前是 v1，还没有迁移。第一次改表结构时才加第一个 Migration。
 *
 * `exportSchema = true` 让 KSP 把表结构导到 `app/schemas/`（**进版本库**）。
 * 没有这些 JSON，写迁移就只能靠记忆，而记错列名同样会在用户机器上丢数据。
 */
@Database(
    entities = [HistoryEntity::class, KeepEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class BeeDatabase : RoomDatabase() {

    abstract fun library(): LibraryDao

    companion object {

        private const val NAME = "beevideo.db"

        fun create(context: Context): BeeDatabase =
            Room.databaseBuilder(context.applicationContext, BeeDatabase::class.java, NAME)
                // 刻意不写 .fallbackToDestructiveMigration()，理由见类注释
                .build()
    }
}
