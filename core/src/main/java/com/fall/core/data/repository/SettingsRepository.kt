package com.fall.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fall.core.agent.PerceptionMode
import com.fall.core.model.llm.LlmSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "fall_settings")

/** 设置存储抽象（DataStore 实现便于 JVM 单测与后续替换/加密升级）。 */
interface SettingsRepository {
    /** 设置流（默认值兜底），首条即当前配置。 */
    val settings: Flow<AppSettings>

    /** 全量保存设置。 */
    suspend fun save(settings: AppSettings)
}

/** DataStore 实现（Preferences）。 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            llmSource = prefs[KEY_LLM_SOURCE]?.let { normalized ->
                runCatching { LlmSource.valueOf(normalized) }.getOrDefault(LlmSource.LOCAL)
            } ?: LlmSource.LOCAL,
            llmBaseUrl = prefs[KEY_LLM_BASE_URL] ?: AppSettings.DEFAULT_LLM_BASE_URL,
            llmApiKey = prefs[KEY_LLM_API_KEY] ?: "",
            llmModel = prefs[KEY_LLM_MODEL] ?: AppSettings.DEFAULT_LLM_MODEL,
            debugLoggingEnabled = prefs[KEY_DEBUG_LOGGING_ENABLED] ?: AppSettings.DEFAULT_DEBUG_LOGGING_ENABLED,
            rootModeEnabled = prefs[KEY_ROOT_MODE_ENABLED] ?: AppSettings.DEFAULT_ROOT_MODE_ENABLED,
            perceptionMode = prefs[KEY_PERCEPTION_MODE]?.let { name ->
                runCatching { PerceptionMode.valueOf(name) }.getOrDefault(AppSettings.DEFAULT_PERCEPTION_MODE)
            } ?: AppSettings.DEFAULT_PERCEPTION_MODE,
            parallelVdEnabled = prefs[KEY_PARALLEL_VD_ENABLED] ?: AppSettings.DEFAULT_PARALLEL_VD_ENABLED,
            previewQuality = prefs[KEY_PREVIEW_QUALITY]?.let { name ->
                runCatching { PreviewQuality.valueOf(name) }.getOrDefault(AppSettings.DEFAULT_PREVIEW_QUALITY)
            } ?: AppSettings.DEFAULT_PREVIEW_QUALITY,
        )
    }

    override suspend fun save(settings: AppSettings) {
        val s = settings.normalized() // 保存前统一规整（存储层只保存规整后的值）
        dataStore.edit { prefs ->
            prefs[KEY_LLM_SOURCE] = s.llmSource.name
            prefs[KEY_LLM_BASE_URL] = s.llmBaseUrl
            prefs[KEY_LLM_API_KEY] = s.llmApiKey
            prefs[KEY_LLM_MODEL] = s.llmModel
            prefs[KEY_DEBUG_LOGGING_ENABLED] = s.debugLoggingEnabled
            prefs[KEY_ROOT_MODE_ENABLED] = s.rootModeEnabled
            prefs[KEY_PERCEPTION_MODE] = s.perceptionMode.name
            prefs[KEY_PARALLEL_VD_ENABLED] = s.parallelVdEnabled
            prefs[KEY_PREVIEW_QUALITY] = s.previewQuality.name
        }
    }

    private companion object {
        val KEY_LLM_SOURCE = stringPreferencesKey("llm_source")
        val KEY_LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val KEY_LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val KEY_LLM_MODEL = stringPreferencesKey("llm_model")
        val KEY_DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val KEY_ROOT_MODE_ENABLED = booleanPreferencesKey("root_mode_enabled")
        val KEY_PERCEPTION_MODE = stringPreferencesKey("perception_mode")
        val KEY_PARALLEL_VD_ENABLED = booleanPreferencesKey("parallel_vd_enabled")
        val KEY_PREVIEW_QUALITY = stringPreferencesKey("preview_quality")
    }
}