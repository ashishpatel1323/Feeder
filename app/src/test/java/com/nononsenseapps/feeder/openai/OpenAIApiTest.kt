package com.nononsenseapps.feeder.openai

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.core.RequestOptions
import com.aallam.openai.api.model.Model
import com.aallam.openai.api.model.ModelId
import com.nononsenseapps.feeder.archmodel.OpenAISettings
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAIClientMock(
    private val chatCompletion: ChatCompletion,
) : OpenAIClient {
    override suspend fun models(requestOptions: RequestOptions?): List<Model> = emptyList()

    override suspend fun chatCompletion(
        request: ChatCompletionRequest,
        requestOptions: RequestOptions?,
    ): ChatCompletion = chatCompletion
}

class OpenAIApiTest {
    @Test
    fun testSummaryResponseLangNoQuotes() =
        runTest {
            val chatCompletion = createResponse("Lang: eng\n\nMy summary")
            val api = createApi(response = chatCompletion)
            val actual = api.summarize("My content", OpenAISettings(key = "test", modelId = "test-model-id"))
            val expected = createResult("My summary", "eng")
            assertEquals(expected, actual)
        }

    @Test
    fun testSummaryResponseLangWithQuotes() =
        runTest {
            val chatCompletion = createResponse("Lang: \"FR\"\nMy summary")
            val api = createApi(response = chatCompletion)
            val actual = api.summarize("My content", OpenAISettings(key = "test", modelId = "test-model-id"))
            val expected = createResult("My summary", "FR")
            assertEquals(expected, actual)
        }

    @Test
    fun testSummaryResponseWithoutLangLineKeepsWholeSummary() =
        runTest {
            val chatCompletion = createResponse("First line of summary\nSecond line")
            val api = createApi(response = chatCompletion)
            val actual = api.summarize("My content", OpenAISettings(key = "test", modelId = "test-model-id"))
            val expected = createResult("First line of summary\nSecond line", "")
            assertEquals(expected, actual)
        }

    @Test
    fun onDeviceSummaryUsesOnDeviceModel() =
        runTest {
            val fake = FakeOnDeviceSummarizer(answer = "Lang: en\nShort summary")
            val api = OpenAIApi("lang", { error("No network client for on-device summaries") }, fake)
            val actual = api.summarize("Article text", OpenAISettings(baseUrl = ON_DEVICE_SUMMARY_PROVIDER_URL))
            assertIs<OpenAIApi.SummaryResult.Success>(actual)
            assertEquals("Short summary", actual.content)
            assertEquals("en", actual.detectedLanguage)
            assertEquals("Article text", fake.lastContent)
        }

    @Test
    fun onDeviceUnavailableFailsVerification() =
        runTest {
            val fake = FakeOnDeviceSummarizer(status = OnDeviceStatus.Unavailable("Not on this phone"))
            val api = OpenAIApi("lang", { error("No network client for on-device summaries") }, fake)
            val actual = api.listModelIds(OpenAISettings(baseUrl = ON_DEVICE_SUMMARY_PROVIDER_URL))
            assertEquals(OpenAIApi.ModelsResult.Error("Not on this phone"), actual)
        }

    @Test
    fun onDeviceSettingsAreValidWithoutKey() {
        val settings = OpenAISettings(baseUrl = ON_DEVICE_SUMMARY_PROVIDER_URL)
        assertTrue(settings.canSummarize)
        assertTrue(!settings.canTranslate)
    }

    @Test
    fun compatibleBaseUrlIsUsedAsTyped() {
        val host =
            OpenAISettings(key = "ollama", modelId = "llama3.2", baseUrl = "http://192.168.1.10:11434/v1")
                .toOpenAIHost(withAzureDeploymentId = false)
        assertEquals("http://192.168.1.10:11434/v1/", host.baseUrl)
    }

    @Test
    fun azureBaseUrlStillGetsOpenAIPath() {
        val host =
            OpenAISettings(
                key = "k",
                modelId = "m",
                baseUrl = "https://example.openai.azure.com",
                azureApiVersion = "2024-02-01",
                azureDeploymentId = "dep",
            ).toOpenAIHost(withAzureDeploymentId = true)
        assertTrue(host.baseUrl.endsWith("/openai/deployments/dep"), host.baseUrl)
    }

