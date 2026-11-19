package com.justdataplease.boringtravel.world

import com.justdataplease.boringtravel.time.LocalDate
import java.time.ZoneId

/** The Android app's WorldModelTest (read in as it is) passes a java.time zone; here WorldModel takes its id. */
fun WorldModel.build(input: WorldInput, today: LocalDate, zone: ZoneId, nowMs: Long, codeAt: (Double, Double) -> String?): World =
    build(input, today, zone.id, nowMs, codeAt)
