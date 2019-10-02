package no.nav.helse.spade.behandlinger

data class BehandlingSummary(val behandlingsId: String,
                             val aktorId: String,
                             val fom: String,
                             val tom: String,
                             val vurderingstidspunkt: String)
