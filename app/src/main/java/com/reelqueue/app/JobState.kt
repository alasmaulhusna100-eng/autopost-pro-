package com.reelqueue.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class Status { PENDING, UPLOADING, SCHEDULED, FAILED }

data class Item(
    val index: Int,
    val videoUri: String,
    val videoName: String,
    val title: String,
    val description: String,
    val publishAtSec: Long,
    val status: Status = Status.PENDING,
    val message: String = "",
    val progress: Int = 0
)

/** Shared between the UI and the upload service (same process). */
object JobState {
    val items = MutableStateFlow<List<Item>>(emptyList())
    val running = MutableStateFlow(false)
    val intervalSec = MutableStateFlow(3 * 3600L)

    fun update(idx: Int, f: (Item) -> Item) {
        items.update { list -> list.mapIndexed { i, it -> if (i == idx) f(it) else it } }
    }

    fun mapAll(f: (Item) -> Item) {
        items.update { list -> list.map(f) }
    }
}
