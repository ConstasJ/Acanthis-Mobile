package io.acanthis.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcanthisApiClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun createClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): AcanthisApiClient {
        val mockEngine = MockEngine(handler)
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return AcanthisApiClient(httpClient = httpClient, json = json)
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        extraHeaders: Map<String, List<String>> = emptyMap(),
    ): HttpResponseData {
        val pairs = buildList {
            add(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            extraHeaders.forEach { (key, values) -> add(key to values) }
        }.toTypedArray()
        return respond(
            content = body,
            status = status,
            headers = headersOf(*pairs),
        )
    }

    private fun HttpRequestData.bodyText(): String {
        val content = body as? OutgoingContent.ByteArrayContent
            ?: error("Expected ByteArrayContent, got ${body::class}")
        return content.bytes().decodeToString()
    }

    @Test
    fun fetchExploreSchemaAndHomeSendBearerAndQueryParams() = runTest {
        val recordedRequests = mutableListOf<HttpRequestData>()
        val client = createClient { request ->
            recordedRequests += request
            when (request.url.encodedPath) {
                "/v1/linovelib/explore/schema" -> respondJson(
                    """
                    {
                      "code": 0,
                      "message": "Success",
                      "data": {
                        "platform": "linovelib",
                        "supportsHome": true,
                        "channels": [
                          { "id": "home", "label": "首页" },
                          { "id": "jp", "label": "日轻", "requiresChallenge": true }
                        ],
                        "rankings": [
                          { "id": "monthvisit", "label": "月点击榜", "supportsAll": true, "supportsFull": true }
                        ],
                        "catalogRequiresChallenge": true,
                        "catalogFilters": [
                          {
                            "key": "sort",
                            "label": "排序",
                            "defaultValue": "lastupdate",
                            "options": [
                              { "value": "lastupdate", "label": "最近更新" }
                            ]
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )

                "/v1/linovelib/home" -> respondJson(
                    """
                    {
                      "code": 0,
                      "message": "Success",
                      "data": {
                        "platform": "linovelib",
                        "channel": "jp",
                        "title": "日轻频道",
                        "sections": [
                          {
                            "id": "hot",
                            "title": "热门推荐",
                            "layout": "grid",
                            "items": [
                              {
                                "id": "1001",
                                "platform": "linovelib",
                                "title": "测试轻小说",
                                "coverUrl": "/v1/linovelib/cover/novel/1001",
                                "author": "测试作者",
                                "status": "ongoing",
                                "genres": ["奇幻", "冒险"],
                                "rank": 1
                              }
                            ],
                            "more": {
                              "type": "ranking",
                              "params": { "rank": "monthvisit", "full": "true" }
                            }
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )

                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        val schema = client.fetchExploreSchema(
            baseUrl = "https://api.acanthis.test/v1/",
            authToken = "secret",
            platform = "linovelib",
        )
        val home = client.fetchExploreHome(
            baseUrl = "https://api.acanthis.test",
            authToken = "secret",
            platform = "linovelib",
            channel = "jp",
        )

        assertEquals(2, recordedRequests.size)
        assertEquals("/v1/linovelib/explore/schema", recordedRequests[0].url.encodedPath)
        assertEquals("Bearer secret", recordedRequests[0].headers[HttpHeaders.Authorization])

        assertEquals("/v1/linovelib/home", recordedRequests[1].url.encodedPath)
        assertEquals("jp", recordedRequests[1].url.parameters["channel"])
        assertEquals("Bearer secret", recordedRequests[1].headers[HttpHeaders.Authorization])

        assertEquals("linovelib", schema.platform)
        assertTrue(schema.supportsHome)
        assertTrue(schema.catalogRequiresChallenge)
        assertEquals(2, schema.channels.size)
        assertFalse(schema.channels[0].requiresChallenge)
        assertTrue(schema.channels[1].requiresChallenge)
        assertEquals("monthvisit", schema.rankings.single().id)
        assertEquals("sort", schema.catalogFilters.single().key)

        assertEquals("jp", home.channel)
        assertEquals("日轻频道", home.title)
        val section = home.sections.single()
        assertEquals("grid", section.layout)
        assertEquals("ranking", section.more?.type)
        assertEquals("monthvisit", section.more?.params?.get("rank"))
        assertEquals("1001", section.items.single().id)
        assertEquals(listOf("奇幻", "冒险"), section.items.single().genres)
        assertEquals(1, section.items.single().rank)
    }

    @Test
    fun fetchRankingsCatalogAndSearchEncodeParameters() = runTest {
        val recordedRequests = mutableListOf<HttpRequestData>()
        val client = createClient { request ->
            recordedRequests += request
            when (request.url.encodedPath) {
                "/v1/linovelib/rankings" -> respondJson(
                    """
                    {
                      "code": 0,
                      "message": "Success",
                      "data": {
                        "platform": "linovelib",
                        "items": [
                          {
                            "id": "2001",
                            "platform": "linovelib",
                            "title": "榜单第一",
                            "rank": 1
                          }
                        ],
                        "page": 2,
                        "totalPages": 10,
                        "hasNextPage": true
                      }
                    }
                    """.trimIndent(),
                )

                "/v1/linovelib/catalog" -> respondJson(
                    """
                    {
                      "code": 0,
                      "message": "Success",
                      "data": {
                        "platform": "linovelib",
                        "items": [
                          {
                            "id": "3001",
                            "platform": "linovelib",
                            "title": "分类作品"
                          }
                        ],
                        "page": 1,
                        "hasNextPage": false
                      }
                    }
                    """.trimIndent(),
                )

                "/v1/linovelib/search" -> respondJson(
                    """
                    {
                      "code": 0,
                      "message": "Success",
                      "data": [
                        {
                          "id": "4001",
                          "platform": "linovelib",
                          "title": "无职转生",
                          "coverUrl": "/v1/linovelib/cover/novel/4001"
                        }
                      ]
                    }
                    """.trimIndent(),
                )

                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        val rankings = client.fetchRankings(
            baseUrl = "https://api.acanthis.test",
            authToken = null,
            platform = "linovelib",
            rank = "monthvisit",
            full = true,
            page = 2,
        )
        val catalog = client.fetchCatalog(
            baseUrl = "https://api.acanthis.test",
            authToken = "token-1",
            platform = "linovelib",
            filters = mapOf(
                "sort" to "lastupdate",
                "region" to "1",
                "tag" to "奇幻",
                "empty" to "   ",
                "page" to "99",
            ),
            page = 1,
        )
        val searchResults = client.searchNovels(
            baseUrl = "https://api.acanthis.test",
            authToken = "token-1",
            platform = "linovelib",
            keyword = "无职转生 & 异世界",
        )

        assertEquals(3, recordedRequests.size)

        val rankingReq = recordedRequests[0]
        assertEquals("/v1/linovelib/rankings", rankingReq.url.encodedPath)
        assertEquals("monthvisit", rankingReq.url.parameters["rank"])
        assertEquals("true", rankingReq.url.parameters["full"])
        assertEquals("2", rankingReq.url.parameters["page"])
        assertNull(rankingReq.headers[HttpHeaders.Authorization])

        val catalogReq = recordedRequests[1]
        assertEquals("/v1/linovelib/catalog", catalogReq.url.encodedPath)
        assertEquals("lastupdate", catalogReq.url.parameters["sort"])
        assertEquals("1", catalogReq.url.parameters["region"])
        assertEquals("奇幻", catalogReq.url.parameters["tag"])
        assertNull(catalogReq.url.parameters["empty"])
        assertEquals("1", catalogReq.url.parameters["page"])
        assertTrue(catalogReq.url.encodedQuery.contains("%E5%A5%87%E5%B9%BB"))

        val searchReq = recordedRequests[2]
        assertEquals("/v1/linovelib/search", searchReq.url.encodedPath)
        assertEquals("无职转生 & 异世界", searchReq.url.parameters["keyword"])
        assertTrue(searchReq.url.encodedQuery.contains("%E6%97%A0%E8%81%8C"))
        assertTrue(searchReq.url.encodedQuery.contains("%26"))

        assertEquals(2, rankings.page)
        assertEquals(10, rankings.totalPages)
        assertTrue(rankings.hasNextPage)
        assertEquals("2001", rankings.items.single().id)

        assertEquals(1, catalog.page)
        assertNull(catalog.totalPages)
        assertFalse(catalog.hasNextPage)
        assertEquals("3001", catalog.items.single().id)

        assertEquals(1, searchResults.size)
        assertEquals("4001", searchResults.single().id)
        assertEquals("/v1/linovelib/cover/novel/4001", searchResults.single().coverUrl)
    }

    @Test
    fun fetchNovelAndChapterPassOptionalNovelId() = runTest {
        val recordedRequests = mutableListOf<HttpRequestData>()
        val client = createClient { request ->
            recordedRequests += request
            when (request.url.encodedPath) {
                "/v1/linovelib/novel/123" -> respondJson(
                    """
                    {
                      "code": 0,
                      "message": "Success",
                      "data": {
                        "id": "123",
                        "platform": "linovelib",
                        "title": "测试轻小说",
                        "coverUrl": "/v1/linovelib/cover/novel/123",
                        "author": "作者甲",
                        "summary": "简介内容",
                        "status": "ongoing",
                        "genres": ["校园", "恋爱"],
                        "volumes": [
                          {
                            "id": "v1",
                            "title": "第一卷",
                            "coverUrl": "/v1/linovelib/cover/volume/v1",
                            "chapters": [
                              {
                                "id": "456",
                                "title": "第一章",
                                "novelId": "123",
                                "volumeId": "v1"
                              }
                            ]
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )

                "/v1/linovelib/chapter/456" -> respondJson(
                    """
                    {
                      "code": 0,
                      "message": "Success",
                      "data": {
                        "content": "<p>正文第一段</p>"
                      }
                    }
                    """.trimIndent(),
                )

                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        val novel = client.fetchNovelDetail(
            baseUrl = "https://api.acanthis.test",
            authToken = "secret",
            platform = "linovelib",
            novelId = "123",
        )
        val contentWithNovelId = client.fetchChapterContent(
            baseUrl = "https://api.acanthis.test",
            authToken = "secret",
            platform = "linovelib",
            chapterId = "456",
            novelId = "123",
        )
        val contentWithoutNovelId = client.fetchChapterContent(
            baseUrl = "https://api.acanthis.test",
            authToken = "secret",
            platform = "linovelib",
            chapterId = "456",
            novelId = null,
        )

        assertEquals(3, recordedRequests.size)
        assertEquals("/v1/linovelib/novel/123", recordedRequests[0].url.encodedPath)
        assertEquals("/v1/linovelib/chapter/456", recordedRequests[1].url.encodedPath)
        assertEquals("123", recordedRequests[1].url.parameters["novelId"])
        assertEquals("/v1/linovelib/chapter/456", recordedRequests[2].url.encodedPath)
        assertNull(recordedRequests[2].url.parameters["novelId"])

        assertEquals("123", novel.id)
        assertEquals("测试轻小说", novel.title)
        assertEquals("作者甲", novel.author)
        assertEquals("ongoing", novel.status)
        assertEquals(1, novel.volumes.size)
        assertEquals("456", novel.volumes.single().chapters.single().id)

        assertEquals("<p>正文第一段</p>", contentWithNovelId)
        assertEquals("<p>正文第一段</p>", contentWithoutNovelId)
    }

    @Test
    fun syncPullAndPushHandleSinceParamAndJsonBody() = runTest {
        val recordedRequests = mutableListOf<HttpRequestData>()
        val client = createClient { request ->
            recordedRequests += request
            when (request.method) {
                HttpMethod.Get -> respondJson(
                    """
                    {
                      "code": 0,
                      "message": "Success",
                      "data": {
                        "bookshelf": [
                          {
                            "platform": "linovelib",
                            "novelId": "123",
                            "title": "书架作品",
                            "author": "作者",
                            "coverUrl": "/v1/linovelib/cover/novel/123",
                            "addedAt": 100,
                            "updatedAt": 200,
                            "deletedAt": null
                          }
                        ],
                        "progress": [
                          {
                            "platform": "linovelib",
                            "novelId": "123",
                            "novelTitle": "书架作品",
                            "coverUrl": "/v1/linovelib/cover/novel/123",
                            "volumeId": "v1",
                            "volumeTitle": "第一卷",
                            "chapterId": "c1",
                            "chapterTitle": "第一章",
                            "paragraphIndex": 5,
                            "totalParagraphs": 20,
                            "deviceLabel": "Pixel",
                            "updatedAt": 300
                          }
                        ],
                        "cursor": 1500
                      }
                    }
                    """.trimIndent(),
                )

                HttpMethod.Post -> respondJson(
                    """
                    {
                      "code": 0,
                      "message": "Success",
                      "data": {
                        "bookshelf": [
                          {
                            "platform": "linovelib",
                            "novelId": "123",
                            "title": "书架作品",
                            "author": "",
                            "coverUrl": "/v1/linovelib/cover/novel/123",
                            "addedAt": 100,
                            "updatedAt": 250,
                            "deletedAt": null
                          }
                        ],
                        "progress": [],
                        "serverTime": 2000
                      }
                    }
                    """.trimIndent(),
                )

                else -> error("Unexpected method: ${request.method}")
            }
        }

        val pullNull = client.pullSyncState(
            baseUrl = "https://api.acanthis.test",
            authToken = "sync-token",
            since = null,
        )
        val pullZero = client.pullSyncState(
            baseUrl = "https://api.acanthis.test",
            authToken = "sync-token",
            since = 0L,
        )
        val pullSince = client.pullSyncState(
            baseUrl = "https://api.acanthis.test",
            authToken = "sync-token",
            since = 1000L,
        )
        val pushResponse = client.pushSyncState(
            baseUrl = "https://api.acanthis.test",
            authToken = "sync-token",
            payload = SyncStatePushPayloadDto(
                bookshelf = listOf(
                    BookshelfItemDto(
                        platform = "linovelib",
                        novelId = "123",
                        title = "书架作品",
                        author = "",
                        coverUrl = "/v1/linovelib/cover/novel/123",
                        addedAt = 100L,
                        updatedAt = 250L,
                        deletedAt = null,
                    ),
                ),
            ),
        )

        assertEquals(4, recordedRequests.size)
        assertNull(recordedRequests[0].url.parameters["since"])
        assertNull(recordedRequests[1].url.parameters["since"])
        assertEquals("1000", recordedRequests[2].url.parameters["since"])

        val postRequest = recordedRequests[3]
        assertEquals(HttpMethod.Post, postRequest.method)
        assertEquals("/v1/sync/state", postRequest.url.encodedPath)
        assertEquals("Bearer sync-token", postRequest.headers[HttpHeaders.Authorization])

        val contentType = postRequest.body.contentType
            ?: postRequest.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) }
        assertNotNull(contentType)
        assertTrue(contentType.match(ContentType.Application.Json))

        val sentJson = json.parseToJsonElement(postRequest.bodyText()).jsonObject
        assertFalse("progress" in sentJson)
        val sentItem = sentJson.getValue("bookshelf").jsonArray.single().jsonObject
        assertEquals("123", sentItem.getValue("novelId").jsonPrimitive.content)
        assertEquals("", sentItem.getValue("author").jsonPrimitive.content)
        assertEquals(JsonNull, sentItem["deletedAt"])

        assertEquals(1500L, pullNull.cursor)
        assertEquals(1500L, pullZero.cursor)
        assertEquals(1500L, pullSince.cursor)
        assertEquals(1, pullSince.bookshelf.size)
        assertEquals(5, pullSince.progress.single().paragraphIndex)
        assertEquals(2000L, pushResponse.serverTime)
        assertEquals(250L, pushResponse.bookshelf.single().updatedAt)
    }

    @Test
    fun parses503RetryAfterAnd401ErrorsIntoApiException() = runTest {
        val client = createClient { request ->
            when (request.url.encodedPath) {
                "/v1/linovelib/home" -> respondJson(
                    body = """{"code": 20001, "message": "Cloudflare blocked"}""",
                    status = HttpStatusCode.ServiceUnavailable,
                    extraHeaders = mapOf(HttpHeaders.RetryAfter to listOf("15")),
                )

                "/v1/sources" -> respondJson(
                    body = """{"code": 40100, "message": "Unauthorized"}""",
                    status = HttpStatusCode.Unauthorized,
                )

                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        val blockedException = assertFailsWith<ApiException> {
            client.fetchExploreHome(
                baseUrl = "https://api.acanthis.test",
                authToken = "secret",
                platform = "linovelib",
                channel = "home",
            )
        }
        assertEquals(503, blockedException.statusCode)
        assertEquals(20001, blockedException.apiCode)
        assertEquals("Cloudflare blocked", blockedException.message)
        assertEquals(15, blockedException.retryAfterSeconds)

        val unauthorizedException = assertFailsWith<ApiException> {
            client.fetchSources(
                baseUrl = "https://api.acanthis.test",
                authToken = "wrong",
            )
        }
        assertEquals(401, unauthorizedException.statusCode)
        assertEquals(40100, unauthorizedException.apiCode)
        assertEquals("Unauthorized", unauthorizedException.message)
        assertNull(unauthorizedException.retryAfterSeconds)
    }
}
