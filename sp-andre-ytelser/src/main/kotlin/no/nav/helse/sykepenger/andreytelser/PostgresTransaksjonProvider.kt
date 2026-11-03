package no.nav.helse.sykepenger.andreytelser

import kotliquery.Session
import kotliquery.sessionOf
import javax.sql.DataSource

internal class PostgresTransaksjonProvider(
    private val dataSource: DataSource,
) : TransaksjonProvider<Transaksjonskontekst> {
    override fun <T> transaksjon(block: (Transaksjonskontekst) -> T): T =
        sessionOf(dataSource).use { session ->
            session.transaction { transaksjon -> block(PostgresTransaksjonskontekst(transaksjon)) }
        }
}

private class PostgresTransaksjonskontekst(
    session: Session,
) : Transaksjonskontekst {
    override val graderteAndreYtelser = PostgresGraderteAndreYtelserRepository(session)
    override val outbox = PostgresOutbox(session)
}
