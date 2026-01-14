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
val pollIntervalSeconds: Long = System.getenv("input_poll_interval_seconds")?.toLongOrNull() ?: 10L
val maxWaitMinutes: Long = System.getenv("input_max_wait_minutes")?.toLongOrNull() ?: 60L

val settingsURL: String = "${youtrackBaseUrl.trimEnd('/')}/api/admin/databaseBackup/settings"
val statusURL: String = "${youtrackBaseUrl.trimEnd('/')}/api/admin/databaseBackup/settings/backupStatus?fields=backupCancelled,backupError(date,errorMessage),backupInProgress,stopBackup"
val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(30))
    .build()

println("Triggering YouTrack backup at: $settingsURL")

val startPayload: String = buildJsonObject {
    put("backupStatus", buildJsonObject {
        put("backupInProgress", true)
    })
}.toString()

val startRequest: HttpRequest = HttpRequest.newBuilder()
    .uri(URI.create(settingsURL))
    .header("Accept", "application/json")
    .header("Content-Type", "application/json")
    .header("Authorization", "Bearer $youtrackToken")
    .timeout(java.time.Duration.ofSeconds(60))
    .POST(HttpRequest.BodyPublishers.ofString(startPayload))
    .build()

val startResponse: HttpResponse<String> = client.send(startRequest, HttpResponse.BodyHandlers.ofString())

when (startResponse.statusCode()) {
    in 200..299 -> println("✓ Backup triggered successfully (HTTP ${startResponse.statusCode()})")
    else -> error("Failed to trigger backup (HTTP ${startResponse.statusCode()}): ${startResponse.body()}")
}

println("\nMonitoring backup status (polling every ${pollIntervalSeconds}s, max wait: ${maxWaitMinutes}min)...")

val startTime: Long = System.currentTimeMillis()
val maxWaitMillis: Long = maxWaitMinutes * 60 * 1000
var backupCompleted = false
var pollCount = 0

while (!backupCompleted) {
    if (System.currentTimeMillis() - startTime > maxWaitMillis) {
        error("Backup did not complete within $maxWaitMinutes minutes")
    }

    Thread.sleep(pollIntervalSeconds * 1000)
    pollCount++

    if (pollCount % 6 == 0) {
        val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
        println(" (${elapsedSeconds}s elapsed)")
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

    val inProgress = json["backupInProgress"]?.jsonPrimitive?.boolean ?: true
    val backupCancelled = json["backupCancelled"]?.jsonPrimitive?.boolean ?: false
    val backupErrorElement = json["backupError"]
    val backupError = if (backupErrorElement is JsonObject) {
        backupErrorElement["errorMessage"]?.jsonPrimitive?.contentOrNull
    } else {
        null
    }

    when {
        backupCancelled -> error("Backup was cancelled")
        backupError != null -> error("Backup failed with error: $backupError")
        !inProgress -> {
            backupCompleted = true
            val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
            println("\n✓ Backup completed successfully after ${elapsedSeconds}s")
        }
    }
}

println("\nBackup process finished.")
