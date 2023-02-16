package org.eln2.mc.common.cells

import org.ageseries.libage.sim.electrical.mna.component.Inductor
import org.eln2.mc.common.cells.foundation.CellBase
import org.eln2.mc.common.cells.foundation.CellPos
import org.eln2.mc.common.cells.foundation.ComponentInfo
import org.eln2.mc.common.cells.foundation.ISingleElementGuiCell
import org.eln2.mc.extensions.ComponentExtensions.connectToPinOf
import org.eln2.mc.utility.UnitType
import org.eln2.mc.utility.ValueText.valueText

class InductorCell(pos: CellPos) : CellBase(pos), ISingleElementGuiCell<Double> {
    lateinit var inductor: Inductor
    var added = false

    override fun clear() {
        inductor = Inductor()
        inductor.inductance = 0.1
        added = false
    }

    override fun getOfferedComponent(neighbour: CellBase): ComponentInfo {
        val circuit = graph.circuit
        if(!added) {
            circuit.add(inductor)
            added = true
        }
        return ComponentInfo(inductor, connections.indexOf(neighbour))
    }

    override fun buildConnections() {
        connections.forEach{remoteCell ->
            val localInfo = getOfferedComponent(remoteCell)
            localInfo.component.connectToPinOf(localInfo.index, remoteCell.getOfferedComponent(this))
        }
    }

    override fun getHudMap(): Map<String, String> {
        var voltage: String = valueText(0.0, UnitType.VOLT)
        var current: String = valueText(0.0, UnitType.AMPERE)
        val inductance: String = valueText(inductor.inductance, UnitType.HENRY)
        var joules: String = valueText(0.0, UnitType.JOULE)
        val map = mutableMapOf<String, String>()

        try {
            current = valueText(inductor.current, UnitType.AMPERE)
            joules = valueText(inductor.energy, UnitType.JOULE)
            voltage = inductor.pins.joinToString(", ") {
                valueText(
                    it.node?.potential ?: 0.0,
                    UnitType.VOLT
                )
            }
        } catch (_: Exception) {
            // No results from simulator
        }

        map["voltage"] = voltage
        map["current"] = current
        map["inductance"] = inductance
        map["energy"] = joules

        return map
    }

    override fun getGuiValue(): Double {
        return inductor.inductance
    }

    override fun setGuiValue(value: Double) {
        inductor.inductance = value
    }
}