package com.nox.offline.updates

import com.nox.offline.downloader.HttpDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Скорость передачи одного и того же опубликованного APK: путь 0.4.2 (один вызов
 * HttpDownloader) против UpdateTransfer 0.4.3 — тот же клиент, та же сеть, попеременно.
 * Только по запросу: NOX_UPDATE_BENCH_URL, NOX_UPDATE_BENCH_SIZE, NOX_UPDATE_BENCH_SHA256.
 */
class UpdateThroughputTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun env(k: String): String? = System.getenv(k)?.takeIf { it.isNotBlank() }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun `old and new path on the same published file`() = runBlocking {
        val url = env("NOX_UPDATE_BENCH_URL")
        assumeTrue("замер только по запросу (NOX_UPDATE_BENCH_URL)", url != null)
        val size = env("NOX_UPDATE_BENCH_SIZE")!!.toLong()
        val sha = env("NOX_UPDATE_BENCH_SHA256")!!
        val client = HttpDownloader.defaultClient()
        val lines = mutableListOf<String>()
        repeat(3) { round ->
            for (path in listOf("0.4.2", "0.4.3")) {
                val part = File(tmp.root, "r$round-$path.part").apply { delete() }
                val t0 = System.nanoTime()
                if (path == "0.4.2") {
                    val out = HttpDownloader(client).download(url!!, mapOf("Accept" to "application/vnd.android.package-archive"), part) { _, _ -> }
                    assertEquals(HttpDownloader.Outcome.Completed::class, out::class)
                } else {
                    val r = UpdateTransfer(client).run(url!!, part, size, refresh = { UpdateTransfer.Refresh.Same(url) }) { }
                    assertEquals(UpdateTransfer.Result.Done, r)
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertEquals(size, part.length())
                assertEquals(sha, sha256(part))
                lines += String.format(Locale.US, "круг %d, %s: %d байт за %d мс — %.1f МБ/с",
                    round + 1, path, size, ms, size / 1_048_576.0 / (ms / 1000.0))
                part.delete()
            }
        }
        lines.forEach(::println)
        File(System.getProperty("java.io.tmpdir"), "nox-update-throughput.txt").writeText(lines.joinToString("\n") + "\n")
    }
}
