package com.commcrete.stardust.stardust.model.config

import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LimitationType
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.ConfigurationUtils

/** Firmware from which the config parse computes leases; below it, [Preset.leases] is always empty. */
private const val LEASES_MAJOR = 24
private const val LEASES_MINOR = 0
private const val LEASES_PATCH = 7

/**
 * Why a preset cannot be used. Named rather than boolean because the operator is told which one it
 * is: both are fixed in Commander, but they are fixed by doing different things there.
 */
enum class PresetProblem(
    /**
     * Nothing at all can be sent on the preset — text, voice, files, location, SOS. False means
     * only RD Auto is affected: an RD picked by hand still sends.
     */
    val blocksTransmission: Boolean,
) {
    /**
     * A required functionality is in no RD's selection — the operator's saved one, else the
     * preset's defaults — so RD Auto has no RD to route it to. See [unroutedFunctionalities].
     */
    MISSING_FUNCTIONALITY(blocksTransmission = false),

    /** No RD assigned with a usable bandwidth, so the preset owns no lease to transmit on. */
    NO_LEASE(blocksTransmission = true),

    /** Legacy firmware only: no transceiver on the preset has a frequency set. */
    NOT_CONFIGURED(blocksTransmission = true),
}

/**
 * The presets this radio cannot actually be switched to, each with the reason.
 *
 * The SDK's own [StardustConfigurationPackage.presetsWithoutConfig] answers only half the
 * question. It looks for missing FUNCTIONALITY options, and it looks only at transceivers that
 * have already been given a frequency — so a preset nobody configured AT ALL, with no RD assigned
 * and no frequency set, has nothing that could be missing and comes back clean. That is exactly
 * the preset the operator taps and nothing happens.
 *
 * The other half is the lease. A preset with no lease has no channel to transmit on whatever its
 * functionality bits say, and the leases are already computed during the config parse, so this
 * costs a list read.
 *
 * Legacy firmware (below [LEASES_MAJOR].[LEASES_MINOR].[LEASES_PATCH]) reports no leases, so each
 * generation runs its own check — see [leasePresetProblems] and [legacyPresetProblems].
 *
 * The dispatcher for the surfaces shared by both generations — the identity dialog's preset picker,
 * the active-preset warning on [com.commcrete.stardust.transport.ConnectionState.Ready] and the
 * host's send gates. Each carriers screen calls its own check directly.
 *
 * A preset failing both halves is reported by the half that blocks transmission: until that is
 * fixed nothing can be sent at all, so a missing default is not what the operator needs to hear.
 */
fun StardustConfigurationPackage.presetProblems(): Map<CurrentPreset, PresetProblem> =
    if (ConfigurationUtils.isFirmwareAtLeast(LEASES_MAJOR, LEASES_MINOR, LEASES_PATCH)) leasePresetProblems()
    else legacyPresetProblems()

/** Firmware with leases: no lease, else missing functionality. */
fun StardustConfigurationPackage.leasePresetProblems(): Map<CurrentPreset, PresetProblem> {
    val problems = linkedMapOf<CurrentPreset, PresetProblem>()
    presets.filter { it.leases.isEmpty() }.mapNotNull { it.currentPreset }.forEach {
        problems[it] = PresetProblem.NO_LEASE
    }
    missingFunctionality().forEach { (preset, problem) -> problems.putIfAbsent(preset, problem) }
    return problems
}

/**
 * Legacy firmware: no transceiver with a frequency — the preset nobody configured, which
 * [StardustConfigurationPackage.presetsWithoutConfig] passes; ST carries no RD — else missing
 * functionality.
 */
fun StardustConfigurationPackage.legacyPresetProblems(): Map<CurrentPreset, PresetProblem> {
    val problems = linkedMapOf<CurrentPreset, PresetProblem>()
    presets.filter { it.isUnconfigured() }
        .mapNotNull { it.currentPreset }
        .forEach { problems[it] = PresetProblem.NOT_CONFIGURED }
    missingFunctionality().forEach { (preset, problem) -> problems.putIfAbsent(preset, problem) }
    return problems
}

/** No transceiver that can carry an RD (ST cannot) has a frequency set. */
internal fun Preset.isUnconfigured(): Boolean =
    xcvrList.none { it.carrier.type != CarrierType.ST && !it.hasDefaultFrequency() }

private fun StardustConfigurationPackage.missingFunctionality(): Map<CurrentPreset, PresetProblem> =
    presets.filter { unroutedFunctionalities(it).isNotEmpty() }
        .mapNotNull { it.currentPreset }
        .associateWith { PresetProblem.MISSING_FUNCTIONALITY }

/**
 * The required functionalities RD Auto cannot send on [preset]: in no RD's selection. The selection
 * is the operator's saved one when there is one, else the preset's defaults — the same list
 * [CarriersUtils.getRadioToSend] routes from. Unlicensed functionalities are left out: no preset can
 * route them, so they are the licence's warning, not the preset's. Neither are
 * [NOT_ROUTED_BY_AUTO]. Reads saved preferences.
 */
fun StardustConfigurationPackage.unroutedFunctionalities(preset: Preset): Set<FunctionalityType> {
    val (required, defaults) = requiredAndDefaultFunctionalities(preset)
    val routed = CarriersUtils.savedSelection(preset.index) ?: defaults
    return required.filterTo(mutableSetOf()) {
        it !in NOT_ROUTED_BY_AUTO && it.isLicensed() && it !in routed
    }
}

/**
 * Functionalities nothing sends through RD Auto today, so a preset missing them is not a problem.
 * Each must come out of this set — and back into the check — once it is sent on its own RD:
 * - REPORTS: deprecated; the host's Reports feature is being removed.
 * - BFT (periodic location): not implemented yet.
 * - IMAGE: uses the file's RD — the host's RD settings set FILE and IMAGE with one toggle, so
 *   checking FILE covers it. Routing itself still asks for IMAGE (FileType.relatedFunctionalityType),
 *   so a radio whose defaults set FILE without IMAGE is not caught here until the operator saves
 *   a selection.
 */
private val NOT_ROUTED_BY_AUTO = setOf(
    FunctionalityType.REPORTS,
    FunctionalityType.BFT,
    FunctionalityType.IMAGE,
)

/** [unroutedFunctionalities] for the preset numbered [preset]; empty when the radio has no such preset. */
fun StardustConfigurationPackage.unroutedFunctionalities(preset: CurrentPreset): Set<FunctionalityType> =
    presets.firstOrNull { it.currentPreset == preset }?.let { unroutedFunctionalities(it) }.orEmpty()

// Same reading as the host's licence gate: no entry means no limit was reported.
private fun FunctionalityType.isLicensed(): Boolean =
    ConfigurationUtils.licensedFunctionalities[this]?.let { it == LimitationType.ENABLED } ?: true

/** Just the verdict, for a caller that only has to know whether the preset can be taken. */
fun StardustConfigurationPackage.unusablePresets(): Set<CurrentPreset> = presetProblems().keys

/**
 * The problem with the preset the radio is on now, or null when it is usable or no configuration
 * has arrived. Reads saved preferences — call off the main thread.
 */
fun activePresetProblem(): PresetProblem? {
    val config = ConfigurationUtils.currentConfig ?: return null
    val preset = ConfigurationUtils.currentPresetNow ?: return null
    return config.presetProblems()[preset]
}
