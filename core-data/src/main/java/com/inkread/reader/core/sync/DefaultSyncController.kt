package com.inkread.reader.core.sync

import com.inkread.reader.core.db.FeedDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [SyncController] 的默认实现。
 *
 * - 串行同步（`Mutex` + 逐个 feed），避免墨水屏后台并发网络耗电；
 * - 单个源失败不影响其他源，失败原因写入 `feeds.error_message`；
 * - 同步结束按设置清理过期已读文章。
 */
internal class DefaultSyncController(
    private val feedDao: FeedDao,
    private val syncer: FeedSyncer,
) : SyncController {

    private val mutex = Mutex()

    private val _syncing = MutableStateFlow(false)
    override val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastResult = MutableStateFlow<SyncResult?>(null)
    override val lastResult: StateFlow<SyncResult?> = _lastResult.asStateFlow()

    override suspend fun syncAll(): SyncResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            _syncing.value = true
            try {
                val feeds = feedDao.listFeeds().filter { it.enabled }
                if (feeds.isEmpty()) {
                    val empty = SyncResult(feedsTried = 0)
                    _lastResult.value = empty
                    return@withLock empty
                }

                var failed = 0
                var added = 0
                var firstError: String? = null
                for (feed in feeds) {
                    // 单个源出现任何意外错误都不能中断其他源的同步
                    val outcome = try {
                        syncer.syncFeed(feed)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FeedSyncOutcome(error = "同步失败：${e.message ?: e.javaClass.simpleName}")
                    }
                    if (outcome.error != null) {
                        failed++
                        if (firstError == null) firstError = outcome.error
                    } else {
                        added += outcome.newArticles
                    }
                }
                syncer.pruneExpiredArticles()

                val result = SyncResult(
                    feedsTried = feeds.size,
                    feedsFailed = failed,
                    newArticles = added,
                    // 全部源都失败时，用第一个失败原因作为整体错误提示
                    error = if (failed == feeds.size) firstError ?: "全部订阅源同步失败" else null,
                )
                _lastResult.value = result
                result
            } finally {
                _syncing.value = false
            }
        }
    }

    override suspend fun syncFeed(feedId: Long): SyncResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            _syncing.value = true
            try {
                val feed = feedDao.getFeed(feedId)
                if (feed == null) {
                    val missing = SyncResult(feedsTried = 1, feedsFailed = 1, error = "订阅源不存在")
                    _lastResult.value = missing
                    return@withLock missing
                }
                val outcome = try {
                    syncer.syncFeed(feed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FeedSyncOutcome(error = "同步失败：${e.message ?: e.javaClass.simpleName}")
                }
                val result = if (outcome.error != null) {
                    SyncResult(feedsTried = 1, feedsFailed = 1, error = outcome.error)
                } else {
                    SyncResult(feedsTried = 1, newArticles = outcome.newArticles)
                }
                _lastResult.value = result
                result
            } finally {
                _syncing.value = false
            }
        }
    }
}
