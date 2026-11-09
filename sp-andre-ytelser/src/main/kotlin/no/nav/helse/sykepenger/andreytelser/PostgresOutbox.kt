package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import kotliquery.Session
import no.nav.sykepenger.libs.outbox.Outbox as SykepengerLibsOutbox

internal class PostgresOutbox(
    private val session: Session,
) : Outbox {
    override fun leggTil(
        identitetsnummer: Identitetsnummer,
        melding: String,
    ) {
        SykepengerLibsOutbox.leggTil(
            connection = session.connection.underlying,
            fødselsnummer = identitetsnummer.value,
            melding = melding,
        )
    }
}
