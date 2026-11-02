package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.Config
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import no.nav.helse.rapids_rivers.RapidApplication

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
    RapidApplication
        .create(
            env = env,
            consumerProducerFactory = ConsumerProducerFactory(kafkaConfig),
            builder = {
                env["HTTP_PORT"]?.toInt()?.let(::withHttpPort)
            },
        ).start()
}
