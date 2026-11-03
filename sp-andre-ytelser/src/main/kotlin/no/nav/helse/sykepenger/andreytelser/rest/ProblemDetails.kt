package no.nav.helse.sykepenger.andreytelser.rest

import com.fasterxml.jackson.annotation.JsonInclude
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import tools.jackson.module.kotlin.jacksonObjectMapper

@Serializable
internal data class ProblemDetails(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val code: String? = null,
)

internal suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
    code: String? = null,
) {
    respondText(
        problemMapper.writeValueAsString(ProblemDetails(title = title, status = status.value, instance = request.path(), code = code)),
        ContentType.Application.ProblemJson,
        status,
    )
}

private val problemMapper = jacksonObjectMapper()
