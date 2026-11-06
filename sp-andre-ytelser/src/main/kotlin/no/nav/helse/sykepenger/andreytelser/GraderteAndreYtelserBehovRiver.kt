package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.JsonNode
import java.time.LocalDate

internal class GraderteAndreYtelserBehovRiver(
    rapidsConnection: RapidsConnection,
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
) : River.PacketListener {
    private val behov = "GraderteAndreYtelserForBeregning"

    init {
        River(rapidsConnection)
            .apply {
                precondition {
                    it.requireValue("@event_name", "behov")
                    it.requireAll("@behov", listOf(behov))
                    it.forbid("@løsning")
                }
                validate {
                    it.requireKey("@id", "fødselsnummer")
                    it.require("$behov.fom", JsonNode::asLocalDate)
                    it.require("$behov.tom", JsonNode::asLocalDate)
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
        val periode = packet["$behov.fom"].asLocalDate()..packet["$behov.tom"].asLocalDate()

        medMdc(MdcKey.IDENTITETSNUMMER to fødselsnummer) {
            val periodedetaljer = arrayOf("fom" to periode.start.toString(), "tom" to periode.endInclusive.toString())
            loggInfo("Mottatt behov for $behov", *periodedetaljer)
            try {
                val løsning = løsning(Identitetsnummer(fødselsnummer), periode)
                packet["@løsning"] = mapOf(behov to løsning)
                loggInfo(
                    "Publiserer løsning på $behov",
                    *periodedetaljer,
                    "antallYrkesaktiviteter" to løsning.size.toString(),
                )
                context.publish(fødselsnummer, packet.toJson())
            } catch (ex: Exception) {
                loggError("Feil under håndtering av behov $behov", ex, *periodedetaljer)
            }
        }
    }

    private fun løsning(
        identitetsnummer: Identitetsnummer,
        periode: ClosedRange<LocalDate>,
    ): List<Map<String, Any?>> =
        transaksjonProvider
            .transaksjon { it.graderteAndreYtelser.finnAlle(identitetsnummer) }
            .map { ytelser -> ytelser to ytelser.dager.filter { it.dato in periode } }
            .filter { (_, dager) -> dager.isNotEmpty() }
            .map { (ytelser, dager) ->
                mapOf(
                    "yrkesaktivitetstype" to ytelser.id.yrkesaktivitet.name,
                    "organisasjonsnummer" to ytelser.id.organisasjonsnummer,
                    "versjon" to ytelser.versjon,
                    "dager" to
                        dager.map { dag ->
                            mapOf(
                                "dato" to dag.dato,
                                "trekkFraSykdomsgrad" to dag.trekkFraSykdomsgrad,
                                "sykdomsgradFørFordeling" to dag.sykdomsgradFørFordeling,
                                "andreYtelserGrader" to dag.andreYtelserGrader.mapKeys { (type, _) -> type.name },
                                "sykdomsgrad" to dag.sykdomsgrad,
                                "arbeidsgrad" to dag.arbeidsgrad,
                            )
                        },
                )
            }
}
