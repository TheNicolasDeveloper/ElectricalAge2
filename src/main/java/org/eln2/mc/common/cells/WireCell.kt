package org.eln2.mc.common.cells

import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.eln2.mc.common.cells.foundation.CellBase
import org.eln2.mc.common.cells.foundation.CellPos
import org.eln2.mc.common.cells.foundation.ComponentInfo
import org.eln2.mc.extensions.ComponentExtensions.connect
import org.eln2.mc.utility.UnitType
import org.eln2.mc.utility.ValueText.valueText
import kotlin.math.abs

class WireCell(pos: CellPos) : CellBase(pos) {
    /*  R -> local resistors. Their first pins are interconnected.
    *   C -> remote components. The second pin of the local resistors is used for them.
    *
    *       C
    *       R
    *   C R ┼ R C
    *       R
    *       C
    */

    override fun clear() {
        neighbourToResistorLookup.clear()
    }

    override fun getOfferedComponent(neighbour: CellBase): ComponentInfo {
        val circuit = graph.circuit

        return ComponentInfo(neighbourToResistorLookup.computeIfAbsent(neighbour) {
            val resistor = Resistor()
            resistor.resistance = 0.001
            circuit.add(resistor)
            if (neighbourToResistorLookup.isNotEmpty()) {
                neighbourToResistorLookup.values.first().connect(0, resistor, 0)
            }
            resistor
        }, 1)
    }

    override fun buildConnections() {
        connections.forEach { adjacentCell ->
            val resistor = getOfferedComponent(adjacentCell).component
            resistor.connect(1, adjacentCell.getOfferedComponent(this))
        }
    }

    private val neighbourToResistorLookup = HashMap<CellBase, Resistor>()

    /*
     * The most meaningful current is the branch currents at the central points.
     */
    override fun getHudMap(): Map<String, String> {
        var voltage: String = valueText(0.0, UnitType.VOLT)
        var current: String = valueText(0.0, UnitType.AMPERE)
        val resistance: String = valueText(0.001, UnitType.OHM)
        val map = mutableMapOf<String, String>()

        try {
            val currentString = if (connections.size == 2) {
                // Straight through wire. Just give absolute value I guess since directionality is ~ meaningless for wires.
                valueText(abs((getOfferedComponent(connections[0]).component as Resistor).current), UnitType.AMPERE)
            } else {
                // Branch currents. Print them all.
                val currents = connections.map { (getOfferedComponent(it).component as Resistor).current }
                currents.joinToString(", ") { valueText(it, UnitType.AMPERE) }
            }
            if (currentString.isNotEmpty())
                current = currentString
            voltage = valueText(
                (getOfferedComponent(connections[0]).component as Resistor).pins[0].node?.potential ?: 0.0,
                UnitType.VOLT
            )
        } catch (_: Exception) {
            // No results from simulator
        }

        map["voltage"] = voltage
        map["current"] = current
        map["resistance"] = resistance

        return map
    }
}
