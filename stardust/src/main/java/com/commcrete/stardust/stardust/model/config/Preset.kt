package com.commcrete.stardust.stardust.model.config

data class Preset(
    val index: Int,
    var currentPreset: CurrentPreset? = null,
    val xcvrList: MutableList<Xcvr> = mutableListOf(),
    /** Satellite frequency leases computed for this preset (empty on legacy firmware). */
    var leases: List<Lease> = emptyList()
)
