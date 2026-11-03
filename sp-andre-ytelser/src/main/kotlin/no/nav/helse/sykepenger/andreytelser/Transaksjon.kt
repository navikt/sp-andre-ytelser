package no.nav.helse.sykepenger.andreytelser

internal interface Transaksjonskontekst {
    val graderteAndreYtelser: GraderteAndreYtelserRepository
    val outbox: Outbox
}

internal interface TransaksjonProvider<TRANSAKSJON> {
    fun <T> transaksjon(block: (TRANSAKSJON) -> T): T
}
