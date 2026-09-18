package com.cycling.beevideo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 观看进度与收藏的读写。
 *
 * 是 abstract class 而不是 interface：`@Transaction` 要求方法有实现体，而 [toggleKeep]
 * 必须是一次真正的原子读改写。拆成两个方法由仓储去串的话，中间那道缝会被连点两下踩到
 * （两次读都看到"未收藏"→ 写成两条，结果停在收藏态而非回到原点）。
 */
@Dao
abstract class LibraryDao {

    // ------------------------------------------------------------ 观看进度

    @Query("SELECT * FROM history WHERE vodId = :vodId")
    abstract suspend fun history(vodId: String): HistoryEntity?

    /** REPLACE 让"首次观看"和"更新进度"共用一条路径，不必先判断表里有没有行。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putHistory(row: HistoryEntity)

    // ---------------------------------------------------------------- 收藏

    @Query("SELECT * FROM keep ORDER BY createdAt DESC")
    abstract fun keeps(): Flow<List<KeepEntity>>

    /** 给界面用：收藏状态变化时自动重发。 */
    @Query("SELECT EXISTS(SELECT 1 FROM keep WHERE vodId = :vodId)")
    abstract fun isKept(vodId: String): Flow<Boolean>

    /** 给 [toggleKeep] 用：只要当前值，不需要订阅。 */
    @Query("SELECT EXISTS(SELECT 1 FROM keep WHERE vodId = :vodId)")
    abstract suspend fun isKeptOnce(vodId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putKeep(row: KeepEntity)

    @Query("DELETE FROM keep WHERE vodId = :vodId")
    abstract suspend fun deleteKeep(vodId: String)

    /** 切换收藏，返回切换后的状态（true = 已收藏）。 */
    @Transaction
    open suspend fun toggleKeep(row: KeepEntity): Boolean {
        if (isKeptOnce(row.vodId)) {
            deleteKeep(row.vodId)
            return false
        }
        putKeep(row)
        return true
    }
}
