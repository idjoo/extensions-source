package eu.kanade.tachiyomi.animeextension.en.dramatap

/** Persists the serialized cap state. Backed by SharedPreferences in the app and a field in tests. */
interface CapStore {
    fun load(): String?
    fun save(value: String)
}

/**
 * Mirrors DramaTap's free-tier daily limit (`CAP_EPS` in app.js): after [limit] distinct Episodes,
 * new Episodes stay locked for [lockMillis]; Episodes already counted can still be rewatched.
 * Without a lock the counter resets when the local day changes.
 */
class DailyEpisodeCap(
    private val store: CapStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> String = ::localDay,
    private val limit: Int = DEFAULT_LIMIT,
    private val lockMillis: Long = DEFAULT_LOCK_MILLIS,
) {
    private data class State(val day: String, val until: Long, val episodes: List<String>)

    private fun load(): State {
        val fresh = State(today(), 0L, emptyList())
        val parts = store.load()?.split('|') ?: return fresh
        if (parts.size != 3) return fresh
        val state = State(parts[0], parts[1].toLongOrNull() ?: 0L, parts[2].split(',').filter(String::isNotEmpty))
        val expired = if (state.until > 0) now() >= state.until else state.day != today()
        return if (expired) fresh else state
    }

    private fun save(s: State) = store.save("${s.day}|${s.until}|${s.episodes.joinToString(",")}")

    /** Remaining lock time in milliseconds, or 0 when not locked. */
    @Synchronized
    fun lockedForMillis(): Long = load().until.let { if (it > 0) maxOf(0L, it - now()) else 0L }

    /** Counts an Episode play. Returns false when the daily limit blocks it. */
    @Synchronized
    fun tryConsume(seriesId: Long, episode: Int): Boolean {
        val key = "$seriesId:$episode"
        val state = load()
        if (key in state.episodes) return true
        if (state.until > 0) return false
        val episodes = state.episodes + key
        val until = if (episodes.size >= limit) now() + lockMillis else 0L
        save(state.copy(episodes = episodes, until = until))
        return true
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val DEFAULT_LOCK_MILLIS = 24L * 3600 * 1000
    }
}

private fun localDay(): String {
    val c = java.util.Calendar.getInstance()
    return "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.MONTH) + 1}-${c.get(java.util.Calendar.DAY_OF_MONTH)}"
}
