package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.Config
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import io.ktor.server.application.ApplicationStopped
import no.nav.helse.rapids_rivers.RapidApplication
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.navngittLogger
import no.nav.sykepenger.libs.postgres.DatabaseConfig
import no.nav.sykepenger.libs.postgres.dataSource
import no.nav.sykepenger.libs.postgres.migrerSynkront

private val logger = navngittLogger("no.nav.helse.sykepenger.andreytelser.App")

fun main() {
    launchApplication(
        env = System.getenv(),
        kafkaConfig = AivenConfig.default,
    )
}

internal fun launchApplication(
    env: Map<String, String>,
    kafkaConfig: Config,
) {
    val databaseConfig = DatabaseConfig.fraEnv(env)
    logger.info("Migrerer database")
    migrerSynkront(databaseConfig)
    logger.info("Migrering ferdig")
    val dataSource = databaseConfig.dataSource()

    RapidApplication
        .create(
            env = env,
            consumerProducerFactory = ConsumerProducerFactory(kafkaConfig),
            builder = {
                env["HTTP_PORT"]?.toInt()?.let(::withHttpPort)
                withKtorModule {
                    monitor.subscribe(ApplicationStopped) {
                        loggInfo("Lukker datasource")
                        dataSource.close()
                    }
                }
            },
        ).start()
}
