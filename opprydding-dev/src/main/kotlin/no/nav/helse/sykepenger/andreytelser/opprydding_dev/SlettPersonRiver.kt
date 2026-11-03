package no.nav.helse.sykepenger.andreytelser.opprydding_dev

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import kotliquery.queryOf
import kotliquery.sessionOf
import org.intellij.lang.annotations.Language
import javax.sql.DataSource

internal class SlettPersonRiver(
    rapidsConnection: RapidsConnection,
    private val dataSource: DataSource,
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .apply {
                precondition { it.requireValue("@event_name", "slett_person") }
                validate {
                    it.requireKey("@id", "fødselsnummer")
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val fødselsnummer = packet["fødselsnummer"].asString()
        slettPerson(fødselsnummer)
        context.publish(fødselsnummer, lagPersonSlettet(fødselsnummer))
    }

    private fun slettPerson(fødselsnummer: String) {
        @Language("PostgreSQL")
        val slettEvents = "DELETE FROM graderte_andre_ytelser_events WHERE fødselsnummer = :fodselsnummer"

        @Language("PostgreSQL")
        val slettOutbox = "DELETE FROM outbox WHERE fødselsnummer = :fodselsnummer"
        sessionOf(dataSource).use { session ->
            session.transaction { transaksjon ->
                listOf(slettEvents, slettOutbox).forEach { sql ->
                    transaksjon.run(queryOf(sql, mapOf("fodselsnummer" to fødselsnummer)).asUpdate)
                }
            }
        }
    }

    @Language("JSON")
    private fun lagPersonSlettet(fødselsnummer: String): String =
        """
        {
            "@event_name": "person_slettet",
            "fødselsnummer": "$fødselsnummer"
        }
        """.trimIndent()
}
