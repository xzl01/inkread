package com.inkread.reader.core.parse

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * RSS / Atom 日期解析（纯 Kotlin + java.time，无 Android 依赖，可 JVM 单测）。
 *
 * - RSS 2.0 的 `pubDate`：RFC 822 / RFC 1123，如 `Tue, 03 Jun 2003 09:39:21 GMT`、
 *   `03 Jun 2003 09:39:21 +0800`；
 * - Atom 的 `published` / `updated`：ISO 8601 / RFC 3339，如 `2003-12-13T18:30:02Z`、
 *   `2003-12-13T18:30:02+08:00`、`2003-12-13T18:30:02.123Z`；
 * - RSS 1.0 的 `dc:date` 也走 ISO 8601。
 *
 * **健壮性约定**：任何无法解析的输入（非法时区偏移如 `+9900`、越界日期、超大数字、
 * 非法格式等）一律返回 null，**绝不向调用方抛异常**；单条坏日期不能导致整个订阅源
 * 同步失败（见 docs/QA_REPORT.md P2-02）。
 */
object DateParse {

    /** 时区偏移的合法上限（java.time 只允许 ±18:00）。 */
    private const val MAX_OFFSET_SECONDS = 18 * 3600

    private val RFC822 = Regex(
        """^(?:[A-Za-z]{2,9},?\s+)?(\d{1,2})\s+([A-Za-z]{3,9})\s+(\d{2,4})\s+""" +
            """(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*([+-]\d{4}|[A-Za-z]{1,5}))?\s*$""",
    )

    private val TRAILING_COMMENT = Regex("""\s*\([^)]*\)\s*$""")

    private val RFC822_OFFSET = Regex("""([+-])(\d{2})(\d{2})$""")

    private val ISO_OFFSET_WITHOUT_COLON = Regex("""([+-]\d{2})(\d{2})$""")

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    /** RFC 822 常见时区缩写对应的偏移（小时）。 */
    private val ZONE_ABBREVIATIONS = mapOf(
        "GMT" to 0, "UT" to 0, "UTC" to 0, "Z" to 0,
        "EST" to -5, "EDT" to -4,
        "CST" to -6, "CDT" to -5,
        "MST" to -7, "MDT" to -6,
        "PST" to -8, "PDT" to -7,
        "BST" to 1, "CET" to 1, "CEST" to 2,
        "JST" to 9, "KST" to 9, "AEST" to 10, "NZST" to 12,
    )

    /** 解析为 epoch 毫秒；无法识别时返回 null（任何情况下都不抛异常）。 */
    fun parse(input: String?): Long? {
        val raw = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            // 去掉尾部时区注释，例如 "Tue, 03 Jun 2003 09:39:21 GMT (UTC)"
            val cleaned = TRAILING_COMMENT.replace(raw, "").trim()
            parseIso8601(cleaned) ?: parseRfc822(cleaned)
        } catch (e: Exception) {
            // DateTimeException（非法时区 / 非法日期）、ArithmeticException（数值溢出）等
            // 一律视为「无法解析」，绝不冒泡给同步流程。
            null
        }
    }

    private fun parseIso8601(text: String): Long? = try {
        // 允许日期与时间之间用空格分隔
        val normalized = if (text.length > 10 && text[10] == ' ') {
            text.substring(0, 10) + "T" + text.substring(11)
        } else {
            text
        }
        // 兼容 "+0800"（无冒号）格式
        val iso = if (ISO_OFFSET_WITHOUT_COLON.containsMatchIn(normalized)) {
            normalized.replace(ISO_OFFSET_WITHOUT_COLON) { mr ->
                mr.value.substring(0, 3) + ":" + mr.value.substring(3)
            }
        } else {
            normalized
        }
        runCatching {
            OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant().toEpochMilli()
        }.getOrNull() ?: runCatching {
            LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull() ?: runCatching {
            LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
    } catch (e: Exception) {
        null
    }

    private fun parseRfc822(text: String): Long? {
        return try {
            val match = RFC822.matchEntire(text) ?: return null
            val (dayText, monthText, yearText, hourText, minuteText, secondText, zoneText) =
                match.destructured
            val month = MONTHS[monthText.take(3).lowercase()] ?: return null
            val day = dayText.toIntOrNull() ?: return null
            val hour = hourText.toIntOrNull() ?: return null
            val minute = minuteText.toIntOrNull() ?: return null
            val second = secondText.toIntOrNull() ?: 0
            val rawYear = yearText.toIntOrNull() ?: return null
            // RFC 822 两位年份：00~49 → 2000~2049，50~99 → 1950~1999
            val year = if (yearText.length == 2) {
                if (rawYear < 50) 2000 + rawYear else 1900 + rawYear
            } else {
                rawYear
            }
            val offsetSeconds = parseZoneOffsetSeconds(zoneText) ?: return null
            val local = runCatching {
                LocalDateTime.of(year, month, day, hour, minute, second)
            }.getOrNull() ?: return null
            // 时区换算也包一层：越界 / 溢出统一返回 null，绝不抛异常
            runCatching {
                local.toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
            }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析数字时区偏移为秒。
     *
     * 合法范围与 java.time 一致：±18:00 以内且分钟 0~59；`+9900` / `-2400` / `+2500`
     * 这类越界值返回 null（由调用方回退到 updated 字段或忽略该日期），绝不抛异常。
     * 时区缩写（GMT/EST/…）与未知缩写沿用原行为：已知取对应偏移，未知按 0（GMT）处理。
     */
    private fun parseZoneOffsetSeconds(zoneText: String): Int? {
        if (zoneText.isBlank()) return 0
        val numeric = RFC822_OFFSET.matchEntire(zoneText)
            ?: return (ZONE_ABBREVIATIONS[zoneText.uppercase()] ?: 0) * 3600
        val sign = if (numeric.groupValues[1] == "-") -1 else 1
        val hours = numeric.groupValues[2].toIntOrNull() ?: return null
        val minutes = numeric.groupValues[3].toIntOrNull() ?: return null
        if (hours > 18 || minutes > 59) return null
        if (hours == 18 && minutes != 0) return null
        val total = sign.toLong() * (hours.toLong() * 3600L + minutes.toLong() * 60L)
        if (total < -MAX_OFFSET_SECONDS.toLong() || total > MAX_OFFSET_SECONDS.toLong()) return null
        return total.toInt()
    }
}
