package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.testing.assertions.assertJsonEquals
import no.nav.sykepenger.libs.testing.testdata.feb
import no.nav.sykepenger.libs.testing.testdata.jan
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagNavIdent
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate
import kotlin.test.assertEquals

internal class GraderteAndreYtelserBehovRiverTest {
    private val transaksjonProvider = InMemoryTransaksjonProvider()
    private val rapid = TestRapid().also { GraderteAndreYtelserBehovRiver(it, transaksjonProvider) }
    private val identitetsnummer = Identitetsnummer(lagIdentitetsnummer())
    private val saksbehandlerIdent = NavIdent(lagNavIdent())

    @Test
    fun `svarer med dagene i perioden for hver yrkesaktivitet og beholder resten av meldingen`() {
        val organisasjonsnummer = lagOrganisasjonsnummer()
        val arbeidstaker =
            lagre(
                yrkesaktivitet = Yrkesaktivitetstype.ARBEIDSTAKER,
                organisasjonsnummer = organisasjonsnummer,
                dager = listOf(dag(10 jan 2024, mapOf(GraderteAndreYtelserType.PLEIEPENGER to 50), sykdomsgrad = 30, arbeidsgrad = 20)),
            )
        val frilans =
            lagre(
                yrkesaktivitet = Yrkesaktivitetstype.FRILANS,
                organisasjonsnummer = null,
                dager = listOf(dag(10 jan 2024, mapOf(GraderteAndreYtelserType.SVANGERSKAPSPENGER to 40), sykdomsgrad = 60)),
            )

        rapid.sendTestMessage(behov(fom = 1 jan 2024, tom = 31 jan 2024))

        assertEquals(1, rapid.inspektør.size)
        assertEquals(identitetsnummer.value, rapid.inspektør.key(0))
        assertJsonEquals(
            """
            {
              "@event_name": "behov",
              "@behov": ["GraderteAndreYtelserForBeregning"],
              "@behovId": "behov-123",
              "fødselsnummer": "${identitetsnummer.value}",
              "behandlingId": "behandling-123",
              "GraderteAndreYtelserForBeregning": {
                "fom": "2024-01-01",
                "tom": "2024-01-31"
              },
              "@løsning": {
                "GraderteAndreYtelserForBeregning": [
                  {
                    "yrkesaktivitetstype": "ARBEIDSTAKER",
                    "organisasjonsnummer": "$organisasjonsnummer",
                    "versjon": "${arbeidstaker.versjon}",
                    "dager": [
                      {
                        "dato": "2024-01-10", "trekkFraSykdomsgrad": 50, "sykdomsgradFørFordeling": 80,
                        "andreYtelserGrader": { "PLEIEPENGER": 50 }, "sykdomsgrad": 30, "arbeidsgrad": 20
                      }
                    ]
                  },
                  {
                    "yrkesaktivitetstype": "FRILANS",
                    "organisasjonsnummer": null,
                    "versjon": "${frilans.versjon}",
                    "dager": [
                      {
                        "dato": "2024-01-10", "trekkFraSykdomsgrad": 40, "sykdomsgradFørFordeling": 100,
                        "andreYtelserGrader": { "SVANGERSKAPSPENGER": 40 }, "sykdomsgrad": 60, "arbeidsgrad": 0
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
            publisertUtenFelterFraRapidsAndRivers(),
        )
    }

    @Test
    fun `tar bare med dagene i perioden`() {
        lagre(
            dager =
                listOf(
                    dag(1 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 50)),
                    dag(2 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 80), sykdomsgrad = 20),
                    dag(3 jan 2024, mapOf(GraderteAndreYtelserType.OMSORGSPENGER to 20)),
                ),
        )

        rapid.sendTestMessage(behov(fom = 2 jan 2024, tom = 2 jan 2024))

        val dager = løsning()[0]["dager"]
        assertEquals(listOf("2024-01-02"), dager.values().map { it["dato"].asString() })
        assertEquals(80, dager[0]["andreYtelserGrader"]["FORELDREPENGER"].asInt())
    }

    @Test
    fun `svarer med tilstanden etter siste endring`() {
        lagre(
            dager =
                listOf(
                    dag(1 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 50)),
                    dag(2 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 50)),
                ),
            endringer =
                listOf(
                    listOf(dag(2 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 30), sykdomsgrad = 70)),
                    listOf(dag(2 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 10), sykdomsgrad = 90)),
                ),
        )

        rapid.sendTestMessage(behov(fom = 1 jan 2024, tom = 31 jan 2024))

        val løsning = løsning()[0]
        assertEquals(listOf(50, 10), løsning["dager"].values().map { it["andreYtelserGrader"]["FORELDREPENGER"].asInt() })
        assertEquals(90, løsning["dager"][1]["sykdomsgrad"].asInt())
    }

    @Test
    fun `versjonen er den siste endringen, også når den gjelder dager utenfor perioden`() {
        val graderteAndreYtelser =
            GraderteAndreYtelser.ny(
                id = GraderteAndreYtelserId(identitetsnummer, Yrkesaktivitetstype.ARBEIDSTAKER, lagOrganisasjonsnummer()),
                dager = listOf(dag(10 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 50))),
                saksbehandlerIdent = saksbehandlerIdent,
            )
        val sisteEndring =
            graderteAndreYtelser.endre(
                listOf(dag(10 feb 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 20), sykdomsgrad = 80)),
                saksbehandlerIdent,
            )
        transaksjonProvider.graderteAndreYtelser.lagre(graderteAndreYtelser)

        rapid.sendTestMessage(behov(fom = 1 jan 2024, tom = 31 jan 2024))

        assertEquals(sisteEndring!!.versjon.toString(), løsning()[0]["versjon"].asString())
    }

    @Test
    fun `ytelser tatt fra arbeidet gir ikke trekk`() {
        lagre(dager = listOf(dag(10 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 40), sykdomsgrad = 50, arbeidsgrad = 10, sykdomsgradFørFordeling = 50)))

        rapid.sendTestMessage(behov(fom = 1 jan 2024, tom = 31 jan 2024))

        val dag = løsning()[0]["dager"][0]
        assertEquals(0, dag["trekkFraSykdomsgrad"].asInt())
        assertEquals(50, dag["sykdomsgradFørFordeling"].asInt())
    }

    @Test
    fun `ytelser tatt fra både sykdom og arbeid gir trekk for delen fra sykdommen`() {
        lagre(dager = listOf(dag(10 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 60), sykdomsgrad = 30, arbeidsgrad = 10, sykdomsgradFørFordeling = 50)))

        rapid.sendTestMessage(behov(fom = 1 jan 2024, tom = 31 jan 2024))

        assertEquals(20, løsning()[0]["dager"][0]["trekkFraSykdomsgrad"].asInt())
    }

    @Test
    fun `dag der ytelsene er fjernet gir ikke trekk`() {
        lagre(
            dager = listOf(dag(10 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 30), sykdomsgrad = 50, sykdomsgradFørFordeling = 80, arbeidsgrad = 20)),
            endringer = listOf(listOf(dag(10 jan 2024, emptyMap(), sykdomsgrad = 80, arbeidsgrad = 20))),
        )

        rapid.sendTestMessage(behov(fom = 1 jan 2024, tom = 31 jan 2024))

        val dag = løsning()[0]["dager"][0]
        assertEquals(0, dag["trekkFraSykdomsgrad"].asInt())
        assertEquals(80, dag["sykdomsgradFørFordeling"].asInt())
    }

    @Test
    fun `svarer med tom liste når ingen dager er i perioden`() {
        lagre(dager = listOf(dag(1 feb 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 50))))

        rapid.sendTestMessage(behov(fom = 1 jan 2024, tom = 31 jan 2024))

        assertEquals(0, løsning().size())
    }

    @Test
    fun `svarer med tom liste når personen ikke har graderte andre ytelser`() {
        rapid.sendTestMessage(behov(fom = 1 jan 2024, tom = 31 jan 2024))

        assertEquals(0, løsning().size())
    }

    @Test
    fun `svarer med tom liste når fom er etter tom`() {
        lagre(dager = listOf(dag(15 jan 2024, mapOf(GraderteAndreYtelserType.FORELDREPENGER to 50))))

        rapid.sendTestMessage(behov(fom = 31 jan 2024, tom = 1 jan 2024))

        assertEquals(0, løsning().size())
    }

    @Test
    fun `svarer ikke på behov som allerede er løst`() {
        rapid.sendTestMessage(behov(fom = 1 jan 2024, tom = 31 jan 2024, medLøsning = true))

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `svarer ikke på behov uten tom`() {
        rapid.sendTestMessage(behov(fom = 1 jan 2024, tom = null))

        assertEquals(0, rapid.inspektør.size)
    }

    private fun løsning() = rapid.inspektør.message(0)["@løsning"]["GraderteAndreYtelserForBeregning"]

    private fun publisertUtenFelterFraRapidsAndRivers(): String {
        val melding = rapid.inspektør.message(0).deepCopy() as ObjectNode
        listOf("@id", "@opprettet", "@forårsaket_av", "system_read_count", "system_participating_services")
            .forEach { melding.remove(it) }
        return melding.toString()
    }

    private fun lagre(
        dager: List<GraderteAndreYtelserDag>,
        endringer: List<List<GraderteAndreYtelserDag>> = emptyList(),
        yrkesaktivitet: Yrkesaktivitetstype = Yrkesaktivitetstype.ARBEIDSTAKER,
        organisasjonsnummer: String? = lagOrganisasjonsnummer(),
    ): GraderteAndreYtelser {
        val graderteAndreYtelser =
            GraderteAndreYtelser.ny(
                id = GraderteAndreYtelserId(identitetsnummer, yrkesaktivitet, organisasjonsnummer),
                dager = dager,
                saksbehandlerIdent = saksbehandlerIdent,
            )
        endringer.forEach { graderteAndreYtelser.endre(it, saksbehandlerIdent) }
        transaksjonProvider.graderteAndreYtelser.lagre(graderteAndreYtelser)
        return graderteAndreYtelser
    }

    private fun dag(
        dato: LocalDate,
        andreYtelserGrader: Map<GraderteAndreYtelserType, Int>,
        sykdomsgrad: Int = 50,
        arbeidsgrad: Int = 0,
        sykdomsgradFørFordeling: Int = sykdomsgrad + andreYtelserGrader.values.sum(),
    ) = GraderteAndreYtelserDag(
        dato = dato,
        andreYtelserGrader = andreYtelserGrader,
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = arbeidsgrad,
        sykdomsgradFørFordeling = sykdomsgradFørFordeling,
        begrunnelse = "en begrunnelse",
    )

    private fun behov(
        fom: LocalDate,
        tom: LocalDate?,
        medLøsning: Boolean = false,
    ): String {
        val tomFelt = tom?.let { ", \"tom\": \"$it\"" } ?: ""
        val løsningFelt = if (medLøsning) ", \"@løsning\": { \"GraderteAndreYtelserForBeregning\": [] }" else ""
        return """
            {
              "@event_name": "behov",
              "@behov": ["GraderteAndreYtelserForBeregning"],
              "@behovId": "behov-123",
              "@id": "48d9f7b4-54d0-4be4-84b1-d950091d1c2f",
              "@opprettet": "2024-01-01T00:00:00",
              "fødselsnummer": "${identitetsnummer.value}",
              "behandlingId": "behandling-123",
              "GraderteAndreYtelserForBeregning": { "fom": "$fom"$tomFelt }$løsningFelt
            }
            """.trimIndent()
    }
}
