package app.duongondro.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/*
 * Day keys are `java.time.LocalDate`: the ISO calendar, which is the proleptic
 * Gregorian one. Always built from an instant in an explicit zone, never from
 * a formatter with a week-based year (`YYYY` stamps 29 December 2025 as 2026)
 * and never from the device's default zone implicitly.
 */

/** The civil date of `instant` in `zone`. */
fun civilDate(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

/**
 * The first instant of `this` date plus `offset` days in `zone`. Where local
 * midnight does not exist (a DST jump), the first instant that does.
 */
fun LocalDate.startOfDay(zone: ZoneId, offset: Long = 0): Instant =
    plusDays(offset).atStartOfDay(zone).toInstant()

/** Parses `YYYY-MM-DD`, or null. */
fun parseCivilDate(s: String): LocalDate? =
    try { LocalDate.parse(s) } catch (_: DateTimeParseException) { null }
