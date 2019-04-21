package no.nav.helse

import arrow.core.*
import io.ktor.application.ApplicationCall
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond

data class FeilResponse(val feilmelding: String)
data class HttpFeil(val status: HttpStatusCode, val feilmelding: String)

suspend fun ApplicationCall.respondFeil(feil: HttpFeil) = respond(feil.status, FeilResponse(feil.feilmelding))

suspend fun <B: Any> Either<Feilårsak, B>.respond(call: ApplicationCall) = this.fold(
        { left -> call.respondFeil(left.toHttpFeil()) },
        { right -> call.respond(right) }
)

fun Feilårsak.toHttpFeil() = when (this) {
    is Feilårsak.IkkeFunnet -> HttpFeil(HttpStatusCode.NotFound, "Resource not found")
    is Feilårsak.MidlertidigUtilgjengelig -> HttpFeil(HttpStatusCode.ServiceUnavailable, "Service is unavailable at the momement")
    is Feilårsak.UkjentFeil -> HttpFeil(HttpStatusCode.InternalServerError, "Unknown error")
}
