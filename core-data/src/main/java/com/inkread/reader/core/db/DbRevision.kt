package com.inkread.reader.core.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 进程内的数据版本号。
 *
 * 每次写库后 [bump] 一次；所有 `observe*` 都基于它的 [version] 重新查询，
 * 从而让 Compose 列表在同步 / 标记已读 / 收藏后自动刷新（不需要 ContentObserver）。
 */
internal class DbRevision {

    private val _version = MutableStateFlow(0L)

    val version: StateFlow<Long> = _version.asStateFlow()

    fun bump() {
        _version.update { it + 1L }
    }
}
