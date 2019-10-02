package no.nav.helse.spade.behandlinger

data class BehandlingDto(val behandlingsId: String,
                         val aktorId: String,
                         val fom: String,
                         val tom: String,
                         val vurderinstidspunkt: String)
