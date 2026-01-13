@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
@file:CompilerOptions("-opt-in=kotlin.RequiresOptIn")

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val youtrackBaseUrl: String = System.getenv("input_youtrack_base_url")
    ?: error("Input \"youtrack_base_url\" is not set.")
val youtrackToken: String = System.getenv("input_youtrack_token")
    ?: error("Input \"youtrack_token\" is not set.")

val apiURL: String = "${youtrackBaseUrl.trimEnd('/')}/api/admin/databaseBackup/settings"
val jsonPayload: String = buildJsonObject {
    put("backupStatus", buildJsonObject {
        put("backupInProgress", true)
    })
}.toString()

println("Triggering YouTrack backup at: $apiURL")

val client: HttpClient = HttpClient.newHttpClient()
val request: HttpRequest = HttpRequest.newBuilder()
    .uri(URI.create(apiURL))
    .header("Accept", "application/json")
    .header("Content-Type", "application/json")
    .header("Authorization", "Bearer $youtrackToken")
    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
    .build()

val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
val statusCode: Int = response.statusCode()
val responseBody: String = response.body()

when (statusCode) {
    in 200..299 -> {
        println("✓ Backup triggered successfully (HTTP $statusCode)")
        println("Response: $responseBody")
    }
    else -> error("Failed to trigger backup (HTTP $statusCode): $responseBody")
}
