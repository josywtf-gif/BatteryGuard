package com.example.batteryguard

import java.io.File

/** Reads CPU thermal zones, preferring CPU-cluster sensors over broad SoC/package sensors. */
object CpuTemp {
    private data class Candidate(val path: String, val score: Int)
    @Volatile private var cached: List<Candidate> = emptyList()
    @Volatile private var lastDiscoveryMs: Long = 0L

    fun read(): Float? {
        val now = android.os.SystemClock.elapsedRealtime()
        var candidates = cached
        if (candidates.isEmpty() || now - lastDiscoveryMs > 10 * 60 * 1000L || candidates.none { File(it.path).canRead() }) {
            candidates = discover()
            cached = candidates
            lastDiscoveryMs = now
        }
        // Prefer CPU-specific zones. If several cores/clusters exist, report the hottest
        // valid CPU sensor; fall back to broader SoC sensors only when CPU zones fail.
        for (score in candidates.map { it.score }.distinct().sortedDescending()) {
            val values = candidates.asSequence().filter { it.score == score }
                .mapNotNull { readTemp(it.path) }.toList()
            if (values.isNotEmpty()) return values.maxOrNull()
        }
        return null
    }

    private fun readTemp(path: String): Float? {
        return try {
            val raw = File(path).readText().trim().toFloatOrNull() ?: return null
            val c = when {
                raw >= 1000f -> raw / 1000f
                raw > 150f -> return null
                else -> raw
            }
            c.takeIf { it in 10f..115f }
        } catch (_: Exception) { null }
    }

    private fun score(name: String): Int {
        val n = name.trim().lowercase()
        if (listOf("batt", "charger", "usb", "gpu", "modem", "wifi", "pa-", "quiet", "skin", "chg", "camera", "display").any { n.contains(it) }) return -100
        return when {
            listOf("cpu-thermal", "cpu_thermal", "cpu-therm", "cpu_therm", "cpuss", "cpu cluster", "cpu-cluster").any { n.contains(it) } -> 100
            listOf("cpu", "apc", "big", "little", "mid", "cluster", "core", "k10temp", "coretemp").any { n.contains(it) } -> 80
            listOf("soc", "tsens", "pkg_temp", "package").any { n.contains(it) } -> 50
            else -> -100
        }
    }

    private fun discover(): List<Candidate> {
        val found = LinkedHashMap<String, Candidate>()
        fun add(path: String, name: String) {
            val score = score(name)
            if (score >= 0 && readTemp(path) != null) found[path] = Candidate(path, score)
        }
        try {
            File("/sys/class/thermal").listFiles()?.forEach { dir ->
                if (dir.name.startsWith("thermal_zone")) {
                    val type = try { File(dir, "type").readText() } catch (_: Exception) { "" }
                    add(File(dir, "temp").path, type)
                }
            }
        } catch (_: Exception) { }
        try {
            File("/sys/class/hwmon").listFiles()?.forEach { dir ->
                val name = try { File(dir, "name").readText() } catch (_: Exception) { "" }
                dir.listFiles()?.filter { it.name.matches(Regex("temp\\d+_input")) }?.forEach { add(it.path, name) }
            }
        } catch (_: Exception) { }
        return found.values.sortedByDescending { it.score }
    }
}
