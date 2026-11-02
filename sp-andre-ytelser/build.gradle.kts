plugins {
    id("no.nav.sykepenger.deployable")
}

sykepengerDeployable {
    mainClass = "no.nav.helse.sykepenger.andreytelser.AppKt"
}

dependencies {
    implementation(libs.rapids.and.rivers)
    implementation(libs.sykepengerLibs.logging)

    testImplementation(libs.tbd.libs.rapids.and.rivers.test)
    testImplementation(libs.testcontainers.kafka)
}
