plugins {
    id("no.nav.sykepenger.deployable")
}

sykepengerDeployable {
    mainClass = "no.nav.helse.sykepenger.andreytelser.AppKt"
}

dependencies {
    implementation(libs.cloud.sql.postgres.socket.factory)
    implementation(libs.rapids.and.rivers)
    implementation(libs.sykepengerLibs.logging)
    implementation(libs.sykepengerLibs.postgres)
    implementation(project(":migreringer"))

    testImplementation(libs.tbd.libs.rapids.and.rivers.test)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(testFixtures(libs.sykepengerLibs.postgres))
}
