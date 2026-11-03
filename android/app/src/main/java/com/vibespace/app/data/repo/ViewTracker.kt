package com.vibespace.app.data.repo

import com.vibespace.app.data.api.ViewResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Records post views the way the feed sees them:
 *  - a post counts as seen once it has been at least 50% on screen for [dwellMillis] (1 s),
 *  - seen ids are batched and sent every [flushIntervalMillis] (5 s), at most [MAX_BATCH] per request,
 *    or right away on [flushNow] (the screen pauses),
 *  - opening a post's detail screen records a view immediately ([recordOpen]).
 * Each post is sent at most once per app session from the feed; the server keeps the real rule
 * (one view per viewer per 24 hours, never the author's own).
 *
 * Screens report what is visible with [setVisible]; several lists can report at once (one [source] each).
 */
class ViewTracker(
    private val scope: CoroutineScope,
    private val sendBatch: suspend (List<String>) -> Result<List<ViewResult>>,
    private val sendOne: suspend (String) -> Result<ViewResult>,
    private val dwellMillis: Long = 1_000,
    private val flushIntervalMillis: Long = 5_000,
) {
    private val lock = Any()
    private val visibleBySource = mutableMapOf<String, Set<String>>()
    private val dwellJobs = mutableMapOf<String, Job>()
    private val recorded = LinkedHashSet<String>()
    private val pending = LinkedHashSet<String>()
    private var flushJob: Job? = null
    private val sending = Mutex()

    private val _counts = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Latest view counts the server returned, by post id (to refresh the numbers on screen). */
    val counts: StateFlow<Map<String, Long>> = _counts.asStateFlow()

    /** Ids from [source] that are now at least half visible (empty when the screen is paused or gone). */
    fun setVisible(source: String, ids: Set<String>) = synchronized(lock) {
        if (ids.isEmpty()) visibleBySource.remove(source) else visibleBySource[source] = ids
        val visible = visibleNow()
        (dwellJobs.keys - visible).forEach { dwellJobs.remove(it)?.cancel() }
        for (id in visible) {
            if (id in recorded || id in dwellJobs) continue
            dwellJobs[id] = scope.launch {
                delay(dwellMillis)
                onDwellReached(id)
            }
        }
    }

    private fun visibleNow(): Set<String> = visibleBySource.values.flatten().toSet()

    private fun onDwellReached(id: String) = synchronized(lock) {
        dwellJobs.remove(id)
        if (id !in visibleNow() || id in recorded) return@synchronized
        remember(id)
        pending += id
        if (flushJob?.isActive != true) {
            flushJob = scope.launch {
                delay(flushIntervalMillis)
                flush()
            }
        }
    }

    private fun remember(id: String) {
        recorded += id
        if (recorded.size > MAX_REMEMBERED) recorded.remove(recorded.first())
    }

    /** Send whatever is queued now (call when the screen pauses / the app goes to the background). */
    fun flushNow() {
        synchronized(lock) { flushJob?.cancel(); flushJob = null }
        scope.launch { flush() }
    }

    /** Ids queued but not sent yet (for tests and debugging). */
    fun pendingIds(): List<String> = synchronized(lock) { pending.toList() }

    private suspend fun flush() = sending.withLock {
        while (true) {
            val batch = synchronized(lock) {
                pending.take(MAX_BATCH).also { pending.removeAll(it.toSet()) }
            }
            if (batch.isEmpty()) return@withLock
            val result = sendBatch(batch)
            result.onSuccess { publish(it) }
            if (result.isFailure) {
                // Offline or server trouble: keep them for the next flush (bounded), don't retry in a loop.
                synchronized(lock) {
                    val keep = (batch + pending).take(MAX_PENDING)
                    pending.clear()
                    pending.addAll(keep)
                }
                return@withLock
            }
        }
    }

    /** The post detail screen opened: record a view now. Returns the server's answer (count + whether it counted). */
    suspend fun recordOpen(id: String): Result<ViewResult> {
        synchronized(lock) {
            dwellJobs.remove(id)?.cancel()
            pending.remove(id)
            remember(id)
        }
        return sendOne(id).onSuccess { publish(listOf(it)) }
    }

    private fun publish(results: List<ViewResult>) {
        if (results.isEmpty()) return
        _counts.update { cur ->
            val next = cur + results.associate { it.postId to it.viewCount }
            if (next.size > MAX_REMEMBERED) next.entries.drop(next.size - MAX_REMEMBERED).associate { it.key to it.value } else next
        }
    }

    companion object {
        /** The server accepts at most 50 ids per batch. */
        const val MAX_BATCH = 50
        private const val MAX_PENDING = 200
        private const val MAX_REMEMBERED = 5_000
    }
}

/** Pure visibility math for list items (offsets in px, as LazyListLayoutInfo reports them). */
object ViewVisibility {
    const val THRESHOLD = 0.5f

    /** Fraction (0..1) of an item that lies inside the viewport. */
    fun fraction(itemOffset: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int): Float {
        if (itemSize <= 0) return 0f
        val shown = minOf(itemOffset + itemSize, viewportEnd) - maxOf(itemOffset, viewportStart)
        return (shown.coerceAtLeast(0).toFloat() / itemSize).coerceIn(0f, 1f)
    }

    data class Item(val key: Any, val offset: Int, val size: Int)

    /** Keys (post ids) of items at least [threshold] visible. Non-post rows are skipped via [isPost]. */
    fun visibleIds(items: List<Item>, viewportStart: Int, viewportEnd: Int, isPost: (Any) -> Boolean, threshold: Float = THRESHOLD): Set<String> =
        items.asSequence()
            .filter { isPost(it.key) && fraction(it.offset, it.size, viewportStart, viewportEnd) >= threshold }
            .map { it.key.toString() }
            .toSet()
}
