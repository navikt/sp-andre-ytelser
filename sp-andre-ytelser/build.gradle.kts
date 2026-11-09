plugins {
    id("no.nav.sykepenger.deployable")
    // Brukes bare av OpenAPI-generatoren, som leser skjemaene fra kotlinx-serializerne. Selve JSON-en lages av Jackson.
    alias(libs.plugins.kotlin.serialization)
}

sykepengerDeployable {
    mainClass = "no.nav.helse.sykepenger.andreytelser.AppKt"
}

dependencies {
    implementation(libs.cloud.sql.postgres.socket.factory)
    implementation(libs.kotliquery)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.smiley4.ktor.openapi.tools)
    implementation(libs.rapids.and.rivers)
    implementation(libs.sykepengerLibs.ktor.auth)
    implementation(libs.sykepengerLibs.leader.election)
    implementation(libs.sykepengerLibs.logging)
    implementation(libs.sykepengerLibs.outbox)
    implementation(libs.sykepengerLibs.postgres)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.jackson3)
    implementation(libs.tbd.libs.access.token.provider.texas)
    implementation(libs.tbd.libs.person.pseudo.id)
    implementation(libs.tbd.libs.populasjonstilgangskontroll.provider.api)
    implementation(libs.tbd.libs.populasjonstilgangskontroll.provider.tilgangsmaskinen)
    implementation(project(":migreringer"))

    testImplementation(libs.sykepengerLibs.testing)
    testImplementation(libs.tbd.libs.rapids.and.rivers.test)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(testFixtures(libs.sykepengerLibs.postgres))
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mock.oauth2.server)
}
