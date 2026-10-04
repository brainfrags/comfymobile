package sh.hnet.comfychair.storage

import android.content.Context

/**
 * Global app settings singleton.
 * Provides access to app-wide settings that need to be checked from multiple places.
 */
object AppSettings {
    private const val PREFS_NAME = "AppSettings"
    private const val KEY_MEDIA_CACHE_DISABLED = "media_cache_disabled"
    private const val KEY_MEMORY_FIRST_CACHE = "memory_first_cache"
    private const val KEY_LIVE_PREVIEW_ENABLED = "live_preview_enabled"
    private const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
    private const val KEY_AUTO_CONNECT = "autoConnect"
    private const val KEY_SHOW_BUILT_IN_WORKFLOWS = "show_built_in_workflows"
    private const val KEY_OFFLINE_MODE = "offline_mode"
    private const val KEY_EDGE_ROUTER = "edge_router"
    private const val DEFAULT_EDGE_ROUTER = "hermite"
    private const val KEY_PROMPT_SPELL_CHECK = "prompt_spell_check"

    /**
     * Check if live preview is enabled.
     * When enabled (default), intermediate preview images are shown during generation.
     * When disabled, only the final result is displayed.
     */
    fun isLivePreviewEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIVE_PREVIEW_ENABLED, true)  // Default: true (show live previews)
    }

    /**
     * Set whether live preview should be enabled.
     */
    fun setLivePreviewEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LIVE_PREVIEW_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if memory-first caching is enabled.
     * When enabled (default), media is kept in RAM and persisted to disk on app background.
     * When disabled, media is written directly to disk (disk-first mode for low-end devices).
     */
    fun isMemoryFirstCache(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEMORY_FIRST_CACHE, true)  // Default: true (memory-first)
    }

    /**
     * Set whether memory-first caching should be enabled.
     */
    fun setMemoryFirstCache(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MEMORY_FIRST_CACHE, enabled)
            .apply()
    }

    /**
     * Check if media cache is disabled.
     * When disabled, the app does not persist preview images and videos to disk.
     * Only applicable in memory-first mode.
     */
    fun isMediaCacheDisabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEDIA_CACHE_DISABLED, false)
    }

    /**
     * Set whether media cache should be disabled.
     */
    fun setMediaCacheDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MEDIA_CACHE_DISABLED, disabled)
            .apply()
    }

    /**
     * Check if debug logging is enabled.
     * When enabled, operational logs are captured in memory for troubleshooting.
     * Default is false (off).
     */
    fun isDebugLoggingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGGING_ENABLED, false)
    }

    /**
     * Set whether debug logging should be enabled.
     */
    fun setDebugLoggingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEBUG_LOGGING_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if auto-connect is enabled.
     * When enabled (default), the app auto-connects to the last selected server on startup.
     */
    fun isAutoConnectEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONNECT, true)  // Default: true (auto-connect)
    }

    /**
     * Set whether auto-connect should be enabled.
     */
    fun setAutoConnectEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONNECT, enabled)
            .apply()
    }

    /**
     * Check if built-in workflows should be shown.
     * When enabled (default), built-in workflows are displayed alongside user workflows.
     * When disabled, only user-created workflows are shown.
     */
    fun isShowBuiltInWorkflows(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_BUILT_IN_WORKFLOWS, false)  // Default: false (only your own workflows)
    }

    /**
     * Set whether built-in workflows should be shown.
     */
    fun setShowBuiltInWorkflows(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_BUILT_IN_WORKFLOWS, show)
            .apply()
    }

    /**
     * Check if offline mode is enabled.
     * When enabled, the app operates with cached data only and does not connect to the server.
     * Requires disk-first cache mode to be enabled for full functionality.
     * Default is false (online mode).
     */
    fun isOfflineMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OFFLINE_MODE, false)
    }

    /**
     * Set whether offline mode should be enabled.
     * Note: Should only be enabled when disk-first cache mode is active.
     */
    fun setOfflineMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OFFLINE_MODE, enabled)
            .apply()
    }

    /**
     * Get the selected edge router ID for the workflow editor.
     * Default is "hermite" (Hermite spline).
     */
    fun getEdgeRouterId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EDGE_ROUTER, DEFAULT_EDGE_ROUTER) ?: DEFAULT_EDGE_ROUTER
    }

    /**
     * Set the edge router ID for the workflow editor.
     */
    fun setEdgeRouterId(context: Context, routerId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EDGE_ROUTER, routerId)
            .apply()
    }

    /**
     * Check if spell check and autocorrect are enabled in prompt input fields.
     * Default is false — AI prompts contain many non-standard tokens that would be
     * flagged as errors, so the feature is opt-in.
     */
    fun isPromptSpellCheckEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPT_SPELL_CHECK, false)
    }

    fun setPromptSpellCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROMPT_SPELL_CHECK, enabled)
            .apply()
    }

    private const val KEY_PROMPT_EXPAND = "prompt_expand"

    fun isPromptExpandEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPT_EXPAND, false)
    }

    fun setPromptExpandEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROMPT_EXPAND, enabled)
            .apply()
    }

    private const val KEY_SAVE_TO_PHONE = "save_to_phone"

    /**
     * Whether gallery outputs are also saved to the phone's Photos
     * (Pictures/ComfyMobile, Movies/ComfyMobile). Default: false (only kept inside the app).
     */
    fun isSaveToPhoneEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAVE_TO_PHONE, false)
    }

    fun setSaveToPhoneEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SAVE_TO_PHONE, enabled)
            .apply()
    }

    private const val KEY_GALLERY_VIEW_MODE = "gallery_view_mode"

    /** Gallery view mode name (see GalleryViewMode). */
    fun getGalleryViewMode(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GALLERY_VIEW_MODE, null)
    }

    fun setGalleryViewMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GALLERY_VIEW_MODE, mode)
            .apply()
    }

    private const val KEY_GALLERY_SORT_ORDER = "gallery_sort_order"
    private const val KEY_SLIDESHOW_SECONDS = "slideshow_seconds"
    private const val KEY_CURRENT_ALBUM_PREFIX = "current_album_"

    /** Album selected for generation/gallery on a server, or null for none. */
    fun getCurrentAlbumId(context: Context, serverId: String): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_ALBUM_PREFIX + serverId, null)
    }

    fun setCurrentAlbumId(context: Context, serverId: String, albumId: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (albumId == null) remove(KEY_CURRENT_ALBUM_PREFIX + serverId)
                else putString(KEY_CURRENT_ALBUM_PREFIX + serverId, albumId)
            }
            .apply()
    }

    const val SLIDESHOW_SECONDS_DEFAULT = 4
    const val SLIDESHOW_SECONDS_MIN = 2
    const val SLIDESHOW_SECONDS_MAX = 20

    /** Seconds each picture stays on screen in the slideshow. */
    fun getSlideshowSeconds(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SLIDESHOW_SECONDS, SLIDESHOW_SECONDS_DEFAULT)
            .coerceIn(SLIDESHOW_SECONDS_MIN, SLIDESHOW_SECONDS_MAX)
    }

    fun setSlideshowSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SLIDESHOW_SECONDS, seconds.coerceIn(SLIDESHOW_SECONDS_MIN, SLIDESHOW_SECONDS_MAX))
            .apply()
    }

    /** Gallery sort order name (see GallerySortOrder). */
    fun getGallerySortOrder(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GALLERY_SORT_ORDER, null)
    }

    fun setGallerySortOrder(context: Context, order: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GALLERY_SORT_ORDER, order)
            .apply()
    }
}
