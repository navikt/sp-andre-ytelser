package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import kotliquery.Row
import kotliquery.Session
import kotliquery.queryOf
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import org.intellij.lang.annotations.Language
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate

internal class PostgresGraderteAndreYtelserRepository(
    private val session: Session,
) : GraderteAndreYtelserRepository {
    override fun finnAlle(identitetsnummer: Identitetsnummer): List<GraderteAndreYtelser> {
        @Language("PostgreSQL")
        val sql = """
            SELECT * FROM graderte_andre_ytelser_events
            WHERE fødselsnummer = :fodselsnummer
            ORDER BY versjon
        """
        return session
            .run(queryOf(sql, mapOf("fodselsnummer" to identitetsnummer.value)).map { it.tilIdOgEvent() }.asList)
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .map { (id, events) -> GraderteAndreYtelser.fraLagring(id, events) }
    }

    override fun finn(id: GraderteAndreYtelserId): GraderteAndreYtelser? {
        @Language("PostgreSQL")
        val sql = """
            SELECT * FROM graderte_andre_ytelser_events
            WHERE fødselsnummer = :fodselsnummer
              AND yrkesaktivitetstype = :yrkesaktivitetstype
              AND organisasjonsnummer IS NOT DISTINCT FROM :organisasjonsnummer
            ORDER BY versjon
        """
        return session
            .run(
                queryOf(
                    sql,
                    mapOf(
                        "fodselsnummer" to id.identitetsnummer.value,
                        "yrkesaktivitetstype" to id.yrkesaktivitet.tilDb().name,
                        "organisasjonsnummer" to id.organisasjonsnummer,
                    ),
                ).map { it.tilIdOgEvent().second }.asList,
            ).takeUnless { it.isEmpty() }
            ?.let { events -> GraderteAndreYtelser.fraLagring(id, events) }
    }

    override fun lagre(graderteAndreYtelser: GraderteAndreYtelser) {
        @Language("PostgreSQL")
        val sql = """
            INSERT INTO graderte_andre_ytelser_events (
              versjon, fødselsnummer, yrkesaktivitetstype, organisasjonsnummer, tidspunkt,
              utført_av_saksbehandler_ident, notat_til_beslutter, data_json
            )
            VALUES (
              :versjon, :fodselsnummer, :yrkesaktivitetstype, :organisasjonsnummer, :tidspunkt,
              :utfort_av_saksbehandler_ident, :notat_til_beslutter, :data_json::jsonb
            )
            ON CONFLICT (versjon) DO NOTHING
        """
        val id = graderteAndreYtelser.id
        graderteAndreYtelser.events.forEach { event ->
            session.run(
                queryOf(
                    sql,
                    mapOf(
                        "versjon" to event.versjon,
                        "fodselsnummer" to id.identitetsnummer.value,
                        "yrkesaktivitetstype" to id.yrkesaktivitet.tilDb().name,
                        "organisasjonsnummer" to id.organisasjonsnummer,
                        "tidspunkt" to event.tidspunkt,
                        "utfort_av_saksbehandler_ident" to event.utførtAvSaksbehandlerIdent.value,
                        "notat_til_beslutter" to event.notatTilBeslutter,
                        "data_json" to objectMapper.writeValueAsString(DbEventData(event.dager.map { it.tilDb() })),
                    ),
                ).asUpdate,
            )
        }
    }

    private fun Row.tilIdOgEvent(): Pair<GraderteAndreYtelserId, GraderteAndreYtelserEvent> {
        val id =
            GraderteAndreYtelserId(
                identitetsnummer = Identitetsnummer(string("fødselsnummer")),
                yrkesaktivitet = DbYrkesaktivitetstype.valueOf(string("yrkesaktivitetstype")).tilDomene(),
                organisasjonsnummer = stringOrNull("organisasjonsnummer"),
            )
        val data = objectMapper.readValue<DbEventData>(string("data_json"))
        val event =
            GraderteAndreYtelserEvent(
                versjon = uuid("versjon"),
                tidspunkt = instant("tidspunkt"),
                utførtAvSaksbehandlerIdent = NavIdent(string("utført_av_saksbehandler_ident")),
                notatTilBeslutter = string("notat_til_beslutter"),
                dager = data.dager.map { it.tilDomene() },
            )
        return id to event
    }

    private fun GraderteAndreYtelserEndretDag.tilDb() =
        DbDag(
            dato = dato,
            andreYtelserGrader = andreYtelserGrader.mapKeys { it.key.tilDb() },
            sykdomsgrad = sykdomsgrad,
            arbeidsgrad = arbeidsgrad,
            fraAndreYtelserGrader = fraAndreYtelserGrader?.mapKeys { it.key.tilDb() },
            fraSykdomsgrad = fraSykdomsgrad,
            fraArbeidsgrad = fraArbeidsgrad,
        )

    private fun DbDag.tilDomene() =
        GraderteAndreYtelserEndretDag(
            dato = dato,
            andreYtelserGrader = andreYtelserGrader.mapKeys { it.key.tilDomene() },
            sykdomsgrad = sykdomsgrad,
            arbeidsgrad = arbeidsgrad,
            fraAndreYtelserGrader = fraAndreYtelserGrader?.mapKeys { it.key.tilDomene() },
            fraSykdomsgrad = fraSykdomsgrad,
            fraArbeidsgrad = fraArbeidsgrad,
        )

    private fun GraderteAndreYtelserType.tilDb() =
        when (this) {
            GraderteAndreYtelserType.FORELDREPENGER -> DbYtelsestype.FORELDREPENGER
            GraderteAndreYtelserType.SVANGERSKAPSPENGER -> DbYtelsestype.SVANGERSKAPSPENGER
            GraderteAndreYtelserType.OMSORGSPENGER -> DbYtelsestype.OMSORGSPENGER
            GraderteAndreYtelserType.PLEIEPENGER -> DbYtelsestype.PLEIEPENGER
            GraderteAndreYtelserType.OPPLARINGSPENGER -> DbYtelsestype.OPPLARINGSPENGER
        }

    private fun DbYtelsestype.tilDomene() =
        when (this) {
            DbYtelsestype.FORELDREPENGER -> GraderteAndreYtelserType.FORELDREPENGER
            DbYtelsestype.SVANGERSKAPSPENGER -> GraderteAndreYtelserType.SVANGERSKAPSPENGER
            DbYtelsestype.OMSORGSPENGER -> GraderteAndreYtelserType.OMSORGSPENGER
            DbYtelsestype.PLEIEPENGER -> GraderteAndreYtelserType.PLEIEPENGER
            DbYtelsestype.OPPLARINGSPENGER -> GraderteAndreYtelserType.OPPLARINGSPENGER
        }

    private fun Yrkesaktivitetstype.tilDb() =
        when (this) {
            Yrkesaktivitetstype.ARBEIDSTAKER -> DbYrkesaktivitetstype.ARBEIDSTAKER
            Yrkesaktivitetstype.FRILANS -> DbYrkesaktivitetstype.FRILANS
            Yrkesaktivitetstype.ARBEIDSLEDIG -> DbYrkesaktivitetstype.ARBEIDSLEDIG
            Yrkesaktivitetstype.SELVSTENDIG -> DbYrkesaktivitetstype.SELVSTENDIG
        }

    private fun DbYrkesaktivitetstype.tilDomene() =
        when (this) {
            DbYrkesaktivitetstype.ARBEIDSTAKER -> Yrkesaktivitetstype.ARBEIDSTAKER
            DbYrkesaktivitetstype.FRILANS -> Yrkesaktivitetstype.FRILANS
            DbYrkesaktivitetstype.ARBEIDSLEDIG -> Yrkesaktivitetstype.ARBEIDSLEDIG
            DbYrkesaktivitetstype.SELVSTENDIG -> Yrkesaktivitetstype.SELVSTENDIG
        }

    // Egne enums for det som lagres, så et navnebytte i domenet ikke ødelegger eksisterende rader
    private enum class DbYtelsestype { FORELDREPENGER, SVANGERSKAPSPENGER, OMSORGSPENGER, PLEIEPENGER, OPPLARINGSPENGER }

    private enum class DbYrkesaktivitetstype { ARBEIDSTAKER, FRILANS, ARBEIDSLEDIG, SELVSTENDIG }

    private data class DbEventData(
        val dager: List<DbDag>,
    )

    private data class DbDag(
        val dato: LocalDate,
        val andreYtelserGrader: Map<DbYtelsestype, Int>,
        val sykdomsgrad: Int,
        val arbeidsgrad: Int,
        val fraAndreYtelserGrader: Map<DbYtelsestype, Int>?,
        val fraSykdomsgrad: Int?,
        val fraArbeidsgrad: Int?,
    )

    private companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
