package org.eln2.mc.sim

import mcp.mobius.waila.api.IPluginConfig
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.thermal.Temperature
import org.ageseries.libage.sim.thermal.ThermalMass
import org.eln2.mc.extensions.appendBody
import org.eln2.mc.integration.waila.IWailaProvider
import org.eln2.mc.integration.waila.TooltipBuilder

class ThermalBody(
    var thermalMass: ThermalMass,
    var surfaceArea: Double
) : IWailaProvider {
    var temperature: Temperature
        get() = thermalMass.temperature
        set(value) { thermalMass.temperature = value }
    var temperatureK: Double
        get() = temperature.kelvin
        set(value) { thermalMass.temperature = Temperature(value) }

    var thermalEnergy: Double
        get() = thermalMass.energy
        set(value) { thermalMass.energy = value }

    override fun appendBody(builder: TooltipBuilder, config: IPluginConfig?) {
        thermalMass.appendBody(builder, config)
    }

    companion object {
        fun createDefault(): ThermalBody {
            return ThermalBody(ThermalMass(Material.COPPER), 0.5)
        }
    }
}
