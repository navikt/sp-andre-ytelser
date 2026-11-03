package no.nav.helse.sykepenger.andreytelser.rammeverk.serialization

import kotlinx.serialization.modules.SerializersModule
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// Brukes bare av OpenAPI-generatoren. JSON på routene lages av Jackson.
internal val customSerializersModule =
    SerializersModule {
        contextual(LocalDate::class, LocalDateIsoSerializer)
        contextual(LocalDateTime::class, LocalDateTimeIsoSerializer)
        contextual(UUID::class, UUIDStringSerializer)
    }
