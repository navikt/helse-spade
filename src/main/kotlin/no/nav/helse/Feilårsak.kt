package no.nav.helse

sealed class Feilårsak {
    object IkkeFunnet: Feilårsak()
    object MidlertidigUtilgjengelig: Feilårsak()
    object UkjentFeil: Feilårsak()
}

