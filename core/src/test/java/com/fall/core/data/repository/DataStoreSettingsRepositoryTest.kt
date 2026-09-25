package com.fall.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fall.core.model.llm.LlmSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DataStoreSettingsRepositoryTest {

    private fun newRepo(): DataStoreSettingsRepository {
        val tmp = createTempDir()
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tmp.resolve("settings.preferences_pb") }
        )
        return DataStoreSettingsRepository(dataStore)
    }

    @Test
    fun `默认值回退`() = runTest {
        val repo = newRepo()
        val s = repo.settings.first()
        assertEquals(LlmSource.LOCAL, s.llmSource)
        assertEquals(AppSettings.DEFAULT_LLM_MODEL, s.llmModel)
        assertEquals(AppSettings.DEFAULT_LLM_BASE_URL, s.llmBaseUrl)
        assertEquals(AppSettings.DEFAULT_DEBUG_LOGGING_ENABLED, s.debugLoggingEnabled)
    }

    @Test
    fun `保存后读取一致`() = runTest {
        val repo = newRepo()
        repo.save(
            AppSettings(
                llmSource = LlmSource.ONLINE,
                llmBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                llmApiKey = "sk-test",
                llmModel = "qwen-max",
                debugLoggingEnabled = false,
            )
        )
        val s = repo.settings.first()
        assertEquals(LlmSource.ONLINE, s.llmSource)
        assertEquals("sk-test", s.llmApiKey)
        assertEquals("qwen-max", s.llmModel)
        assertEquals(false, s.debugLoggingEnabled)
    }

    @Test
    fun `保存时规整尾斜杠与空白模型`() = runTest {
        val repo = newRepo()
        repo.save(AppSettings(llmBaseUrl = "http://x:8000/", llmModel = "  "))
        val s = repo.settings.first()
        assertEquals("http://x:8000", s.llmBaseUrl)
        // 不预填、也不"空值回落默认"：留空就保持为空，由用户补齐
        assertEquals("", s.llmModel)
    }

    @Test
    fun `默认不预填端点与模型`() {
        val s = AppSettings()
        assertEquals("", s.llmBaseUrl)
        assertEquals("", s.llmModel)
    }

    @Test
    fun `ApiKey 为空时 toLlmConfig 不带 key`() {
        val config = AppSettings(llmBaseUrl = "http://x:8000", llmModel = "m", llmApiKey = "").toLlmConfig()
        assertEquals(null, config.apiKey)
        assertEquals("http://x:8000", config.baseUrl)
    }
}