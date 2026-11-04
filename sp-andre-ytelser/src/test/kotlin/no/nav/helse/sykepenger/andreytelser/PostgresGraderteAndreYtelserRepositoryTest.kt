package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import kotliquery.sessionOf
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.postgres.TestDatabase
import no.nav.sykepenger.libs.testing.testdata.jan
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagNavIdent
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class PostgresGraderteAndreYtelserRepositoryTest {
    private val testDatabase = TestDatabase.start().also { it.migrer() }
    private val saksbehandlerIdent = NavIdent(lagNavIdent())

    @BeforeEach
    fun tømDatabasen() {
        testDatabase.tøm("graderte_andre_ytelser_events")
    }

    @AfterAll
    fun stoppDatabasen() {
        testDatabase.stop()
    }

    @Test
    fun `kan lagre og hente graderte andre ytelser`() {
        val id = lagId()
        val dager =
            listOf(
                dag(1 jan 2024, mapOf(GraderteAndreYtelserType.PLEIEPENGER to 50)),
                dag(
                    2 jan 2024,
                    mapOf(GraderteAndreYtelserType.PLEIEPENGER to 40, GraderteAndreYtelserType.OMSORGSPENGER to 20),
                    sykdomsgrad = 40,
                    arbeidsgrad = 0,
                ),
            )
        val graderteAndreYtelser = ny(id, dager)

        medRepository { it.lagre(graderteAndreYtelser) }

        val hentet = medRepository { it.finn(id) }
        assertNotNull(hentet)
        assertEquals(id, hentet.id)
        assertEquals(dager, hentet.dager)
        assertEquals(graderteAndreYtelser.versjon, hentet.versjon)
        assertEquals(graderteAndreYtelser.events.map { it.dager }, hentet.events.map { it.dager })
        assertEquals("notat til beslutter", hentet.events.single().notatTilBeslutter)
        assertEquals(saksbehandlerIdent, hentet.events.single().utførtAvSaksbehandlerIdent)
        assertEquals(listOf(id), medRepository { it.finnAlle(id.identitetsnummer) }.map { it.id })
    }

    @Test
    fun `endringer lagres som nye events og spilles av i riktig rekkefølge`() {
        val id = lagId()
        medRepository { it.lagre(ny(id, listOf(dag(1 jan 2024), dag(2 jan 2024)))) }

        val hentet = medRepository { it.finn(id) }!!
        hentet.endre(listOf(dag(2 jan 2024, sykdomsgrad = 10)), saksbehandlerIdent, "endring 1")
        hentet.endre(listOf(dag(2 jan 2024, sykdomsgrad = 20), dag(3 jan 2024)), saksbehandlerIdent, "endring 2")
        medRepository { it.lagre(hentet) }

        val hentetIgjen = medRepository { it.finn(id) }
        assertNotNull(hentetIgjen)
        assertEquals(listOf(dag(1 jan 2024), dag(2 jan 2024, sykdomsgrad = 20), dag(3 jan 2024)), hentetIgjen.dager)
        assertEquals(hentet.events.map { it.versjon }, hentetIgjen.events.map { it.versjon })
        assertEquals(
            listOf(
                GraderteAndreYtelserEndretDag(
                    dato = 2 jan 2024,
                    andreYtelserGrader = mapOf(GraderteAndreYtelserType.FORELDREPENGER to 50),
                    sykdomsgrad = 10,
                    arbeidsgrad = 20,
                    sykdomsgradFørFordeling = 60,
                    fraAndreYtelserGrader = mapOf(GraderteAndreYtelserType.FORELDREPENGER to 50),
                    fraSykdomsgrad = 30,
                    fraArbeidsgrad = 20,
                    fraSykdomsgradFørFordeling = 80,
                ),
            ),
            hentetIgjen.events[1].dager,
        )
        assertEquals(3, testDatabase.antallRader("graderte_andre_ytelser_events"))
    }

    @Test
    fun `kan lagre og hente graderte andre ytelser uten organisasjonsnummer`() {
        val id = lagId(yrkesaktivitet = Yrkesaktivitetstype.FRILANS, organisasjonsnummer = null)
        medRepository { it.lagre(ny(id, listOf(dag(1 jan 2024)))) }

        val hentet = medRepository { it.finn(id) }

        assertNotNull(hentet)
        assertEquals(id, hentet.id)
        assertNull(hentet.id.organisasjonsnummer)
    }

    @Test
    fun `strømmene holdes adskilt per yrkesaktivitet og organisasjonsnummer`() {
        val identitetsnummer = Identitetsnummer(lagIdentitetsnummer())
        val arbeidsgiver1 = lagId(identitetsnummer, organisasjonsnummer = lagOrganisasjonsnummer())
        val arbeidsgiver2 = lagId(identitetsnummer, organisasjonsnummer = lagOrganisasjonsnummer())
        val frilans = lagId(identitetsnummer, Yrkesaktivitetstype.FRILANS, organisasjonsnummer = null)
        medRepository { repository ->
            repository.lagre(ny(arbeidsgiver1, listOf(dag(1 jan 2024, sykdomsgrad = 10))))
            repository.lagre(ny(arbeidsgiver2, listOf(dag(1 jan 2024, sykdomsgrad = 20))))
            repository.lagre(ny(frilans, listOf(dag(1 jan 2024, sykdomsgrad = 30))))
        }

        medRepository { repository ->
            assertEquals(listOf(dag(1 jan 2024, sykdomsgrad = 10)), repository.finn(arbeidsgiver1)?.dager)
            assertEquals(listOf(dag(1 jan 2024, sykdomsgrad = 20)), repository.finn(arbeidsgiver2)?.dager)
            assertEquals(listOf(dag(1 jan 2024, sykdomsgrad = 30)), repository.finn(frilans)?.dager)
            assertNull(repository.finn(lagId(identitetsnummer, Yrkesaktivitetstype.SELVSTENDIG, organisasjonsnummer = null)))
            assertEquals(
                setOf(arbeidsgiver1, arbeidsgiver2, frilans),
                repository.finnAlle(identitetsnummer).map { it.id }.toSet(),
            )
        }
    }

    @Test
    fun `lagre er idempotent for allerede lagrede events`() {
        val graderteAndreYtelser = ny(lagId(), listOf(dag(1 jan 2024)))

        medRepository { it.lagre(graderteAndreYtelser) }
        medRepository { it.lagre(graderteAndreYtelser) }

        assertEquals(1, testDatabase.antallRader("graderte_andre_ytelser_events"))
    }

    private fun <T> medRepository(block: (GraderteAndreYtelserRepository) -> T): T = sessionOf(testDatabase.dataSource).use { session -> block(PostgresGraderteAndreYtelserRepository(session)) }

    private fun lagId(
        identitetsnummer: Identitetsnummer = Identitetsnummer(lagIdentitetsnummer()),
        yrkesaktivitet: Yrkesaktivitetstype = Yrkesaktivitetstype.ARBEIDSTAKER,
        organisasjonsnummer: String? = lagOrganisasjonsnummer(),
    ) = GraderteAndreYtelserId(identitetsnummer, yrkesaktivitet, organisasjonsnummer)

    private fun ny(
        id: GraderteAndreYtelserId,
        dager: List<GraderteAndreYtelserDag>,
    ) = GraderteAndreYtelser.ny(
        id = id,
        dager = dager,
        saksbehandlerIdent = saksbehandlerIdent,
        notatTilBeslutter = "notat til beslutter",
    )

    private fun dag(
        dato: LocalDate,
        andreYtelserGrader: Map<GraderteAndreYtelserType, Grad> = mapOf(GraderteAndreYtelserType.FORELDREPENGER to 50),
        sykdomsgrad: Grad = 30,
        arbeidsgrad: Grad = 20,
        sykdomsgradFørFordeling: Grad = sykdomsgrad + andreYtelserGrader.values.sum(),
    ) = GraderteAndreYtelserDag(dato, andreYtelserGrader, sykdomsgrad, arbeidsgrad, sykdomsgradFørFordeling)
}
