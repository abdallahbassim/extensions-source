package eu.kanade.tachiyomi.extension.all.jellyfin

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class Jellyfin : ConfigurableSource, HttpSource() {

    override val name = "Jellyfin"
    override val baseUrl = ""
    override val lang = "all"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_preferences", 0)
    }

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Mihon-Jellyfin-Extension/1.0.0")
        add("Accept", "application/json")
    }

    private fun getAuthHeaders(): Headers {
        val apiKey = preferences.getString("api_key", "") ?: ""
        return headersBuilder().apply {
            if (apiKey.isNotEmpty()) {
                add("X-Emby-Token", apiKey)
            }
        }.build()
    }

    private fun normalizeServerUrl(url: String): String {
        var normalized = url.trim()

        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }

        normalized = normalized.removeSuffix("/")

        Log.d("JellyfinAuth", "Normalized server URL: $normalized")
        return normalized
    }

    private fun authenticateIfNeeded(): Boolean {
        val apiKey = preferences.getString("api_key", "") ?: ""
        val userId = preferences.getString("user_id", "") ?: ""

        if (apiKey.isNotEmpty() && userId.isNotEmpty()) {
            Log.d("JellyfinAuth", "Using existing API key and user ID")
            return true
        }

        val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")

        if (serverUrl.isEmpty()) {
            Log.e("JellyfinAuth", "Server URL is empty")
            throw IOException("Please configure Jellyfin server URL in settings")
        }

        if (apiKey.isEmpty()) {
            Log.e("JellyfinAuth", "API key is empty")
            throw IOException("Please configure API key in settings")
        }

        return try {
            Log.d("JellyfinAuth", "Testing API key with server: $serverUrl")

            val systemInfoUrl = "$serverUrl/System/Info"
            val request = Request.Builder()
                .url(systemInfoUrl)
                .headers(getAuthHeaders())
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            Log.d("JellyfinAuth", "System info response code: ${response.code}")

            if (response.isSuccessful) {
                getUserInfo(serverUrl, apiKey)
            } else {
                when (response.code) {
                    401 -> throw IOException("Invalid API key")
                    403 -> throw IOException("API key has insufficient permissions")
                    404 -> throw IOException("Jellyfin server not found. Check server URL")
                    500 -> throw IOException("Jellyfin server error")
                    else -> throw IOException("API key validation failed: HTTP ${response.code} - $responseBody")
                }
            }
        } catch (e: IOException) {
            Log.e("JellyfinAuth", "IOException during API key validation: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "Unexpected error during API key validation: ${e.message}")
            throw IOException("Connection failed: ${e.message}. Check server URL and network connection")
        }
    }

    private fun getUserInfo(serverUrl: String, apiKey: String): Boolean {
        return try {
            val usersUrl = "$serverUrl/Users"
            val request = Request.Builder()
                .url(usersUrl)
                .headers(getAuthHeaders())
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            Log.d("JellyfinAuth", "Users response code: ${response.code}")

            if (response.isSuccessful) {
                try {
                    val users = json.decodeFromString<List<JellyfinUser>>(responseBody)
                    if (users.isNotEmpty()) {
                        val user = users.first()
                        preferences.edit()
                            .putString("user_id", user.Id)
                            .apply()
                        Log.d("JellyfinAuth", "User ID saved: ${user.Id} (${user.Name})")
                        true
                    } else {
                        throw IOException("No users found on Jellyfin server")
                    }
                } catch (e: Exception) {
                    Log.e("JellyfinAuth", "Failed to parse users: ${e.message}")
                    throw IOException("Failed to get user information: ${e.message}")
                }
            } else {
                throw IOException("Failed to get users: HTTP ${response.code} - $responseBody")
            }
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "Error getting user info: ${e.message}")
            throw IOException("Failed to get user information: ${e.message}")
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        if (!authenticateIfNeeded()) {
            throw IOException("Authentication failed")
        }

        val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
        val userId = preferences.getString("user_id", "") ?: ""

        val httpUrl = "$serverUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
            addQueryParameter("IncludeItemTypes", "Folder")
            addQueryParameter("Recursive", "false")
            addQueryParameter("SortBy", "SortName")
            addQueryParameter("SortOrder", "Ascending")
            addQueryParameter("StartIndex", ((page - 1) * 20).toString())
            addQueryParameter("Limit", "20")
            addQueryParameter("Fields", "Overview,ChildCount")
        }.build()

        return Request.Builder().url(httpUrl).headers(getAuthHeaders()).build()
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            when (response.code) {
                401 -> {
                    preferences.edit()
                        .putString("api_key", "")
                        .putString("user_id", "")
                        .apply()
                    throw IOException("API key expired or invalid. Please reconfigure")
                }
                else -> throw IOException("Server error: HTTP ${response.code}")
            }
        }

        val result = json.decodeFromString<JellyfinResponse>(responseBody)

        val mangaList = if (result.Items.isEmpty()) {
            getBooksFromBooksLibrary()
        } else {
            result.Items.map { item ->
                SManga.create().apply {
                    title = item.Name
                    url = "/Items/${item.Id}"
                    thumbnail_url = getThumbnailUrl(item.Id)
                    description = buildString {
                        if (!item.Overview.isNullOrEmpty()) {
                            append(item.Overview)
                            append("\n\n")
                        }
                        append("📁 Manga Series")
                        item.ChildCount?.let { count ->
                            append(" ($count chapters)")
                        }
                        append("\n🆔 ID: ${item.Id}")
                        append("\n📱 Type: ${item.Type ?: "Folder"}")
                    }
                    author = "Unknown"
                    genre = "Manga"
                    status = SManga.ONGOING
                    initialized = true
                }
            }
        }

        return MangasPage(mangaList, result.Items.size == 20)
    }

    private fun getBooksFromBooksLibrary(): List<SManga> {
        return try {
            val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
            val userId = preferences.getString("user_id", "") ?: ""

            Log.d("JellyfinAuth", "Getting folders from Books library specifically")

            val viewsUrl = "$serverUrl/Users/$userId/Views".toHttpUrl()
            val viewsRequest = Request.Builder().url(viewsUrl).headers(getAuthHeaders()).build()
            val viewsResponse = client.newCall(viewsRequest).execute()

            if (viewsResponse.isSuccessful) {
                val viewsBody = viewsResponse.body?.string() ?: ""
                val viewsResult = json.decodeFromString<JellyfinResponse>(viewsBody)

                val booksLibrary = viewsResult.Items.find { it.Name.equals("Books", ignoreCase = true) }

                if (booksLibrary != null) {
                    Log.d("JellyfinAuth", "Found Books library with ID: ${booksLibrary.Id}")

                    val httpUrl = "$serverUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
                        addQueryParameter("ParentID", booksLibrary.Id)
                        addQueryParameter("IncludeItemTypes", "Folder")
                        addQueryParameter("Recursive", "false")
                        addQueryParameter("SortBy", "SortName")
                        addQueryParameter("SortOrder", "Ascending")
                        addQueryParameter("Fields", "Overview,ChildCount")
                    }.build()

                    val request = Request.Builder().url(httpUrl).headers(getAuthHeaders()).build()
                    val response = client.newCall(request).execute()

                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        val result = json.decodeFromString<JellyfinResponse>(responseBody)

                        Log.d("JellyfinAuth", "Found ${result.Items.size} manga series folders")

                        result.Items.map { folder ->
                            SManga.create().apply {
                                title = folder.Name
                                url = "/Items/${folder.Id}"
                                thumbnail_url = getThumbnailUrl(folder.Id)
                                description = buildString {
                                    if (!folder.Overview.isNullOrEmpty()) {
                                        append(folder.Overview)
                                        append("\n\n")
                                    }
                                    append("📁 Manga Series")
                                    folder.ChildCount?.let { count ->
                                        append(" ($count chapters)")
                                    }
                                    append("\n🆔 ID: ${folder.Id}")
                                }
                                author = "Unknown"
                                genre = "Manga"
                                status = SManga.ONGOING
                                initialized = true
                            }
                        }
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "Error getting Books library: ${e.message}")
            emptyList()
        }
    }

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (!authenticateIfNeeded()) {
            throw IOException("Authentication failed")
        }

        val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
        val userId = preferences.getString("user_id", "") ?: ""

        val httpUrl = "$serverUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
            addQueryParameter("IncludeItemTypes", "Folder")
            addQueryParameter("Recursive", "true")
            addQueryParameter("SearchTerm", query)
            addQueryParameter("StartIndex", ((page - 1) * 20).toString())
            addQueryParameter("Limit", "20")
        }.build()

        return Request.Builder().url(httpUrl).headers(getAuthHeaders()).build()
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
        val itemId = manga.url.substringAfterLast("/")

        return Request.Builder()
            .url("$serverUrl/Items/$itemId")
            .headers(getAuthHeaders())
            .build()
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val responseBody = response.body?.string() ?: ""
        val item = json.decodeFromString<JellyfinItem>(responseBody)

        return SManga.create().apply {
            title = item.Name
            description = buildString {
                if (!item.Overview.isNullOrEmpty()) {
                    append(item.Overview)
                    append("\n\n")
                }
                append("🆔 ID: ${item.Id}")
                append("\n📱 Type: ${item.Type ?: "Unknown"}")
                if (!item.Genres.isNullOrEmpty()) {
                    append("\n🏷️ Genres: ${item.Genres.joinToString(", ")}")
                }
            }
            author = item.People?.find { it.Type == "Author" }?.Name ?: "Unknown"
            genre = item.Genres?.joinToString(", ") ?: ""
            status = SManga.ONGOING
            thumbnail_url = getThumbnailUrl(item.Id)
        }
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val responseBody = response.body?.string() ?: ""

        return try {
            val item = json.decodeFromString<JellyfinItem>(responseBody)

            when (item.Type) {
                "CollectionFolder", "Folder" -> {
                    getChildBooks(item.Id)
                }
                "Book" -> {
                    listOf(
                        SChapter.create().apply {
                            name = when {
                                item.Name.endsWith(".pdf", ignoreCase = true) -> "📄 ${item.Name}"
                                item.Name.endsWith(".epub", ignoreCase = true) -> "📖 ${item.Name}"
                                item.Name.endsWith(".mobi", ignoreCase = true) -> "📱 ${item.Name}"
                                else -> "📚 ${item.Name}"
                            }
                            url = "/Items/${item.Id}/Download"
                            chapter_number = 1f
                            date_upload = System.currentTimeMillis()
                        },
                    )
                }
                else -> {
                    val children = getChildBooks(item.Id)
                    if (children.isNotEmpty()) {
                        children
                    } else {
                        listOf(
                            SChapter.create().apply {
                                name = "📚 ${item.Name}"
                                url = "/Items/${item.Id}/Download"
                                chapter_number = 1f
                                date_upload = System.currentTimeMillis()
                            },
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "Error parsing chapter list: ${e.message}")
            Log.e("JellyfinAuth", "Response body: $responseBody")

            listOf(
                SChapter.create().apply {
                    name = "📚 Read Book"
                    url = "/Items/unknown/Download"
                    chapter_number = 1f
                    date_upload = System.currentTimeMillis()
                },
            )
        }
    }

    private fun getChildBooks(parentId: String): List<SChapter> {
        return try {
            val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
            val userId = preferences.getString("user_id", "") ?: ""

            Log.d("JellyfinAuth", "Getting chapters for manga series ID: $parentId")

            val httpUrl = "$serverUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
                addQueryParameter("ParentID", parentId)
                addQueryParameter("IncludeItemTypes", "Book")
                addQueryParameter("Recursive", "false")
                addQueryParameter("SortBy", "SortName")
                addQueryParameter("SortOrder", "Ascending")
                addQueryParameter("Fields", "Overview,Path")
                addQueryParameter("EnableImages", "true")
            }.build()

            Log.d("JellyfinAuth", "Chapters URL: $httpUrl")

            val request = Request.Builder().url(httpUrl).headers(getAuthHeaders()).build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            Log.d("JellyfinAuth", "Chapters response code: ${response.code}")
            Log.d("JellyfinAuth", "Chapters response: ${responseBody.take(500)}...")

            if (response.isSuccessful) {
                val result = json.decodeFromString<JellyfinResponse>(responseBody)

                Log.d("JellyfinAuth", "Found ${result.Items.size} chapters in manga series")

                if (result.Items.isNotEmpty()) {
                    result.Items.mapIndexed { index, chapter ->
                        SChapter.create().apply {
                            name = when {
                                chapter.Name.contains("Ch.", ignoreCase = true) -> "📖 ${chapter.Name}"
                                chapter.Name.contains("Chapter", ignoreCase = true) -> "📖 ${chapter.Name}"
                                chapter.Name.endsWith(".pdf", ignoreCase = true) -> "📄 ${chapter.Name}"
                                chapter.Name.endsWith(".epub", ignoreCase = true) -> "📖 ${chapter.Name}"
                                chapter.Name.endsWith(".cbz", ignoreCase = true) -> "📚 ${chapter.Name}"
                                chapter.Name.endsWith(".cbr", ignoreCase = true) -> "📚 ${chapter.Name}"
                                else -> "📖 ${chapter.Name}"
                            }
                            url = "/Items/${chapter.Id}/Download"
                            chapter_number = (index + 1).toFloat()
                            date_upload = System.currentTimeMillis()
                        }
                    }
                } else {
                    Log.d("JellyfinAuth", "No chapters found, trying without IncludeItemTypes filter")
                    getChaptersWithoutFilter(parentId)
                }
            } else {
                Log.e("JellyfinAuth", "Failed to get chapters: HTTP ${response.code}")
                Log.e("JellyfinAuth", "Response body: $responseBody")
                getChaptersWithoutFilter(parentId)
            }
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "Error getting chapters: ${e.message}")
            getChaptersWithoutFilter(parentId)
        }
    }

    private fun getChaptersWithoutFilter(parentId: String): List<SChapter> {
        return try {
            val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
            val userId = preferences.getString("user_id", "") ?: ""

            Log.d("JellyfinAuth", "Trying to get all items from manga series (no filter): $parentId")

            val httpUrl = "$serverUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
                addQueryParameter("ParentID", parentId)
                addQueryParameter("Recursive", "false")
                addQueryParameter("SortBy", "SortName")
                addQueryParameter("SortOrder", "Ascending")
                addQueryParameter("Fields", "Overview,Path,MediaType")
                addQueryParameter("EnableImages", "true")
            }.build()

            Log.d("JellyfinAuth", "Unfiltered chapters URL: $httpUrl")

            val request = Request.Builder().url(httpUrl).headers(getAuthHeaders()).build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            Log.d("JellyfinAuth", "Unfiltered chapters response code: ${response.code}")
            Log.d("JellyfinAuth", "Unfiltered chapters response: ${responseBody.take(500)}...")

            if (response.isSuccessful) {
                val result = json.decodeFromString<JellyfinResponse>(responseBody)

                Log.d("JellyfinAuth", "Found ${result.Items.size} items total in manga series")

                if (result.Items.isNotEmpty()) {
                    result.Items.mapIndexed { index, item ->
                        SChapter.create().apply {
                            name = when {
                                item.Name.contains("Ch.", ignoreCase = true) -> "📖 ${item.Name}"
                                item.Name.contains("Chapter", ignoreCase = true) -> "📖 ${item.Name}"
                                item.Name.endsWith(".pdf", ignoreCase = true) -> "📄 ${item.Name}"
                                item.Name.endsWith(".epub", ignoreCase = true) -> "📖 ${item.Name}"
                                item.Name.endsWith(".cbz", ignoreCase = true) -> "📚 ${item.Name}"
                                item.Name.endsWith(".cbr", ignoreCase = true) -> "📚 ${item.Name}"
                                item.Type == "Book" -> "📚 ${item.Name}"
                                else -> "📄 ${item.Name} (${item.Type ?: "Unknown"})"
                            }
                            url = "/Items/${item.Id}/Download"
                            chapter_number = (index + 1).toFloat()
                            date_upload = System.currentTimeMillis()
                        }
                    }
                } else {
                    listOf(
                        SChapter.create().apply {
                            name = "📚 No chapters found in this series"
                            url = "/Items/$parentId/Download"
                            chapter_number = 1f
                            date_upload = System.currentTimeMillis()
                        },
                    )
                }
            } else {
                listOf(
                    SChapter.create().apply {
                        name = "📚 HTTP Error ${response.code}: ${responseBody.take(50)}"
                        url = "/Items/$parentId/Download"
                        chapter_number = 1f
                        date_upload = System.currentTimeMillis()
                    },
                )
            }
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "Error in unfiltered chapters approach: ${e.message}")
            listOf(
                SChapter.create().apply {
                    name = "📚 Exception: ${e.message?.take(50) ?: "Unknown error"}"
                    url = "/Items/$parentId/Download"
                    chapter_number = 1f
                    date_upload = System.currentTimeMillis()
                },
            )
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
        return Request.Builder()
            .url("$serverUrl${chapter.url}")
            .headers(getAuthHeaders())
            .build()
    }

    override fun pageListParse(response: Response): List<Page> {
        return listOf(
            Page(0, "", response.request.url.toString()),
        )
    }

    override fun imageUrlParse(response: Response): String {
        return response.request.url.toString()
    }

    private fun getThumbnailUrl(itemId: String): String {
        val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
        return "$serverUrl/Items/$itemId/Images/Primary?maxWidth=300&maxHeight=450"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "server_url"
            title = "Jellyfin Server URL"
            summary = "Examples: http://192.168.1.100:8096 or https://jellyfin.mydomain.com"
            dialogTitle = "Jellyfin Server URL"
            dialogMessage = "Enter your Jellyfin server URL (with http:// or https://)"
            setOnPreferenceChangeListener { _, newValue ->
                val normalizedUrl = normalizeServerUrl(newValue as String)
                preferences.edit()
                    .putString("server_url", normalizedUrl)
                    .putString("user_id", "")
                    .apply()
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "api_key"
            title = "Jellyfin API Key"
            summary = "Generate in Jellyfin Dashboard → API Keys"
            dialogTitle = "Jellyfin API Key"
            dialogMessage = "Enter your Jellyfin API key (create one in Dashboard → API Keys)"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putString("api_key", newValue as String)
                    .putString("user_id", "")
                    .apply()
                true
            }
        }.let(screen::addPreference)
    }

    override fun getFilterList() = FilterList()
}
