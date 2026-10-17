package tv.own.owntv.core.epg

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A standalone XMLTV EPG feed (not tied to a playlist). Guide data merges into the shared tables. */
data class EpgSource(
    val id: Long,            // synthetic, always negative so it never collides with a Room source id
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
)

private val Context.epgStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_epg_sources")

/**
 * Stores the user's EPG (XMLTV) sources as a JSON list in DataStore — NOT a Room table, because the
 * database does a destructive migration on schema changes (which would wipe all synced content). The
 * parsed guide still lands in the existing epg_channels / epg_programmes tables, keyed by each EPG
 * source's negative [EpgSource.id]; the guide query simply includes those ids alongside the playlist
 * ids, so channels match guide entries from any feed by their EPG id.
 */
class EpgSourceStore(private val context: Context) {

    private object Keys {
        val LIST = stringPreferencesKey("sources")
        val NEXT_ID = longPreferencesKey("next_id") // counts down: -1, -2, …
        val MIGRATED = longPreferencesKey("migrated_v1")
    }

    val sources: Flow<List<EpgSource>> = context.epgStore.data.map { prefs ->
        EpgSourceJson.parse(prefs[Keys.LIST])
    }

    suspend fun getAll(): List<EpgSource> = EpgSourceJson.parse(context.epgStore.data.first()[Keys.LIST])

    suspend fun add(name: String, url: String, userAgent: String? = null): EpgSource {
        var created: EpgSource? = null
        context.epgStore.edit { prefs ->
            val id = (prefs[Keys.NEXT_ID] ?: -1L)
            prefs[Keys.NEXT_ID] = id - 1
            val source = EpgSource(id = id, name = name.trim().ifBlank { "EPG" }, url = url.trim(), userAgent = userAgent?.trim()?.takeIf { it.isNotBlank() })
            created = source
            prefs[Keys.LIST] = EpgSourceJson.write(EpgSourceJson.parse(prefs[Keys.LIST]) + source)
        }
        return created!!
    }

    /**
     * Make sure [url] exists as an EPG source, adding it if it does not. Returns the existing or new
     * entry, and **never syncs anything** — used to put a Stalker portal's own guide on the Settings →
     * EPG list after the portal's catalog syncs, so the user can press Sync on it like any other feed.
     * Registering it is not downloading it, so EPG stays as opt-in as it has been since v2.2.0.
     */
    suspend fun ensure(name: String, url: String, userAgent: String? = null): EpgSource =
        getAll().firstOrNull { it.url == url } ?: add(name, url, userAgent)

    suspend fun update(source: EpgSource) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = EpgSourceJson.write(EpgSourceJson.parse(prefs[Keys.LIST]).map { if (it.id == source.id) source else it })
        }
    }

    suspend fun remove(id: Long) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = EpgSourceJson.write(EpgSourceJson.parse(prefs[Keys.LIST]).filterNot { it.id == id })
        }
    }

    /** Stamp a source after a sync attempt (success → error null; failure → error message). */
    suspend fun setSynced(id: Long, at: Long, error: String?) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = EpgSourceJson.write(EpgSourceJson.parse(prefs[Keys.LIST]).map { if (it.id == id) it.copy(lastSyncAt = at, lastError = error) else it })
        }
    }

    /**
     * Record a sync failure WITHOUT touching [EpgSource.lastSyncAt]. The staleness-based auto-refresh logic
     * treats `lastSyncAt` as the *last successful* sync, so a failed attempt must leave it unchanged —
     * otherwise a flaky network would falsely reset the threshold and stop retries. Only [EpgSource.lastError]
     * is updated here. Successes still go through [setSynced].
     */
    suspend fun markError(id: Long, message: String?) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = EpgSourceJson.write(EpgSourceJson.parse(prefs[Keys.LIST]).map { if (it.id == id) it.copy(lastError = message) else it })
        }
    }

    /** Has the one-time "playlist EPG → EPG source" migration already run? */
    suspend fun isMigrated(): Boolean = context.epgStore.data.first()[Keys.MIGRATED] == 1L
    suspend fun markMigrated() { context.epgStore.edit { it[Keys.MIGRATED] = 1L } }

    // --- backup / restore (the raw JSON list rides inside the sources section) ---

    suspend fun exportJson(): String = context.epgStore.data.first()[Keys.LIST] ?: "[]"

    suspend fun importJson(raw: String?) {
        context.epgStore.edit { prefs ->
            val list = EpgSourceJson.parse(raw)
            if (list.isEmpty()) prefs.remove(Keys.LIST) else prefs[Keys.LIST] = EpgSourceJson.write(list)
            // Keep the id counter below the lowest restored id so new sources never collide.
            prefs[Keys.NEXT_ID] = (list.minOfOrNull { it.id } ?: 0L).coerceAtMost(0L) - 1
        }
    }

    /**
     * Merge-restore (backup): adds the backup's EPG sources WITHOUT touching existing ones — an
     * incoming source whose URL already exists maps to the existing entry. Returns the id mapping
     * (backup id → device id) so the caller can remap the per-source auto-refresh selections.
     */
    suspend fun mergeJson(raw: String?): Map<Long, Long> {
        val incoming = EpgSourceJson.parse(raw)
        if (incoming.isEmpty()) return emptyMap()
        var idMap: Map<Long, Long> = emptyMap()
        context.epgStore.edit { prefs ->
            val merged = mergeEpgSources(EpgSourceJson.parse(prefs[Keys.LIST]), incoming, prefs[Keys.NEXT_ID] ?: -1L)
            prefs[Keys.LIST] = EpgSourceJson.write(merged.sources)
            prefs[Keys.NEXT_ID] = merged.nextId
            idMap = merged.idMap
        }
        return idMap
    }
}
