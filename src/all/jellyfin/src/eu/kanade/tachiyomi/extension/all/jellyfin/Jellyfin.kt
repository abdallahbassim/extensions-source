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

    // DIRECT APPROACH: Get folders from Books library specifically
    override fun popularMangaRequest(page: Int): Request {
        if (!authenticateIfNeeded()) {
            throw IOException("Authentication failed")
        }

        val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
        val userId = preferences.getString("user_id", "") ?: ""

        // Use the known Books library ID from your previous screenshots: 4e985111ed7f570b595204d82adb02f3
        val booksLibraryId = "4e985111ed7f570b595204d82adb02f3"

        Log.d("JellyfinAuth", "Getting manga series folders from Books library ID: $booksLibraryId")

        // Get folders directly from the Books library
        val httpUrl = "$serverUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
            addQueryParameter("ParentID", booksLibraryId) // Use your Books library ID directly
            addQueryParameter("IncludeItemTypes", "Folder")
            addQueryParameter("Recursive", "false") // Only direct children
            addQueryParameter("SortBy", "SortName")
            addQueryParameter("SortOrder", "Ascending")
            addQueryParameter("StartIndex", ((page - 1) * 50).toString())
            addQueryParameter("Limit", "50")
            addQueryParameter("Fields", "Overview,ChildCount")
        }.build()

        Log.d("JellyfinAuth", "Direct books folders URL: $httpUrl")
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
        Log.d("JellyfinAuth", "Found ${result.Items.size} folders in Books library")

        // All these folders should be manga series (ALPHA, Anz, Athena Complex, etc.)
        val mangaList = result.Items.map { folder ->
            SManga.create().apply {
                title = folder.Name
                url = "/Items/${folder.Id}"
                thumbnail_url = getThumbnailUrl(folder.Id)
                description = buildString {
                    if (!folder.Overview.isNullOrEmpty()) {
                        append(folder.Overview)
                        append("\n\n")
                    }
                    append("📚 Manga Series")
                    folder.ChildCount?.let { count ->
                        append(" ($count chapters)")
                    }
                    append("\n🆔 ID: ${folder.Id}")
                    append("\n📱 Type: ${folder.Type ?: "Folder"}")
                }
                author = "Unknown"
                genre = "Manga"
                status = SManga.ONGOING
                initialized = true
            }
        }

        Log.d("JellyfinAuth", "Created ${mangaList.size} manga entries: ${mangaList.map { it.title }}")
        return MangasPage(mangaList, result.Items.size == 50)
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
        val userId = preferences.getString("user_id", "") ?: ""
        val itemId = manga.url.substringAfterLast("/")

        Log.d("JellyfinAuth", "=== MANGA DETAILS REQUEST ===")
        Log.d("JellyfinAuth", "Manga URL: ${manga.url}")
        Log.d("JellyfinAuth", "Item ID: $itemId")
        Log.d("JellyfinAuth", "User ID: $userId")

        // Try the user-specific endpoint instead of the generic one
        val requestUrl = "$serverUrl/Users/$userId/Items/$itemId"
        Log.d("JellyfinAuth", "Request URL: $requestUrl")

        return Request.Builder()
            .url(requestUrl)
            .headers(getAuthHeaders())
            .build()
    }

    override fun mangaDetailsParse(response: Response): SManga {
        Log.d("JellyfinAuth", "=== MANGA DETAILS PARSE ===")
        Log.d("JellyfinAuth", "Response code: ${response.code}")

        val responseBody = response.body?.string() ?: ""
        Log.d("JellyfinAuth", "Response length: ${responseBody.length}")
        Log.d("JellyfinAuth", "Response preview: ${responseBody.take(200)}")

        return try {
            val item = json.decodeFromString<JellyfinItem>(responseBody)

            Log.d("JellyfinAuth", "Parsed manga details: Name='${item.Name}', ID='${item.Id}', Type='${item.Type}'")

            SManga.create().apply {
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
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "Error parsing manga details: ${e.message}")
            e.printStackTrace()

            SManga.create().apply {
                title = "Error"
                description = "Failed to parse manga details: ${e.message}"
                author = "Error"
                genre = "Error"
                status = SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        Log.d("JellyfinAuth", "=== CHAPTER LIST PARSE START ===")
        Log.d("JellyfinAuth", "Response code: ${response.code}")

        val responseBody = response.body?.string() ?: ""
        Log.d("JellyfinAuth", "Response length: ${responseBody.length}")
        Log.d("JellyfinAuth", "Response preview: ${responseBody.take(200)}")

        return try {
            val item = json.decodeFromString<JellyfinItem>(responseBody)
            Log.d("JellyfinAuth", "Parsed item: Name='${item.Name}', ID='${item.Id}', Type='${item.Type}'")

            // Get real chapters from the folder
            val chapters = getChaptersFromFolder(item.Id)
            Log.d("JellyfinAuth", "Got ${chapters.size} chapters from folder")
            chapters
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "ERROR in chapterListParse: ${e.message}")
            e.printStackTrace()

            // Return error chapter so user can see what went wrong
            listOf(
                SChapter.create().apply {
                    name = "📚 Parse Error: ${e.message?.take(100) ?: "Unknown error"}"
                    url = "/Items/error"
                    chapter_number = 1f
                    date_upload = System.currentTimeMillis()
                },
            )
        }
    }

    private fun getChaptersFromFolder(folderId: String): List<SChapter> {
        return try {
            val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
            val userId = preferences.getString("user_id", "") ?: ""

            Log.d("JellyfinAuth", "Getting chapters from folder ID: $folderId")

            val httpUrl = "$serverUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
                addQueryParameter("ParentID", folderId)
                addQueryParameter("Recursive", "false")
                addQueryParameter("SortBy", "SortName")
                addQueryParameter("SortOrder", "Ascending")
                addQueryParameter("Fields", "Overview,Path,MediaSources,MediaType,MediaStreams")
                addQueryParameter("EnableImages", "true")
            }.build()

            Log.d("JellyfinAuth", "Chapters URL: $httpUrl")

            val request = Request.Builder().url(httpUrl).headers(getAuthHeaders()).build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            Log.d("JellyfinAuth", "Chapters response code: ${response.code}")

            if (response.isSuccessful) {
                val result = json.decodeFromString<JellyfinResponse>(responseBody)
                Log.d("JellyfinAuth", "Found ${result.Items.size} items in manga folder")

                if (result.Items.isNotEmpty()) {
                    result.Items.mapIndexed { index, item ->
                        SChapter.create().apply {
                            name = when {
                                item.Name.contains("Ch.", ignoreCase = true) -> "📖 ${item.Name}"
                                item.Name.contains("Chapter", ignoreCase = true) -> "📖 ${item.Name}"
                                item.Name.contains("Vol.", ignoreCase = true) -> "📖 ${item.Name}"
                                item.Name.matches(Regex("\\d+")) -> "📖 Ch.${item.Name}"
                                item.Name.endsWith(".pdf", ignoreCase = true) -> "📄 ${item.Name}"
                                item.Name.endsWith(".epub", ignoreCase = true) -> "📖 ${item.Name}"
                                item.Name.endsWith(".cbz", ignoreCase = true) -> "📚 ${item.Name}"
                                item.Name.endsWith(".cbr", ignoreCase = true) -> "📚 ${item.Name}"
                                else -> "📄 ${item.Name}"
                            }
                            // Store just the item ID - we'll build the full URL in pageListRequest
                            url = "/Items/${item.Id}"
                            chapter_number = (index + 1).toFloat()
                            date_upload = System.currentTimeMillis()
                        }
                    }
                } else {
                    Log.w("JellyfinAuth", "No items found in manga folder")
                    listOf(
                        SChapter.create().apply {
                            name = "📚 No items found in this folder"
                            url = "/Items/$folderId"
                            chapter_number = 1f
                            date_upload = System.currentTimeMillis()
                        },
                    )
                }
            } else {
                Log.e("JellyfinAuth", "HTTP Error ${response.code} getting chapters")
                Log.e("JellyfinAuth", "Error response: $responseBody")
                listOf(
                    SChapter.create().apply {
                        name = "📚 HTTP Error ${response.code}"
                        url = "/Items/$folderId"
                        chapter_number = 1f
                        date_upload = System.currentTimeMillis()
                    },
                )
            }
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "Exception getting chapters: ${e.message}")
            e.printStackTrace()
            listOf(
                SChapter.create().apply {
                    name = "📚 Exception: ${e.message?.take(50) ?: "Unknown"}"
                    url = "/Items/$folderId"
                    chapter_number = 1f
                    date_upload = System.currentTimeMillis()
                },
            )
        }
    }

    // FIXED PAGE LIST REQUEST
    override fun pageListRequest(chapter: SChapter): Request {
        val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
        val userId = preferences.getString("user_id", "") ?: ""

        // Extract the actual item ID properly - handle both old URLs with /Download and new ones
        val itemId = when {
            chapter.url.contains("/Download") -> {
                // Old format: /Items/{id}/Download
                chapter.url.substringAfter("/Items/").substringBefore("/Download")
            }
            else -> {
                // New format: /Items/{id}
                chapter.url.substringAfterLast("/")
            }
        }

        Log.d("JellyfinAuth", "=== PAGE LIST REQUEST ===")
        Log.d("JellyfinAuth", "Chapter URL: ${chapter.url}")
        Log.d("JellyfinAuth", "Extracted Item ID: $itemId")
        Log.d("JellyfinAuth", "Server URL: $serverUrl")
        Log.d("JellyfinAuth", "User ID: $userId")

        // Try to get the item details first to determine the proper download method
        val itemDetailsUrl = "$serverUrl/Users/$userId/Items/$itemId"
        Log.d("JellyfinAuth", "Item details URL: $itemDetailsUrl")

        return Request.Builder()
            .url(itemDetailsUrl)
            .headers(getAuthHeaders())
            .build()
    }

    // UPDATED CBZ-READY PAGE LIST PARSE
    override fun pageListParse(response: Response): List<Page> {
        Log.d("JellyfinAuth", "=== CBZ PAGE LIST PARSE ===")
        Log.d("JellyfinAuth", "Response code: ${response.code}")

        val responseBody = response.body?.string() ?: ""
        Log.d("JellyfinAuth", "Response length: ${responseBody.length}")

        if (!response.isSuccessful) {
            Log.e("JellyfinAuth", "HTTP Error ${response.code} in pageListParse")
            throw IOException("Failed to get item details: HTTP ${response.code}")
        }

        return try {
            val item = json.decodeFromString<JellyfinItem>(responseBody)
            Log.d("JellyfinAuth", "CBZ Item details: Name='${item.Name}', Type='${item.Type}', ID='${item.Id}'")

            val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
            val userId = preferences.getString("user_id", "") ?: ""
            val apiKey = preferences.getString("api_key", "") ?: ""

            // Create download URL with proper headers for CBZ files
            val downloadUrl = buildString {
                append("$serverUrl/Items/${item.Id}/Download")
                append("?api_key=$apiKey")
                append("&UserId=$userId")
                // Add headers to indicate this is a CBZ file download
                append("&MediaSourceId=${item.Id}")
                append("&Static=true")
            }

            Log.d("JellyfinAuth", "Generated CBZ download URL: $downloadUrl")

            // Check if this is a comic book file
            val isComicBook = item.Type == "Book" ||
                item.Name.contains("chapter", ignoreCase = true) ||
                item.Name.contains("ch.", ignoreCase = true) ||
                item.Name.contains("ch ", ignoreCase = true)

            if (isComicBook) {
                Log.d("JellyfinAuth", "Detected comic book, trying CBZ extraction")

                // First try to extract individual pages
                val extractedPages = tryExtractCBZPages(item.Id, serverUrl, userId, apiKey)

                if (extractedPages.isNotEmpty()) {
                    Log.d("JellyfinAuth", "SUCCESS: Extracted ${extractedPages.size} individual pages")
                    return extractedPages
                }

                // If extraction fails, serve as downloadable CBZ
                Log.d("JellyfinAuth", "Page extraction failed, serving as downloadable CBZ file")

                // Create a single page that points to the CBZ download
                return listOf(
                    Page(0, downloadUrl, downloadUrl).apply {
                        Log.d("JellyfinAuth", "Created CBZ download page: $downloadUrl")
                    },
                )
            } else {
                // For non-comic files, handle normally
                Log.d("JellyfinAuth", "Non-comic file, using direct download")
                return listOf(Page(0, downloadUrl, downloadUrl))
            }
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "CBZ Error in pageListParse: ${e.message}")
            e.printStackTrace()
            throw IOException("Failed to parse CBZ item: ${e.message}")
        }
    }

    // CBZ extraction method
    private fun tryExtractCBZPages(itemId: String, serverUrl: String, userId: String, apiKey: String): List<Page> {
        Log.d("JellyfinAuth", "=== CBZ EXTRACTION START ===")
        Log.d("JellyfinAuth", "Trying CBZ page extraction for item: $itemId")

        val pages = mutableListOf<Page>()

        try {
            // Method 1: Try Jellyfin's attachment/media endpoints for CBZ
            Log.d("JellyfinAuth", "CBZ Method 1: Checking attachments...")

            val attachmentsUrl = "$serverUrl/Items/$itemId/Attachments"
            val attachmentRequest = Request.Builder()
                .url(attachmentsUrl)
                .headers(getAuthHeaders())
                .build()

            val attachmentResponse = client.newCall(attachmentRequest).execute()

            if (attachmentResponse.isSuccessful) {
                val attachmentBody = attachmentResponse.body?.string() ?: "[]"
                Log.d("JellyfinAuth", "CBZ Attachments response: ${attachmentBody.take(500)}...")

                // Try to parse the attachments as a simple JSON array
                if (attachmentBody.trim().startsWith("[")) {
                    try {
                        val attachments = json.decodeFromString<List<JellyfinAttachment>>(attachmentBody)
                        if (attachments.isNotEmpty()) {
                            Log.d("JellyfinAuth", "CBZ Found ${attachments.size} attachments")

                            // Filter for image attachments and create pages
                            val imageAttachments = attachments.filter { attachment ->
                                attachment.Filename.endsWith(".jpg", ignoreCase = true) ||
                                    attachment.Filename.endsWith(".jpeg", ignoreCase = true) ||
                                    attachment.Filename.endsWith(".png", ignoreCase = true) ||
                                    attachment.Filename.endsWith(".gif", ignoreCase = true) ||
                                    attachment.Filename.endsWith(".webp", ignoreCase = true)
                            }

                            if (imageAttachments.isNotEmpty()) {
                                imageAttachments.forEachIndexed { index, attachment ->
                                    val imageUrl = "$serverUrl/Items/$itemId/Attachments/${attachment.Index}?api_key=$apiKey"
                                    pages.add(Page(index, imageUrl, imageUrl))
                                    Log.d("JellyfinAuth", "CBZ Added page $index: ${attachment.Filename}")
                                }

                                if (pages.isNotEmpty()) {
                                    Log.d("JellyfinAuth", "CBZ Method 1 SUCCESS: Found ${pages.size} image pages via attachments")
                                    return pages
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("JellyfinAuth", "CBZ Failed to parse attachments JSON: ${e.message}")
                    }
                }
            }
            attachmentResponse.close()

            // Method 2: Try Jellyfin's image extraction endpoints for CBZ
            Log.d("JellyfinAuth", "CBZ Method 2: Trying image extraction...")

            for (pageIndex in 0 until 100) { // Try up to 100 pages
                val pageUrl = "$serverUrl/Items/$itemId/Images/Page/$pageIndex?api_key=$apiKey&maxWidth=1200&quality=90"

                val pageRequest = Request.Builder()
                    .url(pageUrl)
                    .head()
                    .headers(getAuthHeaders())
                    .build()

                val pageResponse = client.newCall(pageRequest).execute()

                if (pageResponse.isSuccessful) {
                    pages.add(Page(pageIndex, pageUrl, pageUrl))
                    Log.d("JellyfinAuth", "CBZ Found page $pageIndex via image extraction")
                } else {
                    if (pageIndex == 0) {
                        Log.d("JellyfinAuth", "CBZ First page not found via image extraction")
                        break
                    } else {
                        Log.d("JellyfinAuth", "CBZ No more pages found after page ${pageIndex - 1}")
                        break
                    }
                }
                pageResponse.close()
            }

            if (pages.isNotEmpty()) {
                Log.d("JellyfinAuth", "CBZ Method 2 SUCCESS: Found ${pages.size} pages via image extraction")
                return pages
            }
        } catch (e: Exception) {
            Log.e("JellyfinAuth", "CBZ Error during extraction: ${e.message}")
            e.printStackTrace()
        }

        Log.d("JellyfinAuth", "CBZ All extraction methods failed")
        return emptyList()
    }

    // UPDATED IMAGE URL PARSE FOR CBZ
    override fun imageUrlParse(response: Response): String {
        val url = response.request.url.toString()
        Log.d("JellyfinAuth", "=== CBZ IMAGE URL PARSE ===")
        Log.d("JellyfinAuth", "Request URL: $url")
        Log.d("JellyfinAuth", "Response code: ${response.code}")

        // For CBZ files, we want to return the download URL as-is
        // This should trigger Mihon to download the CBZ file
        if (url.contains("/Download") && url.contains("api_key")) {
            Log.d("JellyfinAuth", "CBZ download URL detected, returning for download: $url")
            return url
        }

        // For individual page images (if extraction worked)
        if (url.contains("/Attachments/") || url.contains("/Images/Page/")) {
            Log.d("JellyfinAuth", "CBZ individual page image detected: $url")
            return url
        }

        Log.d("JellyfinAuth", "CBZ default URL handling: $url")
        return url
    }

    private fun getThumbnailUrl(itemId: String): String {
        val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
        return "$serverUrl/Items/$itemId/Images/Primary?maxWidth=300&maxHeight=450"
    }

    private fun getProperAccessUrl(item: JellyfinItem): String {
        val serverUrl = normalizeServerUrl(preferences.getString("server_url", "") ?: "")
        val userId = preferences.getString("user_id", "") ?: ""
        val apiKey = preferences.getString("api_key", "") ?: ""

        return when {
            item.Name.endsWith(".pdf", ignoreCase = true) -> {
                // PDFs might need different handling
                "$serverUrl/Items/${item.Id}/Download?api_key=$apiKey&UserId=$userId"
            }
            item.Name.endsWith(".epub", ignoreCase = true) -> {
                // EPUBs might need streaming
                "$serverUrl/Items/${item.Id}/Stream?api_key=$apiKey&UserId=$userId&Static=true"
            }
            item.Name.endsWith(".cbz", ignoreCase = true) ||
                item.Name.endsWith(".cbr", ignoreCase = true) -> {
                // Comic book archives - try to extract individual pages
                "$serverUrl/Items/${item.Id}/Download?api_key=$apiKey&UserId=$userId"
            }
            else -> {
                // Default download approach
                "$serverUrl/Items/${item.Id}/Download?api_key=$apiKey&UserId=$userId"
            }
        }
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
