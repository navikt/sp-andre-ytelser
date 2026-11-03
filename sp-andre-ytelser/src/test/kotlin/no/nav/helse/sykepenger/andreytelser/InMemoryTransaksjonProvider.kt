package no.nav.helse.sykepenger.andreytelser

internal class InMemoryTransaksjonProvider(
    override val graderteAndreYtelser: InMemoryGraderteAndreYtelserRepository = InMemoryGraderteAndreYtelserRepository(),
    override val outbox: InMemoryOutbox = InMemoryOutbox(),
) : TransaksjonProvider<Transaksjonskontekst>,
    Transaksjonskontekst {
    override fun <T> transaksjon(block: (Transaksjonskontekst) -> T): T = block(this)
}
