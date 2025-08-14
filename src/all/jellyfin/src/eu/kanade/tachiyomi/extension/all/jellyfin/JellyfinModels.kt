package eu.kanade.tachiyomi.extension.all.jellyfin

import kotlinx.serialization.Serializable

@Serializable
data class JellyfinResponse(
    val Items: List<JellyfinItem>,
    val TotalRecordCount: Int,
)

@Serializable
data class JellyfinItem(
    val Id: String,
    val Name: String,
    val Type: String? = null,
    val Overview: String? = null,
    val Genres: List<String>? = null,
    val People: List<JellyfinPerson>? = null,
    val ChildCount: Int? = null,
)

@Serializable
data class JellyfinPerson(
    val Name: String,
    val Type: String,
)

@Serializable
data class JellyfinUser(
    val Id: String,
    val Name: String,
)

@Serializable
data class JellyfinAttachment(
    val Index: Int,
    val Filename: String,
    val MimeType: String? = null,
)
