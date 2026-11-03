package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.access_token.TexasClient
import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.Config
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.personpseudoid.ValkeyPersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.client.PopulasjonstilgangConfig
import com.github.navikt.tbd_libs.populasjonstilgang.client.tilgangsmaskinenClient
import io.ktor.server.application.ApplicationStopped
import no.nav.helse.rapids_rivers.RapidApplication
import no.nav.helse.sykepenger.andreytelser.rest.configureRestApi
import no.nav.helse.sykepenger.andreytelser.rest.graderteAndreYtelserApi
import no.nav.helse.sykepenger.andreytelser.rest.saksbehandlerhandlingVisningApi
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.navngittLogger
import no.nav.sykepenger.libs.postgres.DatabaseConfig
import no.nav.sykepenger.libs.postgres.dataSource
import no.nav.sykepenger.libs.postgres.migrerSynkront

private val logger = navngittLogger("no.nav.helse.sykepenger.andreytelser.App")

fun main() {
    val env = System.getenv()
    launchApplication(
        env = env,
        kafkaConfig = AivenConfig.default,
        personProvider = ValkeyPersonPseudoIdProvider.fraEnv("personpseudoid", env),
        tilgangskontroll = PopulasjonstilgangConfig.fraEnv(env).tilgangsmaskinenClient(TexasClient.fromEnv()),
    )
}

internal fun launchApplication(
    env: Map<String, String>,
    kafkaConfig: Config,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
) {
    val databaseConfig = DatabaseConfig.fraEnv(env)
    logger.info("Migrerer database")
    migrerSynkront(databaseConfig)
    logger.info("Migrering ferdig")
    val dataSource = databaseConfig.dataSource()
    val transaksjonProvider = PostgresTransaksjonProvider(dataSource)

    RapidApplication
        .create(
            env = env,
            consumerProducerFactory = ConsumerProducerFactory(kafkaConfig),
            builder = {
                env["HTTP_PORT"]?.toInt()?.let(::withHttpPort)
                withKtorModule {
                    configureJwtAuthentication(AzureAdConfig.fraEnv(env), TilgangsgrupperTilTilganger.fraEnv(env))
                    configureRestApi(personProvider, tilgangskontroll) { personTilgang ->
                        graderteAndreYtelserApi(transaksjonProvider, personTilgang)
                        saksbehandlerhandlingVisningApi(transaksjonProvider, personTilgang)
                    }
                    monitor.subscribe(ApplicationStopped) {
                        loggInfo("Lukker datasource")
                        dataSource.close()
                    }
                }
            },
        ).apply {
            register(OutboxPubliseringsjobb(this, transaksjonProvider))
        }.start()
}
