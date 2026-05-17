package woowacourse.shopping.data.local.recentproduct

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import woowacourse.shopping.data.local.RecentProductDatabase

@RunWith(AndroidJUnit4::class)
class RecentProductDaoTrimBenchmarkTest {

    private lateinit var db: RecentProductDatabase
    private lateinit var dao: RecentProductDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecentProductDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.recentProductDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun compareTrimStrategies() = runBlocking {
        val limit = 10
        val insertCounts = listOf(20, 100, 1_000, 10_000)
        val iterations = 5

        warmup(insertCount = 1_000, limit = limit)

        println("=== RecentProduct 운영 패턴 trim 전략 성능 비교 (limit=$limit) ===")
        println("insertCount | DB-side total(ms) | APP-side total(ms) | ratio(APP/DB)")
        println("------------|-------------------|--------------------|---------------")

        insertCounts.forEach { count ->
            val dbSideAvgMs = averageMillis(iterations) {
                clearTable()
                measureNanoTime {
                    repeat(count) { i ->
                        insert(i)
                        dao.trim(limit)
                    }
                }
            }
            val appSideAvgMs = averageMillis(iterations) {
                clearTable()
                measureNanoTime {
                    repeat(count) { i ->
                        insert(i)
                        trimOnApplicationSide(limit)
                    }
                }
            }
            val ratio = if (dbSideAvgMs > 0) appSideAvgMs / dbSideAvgMs else Double.NaN
            println(
                "%-11d | %17.3f | %18.3f | %12.2fx"
                    .format(count, dbSideAvgMs, appSideAvgMs, ratio),
            )
        }
    }

    private suspend fun warmup(
        insertCount: Int,
        limit: Int,
    ) {
        repeat(2) {
            clearTable()
            repeat(insertCount) { i ->
                insert(i)
                dao.trim(limit)
            }
            clearTable()
            repeat(insertCount) { i ->
                insert(i)
                trimOnApplicationSide(limit)
            }
        }
    }

    private suspend fun insert(index: Int) {
        dao.insert(
            RecentProductEntity(
                productId = "p-$index",
                viewedAt = System.currentTimeMillis() + index,
            ),
        )
    }

    private suspend fun trimOnApplicationSide(limit: Int) {
        val keep = dao.findAll(limit).toHashSet()
        val all = findAllIds()
        val toDelete = all.filterNot { keep.contains(it) }
        toDelete.forEach { deleteById(it) }
    }

    private fun findAllIds(): List<String> {
        val result = mutableListOf<String>()
        db.openHelper.readableDatabase.query(
            "SELECT productId FROM recent_products",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0))
            }
        }
        return result
    }

    private fun deleteById(productId: String) {
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM recent_products WHERE productId = ?",
            arrayOf<Any>(productId),
        )
    }

    private fun clearTable() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM recent_products")
    }

    private inline fun averageMillis(
        iterations: Int,
        block: () -> Long,
    ): Double {
        var totalNanos = 0L
        repeat(iterations) { totalNanos += block() }
        return totalNanos / iterations / 1_000_000.0
    }
}
