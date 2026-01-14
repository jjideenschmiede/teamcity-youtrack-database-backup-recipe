@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
@file:CompilerOptions("-opt-in=kotlin.RequiresOptIn")

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.*

val youtrackBaseUrl: String = System.getenv("input_youtrack_base_url")
    ?: error("Input \"youtrack_base_url\" is not set.")
val youtrackToken: String = System.getenv("input_youtrack_token")
    ?: error("Input \"youtrack_token\" is not set.")

val settingsURL: String = "${youtrackBaseUrl.trimEnd('/')}/api/admin/databaseBackup/settings"
val statusURL: String = "${youtrackBaseUrl.trimEnd('/')}/api/admin/databaseBackup/settings/backupStatus?fields=backupCancelled,backupError(date,errorMessage),backupInProgress,stopBackup"
val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(30))
    .build()

println("Triggering YouTrack backup at: $settingsURL")

val startRequest: HttpRequest = HttpRequest.newBuilder()
    .uri(URI.create(settingsURL))
    .header("Accept", "application/json")
    .header("Content-Type", "application/json")
    .header("Authorization", "Bearer $youtrackToken")
    .timeout(java.time.Duration.ofSeconds(60))
    .POST(HttpRequest.BodyPublishers.ofString(
        buildJsonObject {
            put("backupStatus", buildJsonObject {
                put("backupInProgress", true)
            })
        }.toString()
    ))
    .build()

val startResponse: HttpResponse<String> = client.send(startRequest, HttpResponse.BodyHandlers.ofString())

when (startResponse.statusCode()) {
    in 200..299 -> println("✓ Backup triggered successfully (HTTP ${startResponse.statusCode()})")
    else -> error("Failed to trigger backup (HTTP ${startResponse.statusCode()}): ${startResponse.body()}")
}

val startTime: Long = System.currentTimeMillis()
var backupCompleted = false
var pollCount = 0

while (!backupCompleted) {
    if (System.currentTimeMillis() - startTime > 60 * 60 * 1000) {
        error("Backup did not complete within 60 minutes")
    }

    Thread.sleep(10 * 1000)
    pollCount++

    if (pollCount % 6 == 0) {
        println(" (${(System.currentTimeMillis() - startTime) / 1000}s elapsed)")
    } else {
        print(".")
    }

    val statusRequest: HttpRequest = HttpRequest.newBuilder()
        .uri(URI.create(statusURL))
        .header("Accept", "application/json")
        .header("Authorization", "Bearer $youtrackToken")
        .timeout(java.time.Duration.ofSeconds(30))
        .GET()
        .build()

    val statusResponse: HttpResponse<String> = try {
        client.send(statusRequest, HttpResponse.BodyHandlers.ofString())
    } catch (e: Exception) {
        error("Failed to fetch backup status: ${e.message}")
    }

    if (statusResponse.statusCode() !in 200..299) {
        error("Failed to check backup status (HTTP ${statusResponse.statusCode()}): ${statusResponse.body()}")
    }

    val json = Json.parseToJsonElement(statusResponse.body()).jsonObject

    when {
        json["backupCancelled"]?.jsonPrimitive?.boolean == true ->
            error("Backup was cancelled")

        json["backupError"] is JsonObject ->
            error("Backup failed with error: ${json["backupError"]?.jsonObject?.get("errorMessage")?.jsonPrimitive?.contentOrNull}")

        json["backupInProgress"]?.jsonPrimitive?.boolean == false -> {
            backupCompleted = true
            println("\n✓ Backup completed successfully after ${(System.currentTimeMillis() - startTime) / 1000}s")
        }
    }
}

println("\nBackup process finished.")