    @Test
    fun deepLVerificationUsesAuthorizationHeader() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .addHeader("Content-Type", "application/json")
                        .setBody("""{"translations":[{"detected_source_language":"EN","text":"Hallo"}]}"""),
                )

                val result =
                    createTranslationApi().listModelIds(
                        OpenAISettings(
                            baseUrl = server.url("/api.deepl.com").toString(),
                            key = "deep-key",
                        ),
                    )

                assertEquals(OpenAIApi.ModelsResult.Success(ids = emptyList()), result)

                val request = server.takeRequest()
                assertEquals("/api.deepl.com/v2/translate", request.path)
                assertEquals("DeepL-Auth-Key deep-key", request.getHeader("Authorization"))
                assertTrue(request.body.readUtf8().contains("\"target_lang\":\"DE\""))
            }
        }

    @Test
    fun deepLTranslateUsesHtmlTagHandling() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .addHeader("Content-Type", "application/json")
                        .setBody("""{"translations":[{"detected_source_language":"EN","text":"<p>Hallo</p>"}]}"""),
                )

                val result =
                    createTranslationApi().translate(
                        content = "<p>Hello</p>",
                        targetLanguage = "DE",
                        settings =
                            OpenAISettings(
                                baseUrl = server.url("/api.deepl.com").toString(),
                                key = "deep-key",
                            ),
                        preserveHtml = true,
                    )

                assertIs<OpenAIApi.TranslationResult.Success>(result)
                val request = server.takeRequest()
                assertEquals("DeepL-Auth-Key deep-key", request.getHeader("Authorization"))
                assertTrue(request.body.readUtf8().contains("\"tag_handling\":\"html\""))
            }
        }

    @Test
    fun deepLReturnsProviderSpecificHttpFailure() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setStatus("HTTP/1.1 403 Forbidden")
                        .setBody("""{"error":"denied"}"""),
                )

                val result =
                    createTranslationApi().translate(
                        content = "Hello",
                        targetLanguage = "DE",
                        settings =
                            OpenAISettings(
                                baseUrl = server.url("/api.deepl.com").toString(),
                                key = "deep-key",
                            ),
                    )

                val error = assertIs<OpenAIApi.TranslationResult.Error>(result)
                assertTrue(error.content.startsWith("DeepL request failed: HTTP 403"))
            }
        }

    @Test
    fun localTranslationCanBeVerifiedWithoutApiKey() =
        runTest {
            val result =
                createTranslationApi().listModelIds(
                    OpenAISettings(baseUrl = LOCAL_TRANSLATION_PROVIDER_URL),
                )

            assertEquals(OpenAIApi.ModelsResult.Success(ids = emptyList()), result)
        }

    @Test
    fun localTranslationIsUsableAsTranslationApiWithoutApiKey() {
        val settings = OpenAISettings(baseUrl = LOCAL_TRANSLATION_PROVIDER_URL)

        assertTrue(settings.canUseAsTranslationApi)
    }

    private fun createApi(response: ChatCompletion) = OpenAIApi("lang", openAIClientFactory = { OpenAIClientMock(response) })

    private fun createTranslationApi() = OpenAIApi("en", openAIClientFactory = { error("OpenAI client should not be used for translation-only providers") })

    private fun createResponse(message: String) =
        ChatCompletion(
            id = "test",
            model = ModelId(id = "test-model-id"),
            created = 0,
            choices =
                listOf(
                    ChatChoice(
                        index = 0,
                        message =
                            ChatMessage(
                                role = ChatRole.Assistant,
                                content = message,
                            ),
                        finishReason = null,
                        logprobs = null,
                    ),
                ),
            usage = null,
            systemFingerprint = null,
        )

    private fun createResult(
        content: String,
        lang: String,
    ) = OpenAIApi.SummaryResult.Success(
        id = "test",
        created = 0,
        model = "test-model-id",
        content = content,
        promptTokens = 0,
        completeTokens = 0,
        totalTokens = 0,
        detectedLanguage = lang,
    )
}

private class FakeOnDeviceSummarizer(
    private val status: OnDeviceStatus = OnDeviceStatus.Ready("Gemini Nano"),
    private val answer: String = "",
) : OnDeviceSummarizer {
    var lastContent: String? = null

    override suspend fun checkStatus(): OnDeviceStatus = status

    override suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        content: String,
    ): String {
        lastContent = content
        return answer
    }
}
