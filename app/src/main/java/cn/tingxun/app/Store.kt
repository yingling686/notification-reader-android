package cn.tingxun.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notices", indices = [Index(value = ["fingerprint"], unique = true)])
data class NoticeRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val sourceKey: String,
    val app: String,
    val label: String,
    val title: String,
    val body: String,
    val receivedAt: Long,
    val instant: Boolean,
    val state: String = "pending",
    val attempts: Int = 0
)
@Entity(tableName = "batches")
data class BatchRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noticeIds: String,
    val text: String,
    val createdAt: Long,
    val instant: Boolean = false,
    val summary: Boolean = false,
    val state: String = "ready",
    val cursor: Int = 0
)
@Dao
interface NoticeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: NoticeRow): Long
    @Insert suspend fun insertBatch(row: BatchRow): Long
    @Query("SELECT * FROM notices ORDER BY receivedAt DESC LIMIT 300") fun observe(): Flow<List<NoticeRow>>
    @Query("SELECT * FROM batches ORDER BY createdAt DESC LIMIT 100") fun observeBatches(): Flow<List<BatchRow>>
    @Query("SELECT count(*) FROM notices WHERE state IN ('pending','held','queued','failed')") fun countPending(): Flow<Int>
    @Query("SELECT * FROM notices WHERE state='pending' AND instant=:instant ORDER BY receivedAt LIMIT 50") suspend fun pending(instant: Boolean): List<NoticeRow>
    @Query("SELECT * FROM notices WHERE receivedAt >= :start AND receivedAt < :end AND app IN (:apps) AND state NOT IN ('held','failed','skipped') ORDER BY receivedAt DESC, id DESC LIMIT 51")
    suspend fun hourlyNotices(start: Long, end: Long, apps: List<String>): List<NoticeRow>
    @Query("SELECT * FROM batches WHERE state='ready' ORDER BY instant DESC, createdAt LIMIT 1") suspend fun nextBatch(): BatchRow?
    @Query("SELECT * FROM batches WHERE id=:id") suspend fun batch(id: Long): BatchRow?
    @Query("SELECT * FROM notices WHERE state IN ('pending','held','failed') ORDER BY receivedAt") suspend fun waiting(): List<NoticeRow>
    @Query("SELECT * FROM notices WHERE id IN (:ids)") suspend fun byIds(ids: List<Long>): List<NoticeRow>
    @Query("SELECT * FROM batches WHERE state='held'") suspend fun heldBatches(): List<BatchRow>
    @Query("UPDATE notices SET instant=:instant WHERE id=:id") suspend fun classify(id: Long, instant: Boolean)
    @Query("UPDATE notices SET state=:state WHERE id IN (:ids)") suspend fun state(ids: List<Long>, state: String)
    @Query("UPDATE notices SET attempts=attempts+1 WHERE id IN (:ids)") suspend fun attempt(ids: List<Long>)
    @Query("UPDATE batches SET state=:state WHERE id=:id") suspend fun batchState(id: Long, state: String)
    @Query("UPDATE batches SET cursor=:cursor WHERE id=:id") suspend fun cursor(id: Long, cursor: Int)
    @Query("UPDATE notices SET state='held' WHERE state='pending'") suspend fun holdPending()
    @Query("UPDATE batches SET state='held' WHERE state IN ('ready','playing')") suspend fun holdBatches()
    @Query("UPDATE notices SET state='pending', attempts=0 WHERE state IN ('held','failed')") suspend fun releaseNotices()
    @Query("UPDATE batches SET state='ready' WHERE state='held'") suspend fun releaseBatches()
    @Query("UPDATE notices SET state='skipped' WHERE state IN ('pending','held','queued','failed')") suspend fun clearPending()
    @Query("UPDATE batches SET state='skipped' WHERE state IN ('ready','held','playing')") suspend fun clearBatches()
    @Query("DELETE FROM notices WHERE receivedAt < :before") suspend fun expireNotices(before: Long)
    @Query("DELETE FROM batches WHERE createdAt < :before") suspend fun expireBatches(before: Long)
    @Query("DELETE FROM notices") suspend fun deleteNotices()
    @Query("DELETE FROM batches") suspend fun deleteBatches()
}
@Database(entities = [NoticeRow::class, BatchRow::class], version = 1, exportSchema = false)
abstract class TingDatabase : RoomDatabase() {
    abstract fun dao(): NoticeDao
    companion object { fun open(c: Context) = Room.databaseBuilder(c, TingDatabase::class.java, "tingxun.db").build() }
}

fun BatchRow.ids() = noticeIds.split(',').mapNotNull { it.toLongOrNull() }
