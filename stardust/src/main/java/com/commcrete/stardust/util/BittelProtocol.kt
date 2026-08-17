package com.commcrete.stardust.util

/**
 * Common transport-agnostic commands. Anything that differs between BLE and USB does NOT belong
 * here — historically `updateBlePort` lived here but the two implementations did OPPOSITE things
 * (BLE impl sent "keep BLE on"; USB impl sent "switch to USB / disable BLE"), so callers routed
 * through the interface silently misdispatched. The port-mode command is now split into two
 * distinctly-named transport-specific methods and callers must pick explicitly.
 */
interface BittelProtocol {
    fun saveConfiguration ()
}