# sp-andre-ytelser

Eier graderte andre ytelser for sykepenger. Appen tar over denne funksjonaliteten fra Spesialist, og Speil kaller den direkte.

Bygg og kjør testene:

```shell
./gradlew build -x addKtlintFormatGitPreCommitHook
```

Testene bruker Testcontainers og trenger Docker.

## Kjøre lokalt

Kjør `main` i `LocalApp.kt` (under `src/test`) fra IntelliJ. Appen starter på port 8484 med data i minnet, uten
innlogging og tilgangskontroll:

- OpenAPI: http://localhost:8484/api/openapi.json (Speil genererer klienten sin herfra med orval)
- Swagger UI: http://localhost:8484/api/swagger

## Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

### For Nav-ansatte

Interne henvendelser kan sendes via Slack i
kanalen [#sykepenger-værsågod](https://nav-it.slack.com/archives/C019637N90X).

## Kode generert av GitHub Copilot

Dette repoet bruker GitHub Copilot til å generere kode.
