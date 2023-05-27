package org.eln2.mc.common.content

import net.minecraft.Util
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextComponent
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import org.eln2.mc.common.items.eln2Tab
import org.eln2.mc.data.CurrentField
import org.eln2.mc.data.TemperatureField
import org.eln2.mc.data.VoltageField
import org.eln2.mc.extensions.getDataAccess
import org.eln2.mc.utility.UnitType
import org.eln2.mc.utility.valueText

abstract class MeterItem : Item(Properties().tab(eln2Tab).stacksTo(1)) {
    override fun useOn(pContext: UseOnContext): InteractionResult {
        if(pContext.level.isClientSide) {
            return InteractionResult.PASS
        }

        val player = pContext.player ?: return InteractionResult.FAIL

        player.sendMessage(read(pContext, player) ?: return InteractionResult.FAIL, Util.NIL_UUID)

        return InteractionResult.SUCCESS
    }

    abstract fun read(pContext: UseOnContext, player: Player): Component?
}

class UniversalMeter(
    // Default values are provided to make registration easier:
    val readVoltage: Boolean = false,
    val readCurrent: Boolean = false,
    val readTemperature: Boolean = false) : MeterItem() {
    override fun read(pContext: UseOnContext, player: Player): Component? {
        val target = pContext.level.getDataAccess(pContext.clickedPos)
            ?: return null

        class FieldReading(val field: Any, val label: String, val printout: String)

        val readings = ArrayList<FieldReading>()

        if(readVoltage) {
            target.fieldScan<VoltageField>().forEach {
                readings.add(FieldReading(it, "Voltage", valueText(it.read(), UnitType.VOLT)))
            }
        }

        if(readCurrent) {
            target.fieldScan<CurrentField>().forEach {
                readings.add(FieldReading(it, "Current", valueText(it.read(), UnitType.AMPERE)))
            }
        }

        if(readTemperature) {
            target.fieldScan<TemperatureField>().forEach {
                readings.add(FieldReading(it, "Temperature", valueText(it.read().kelvin, UnitType.KELVIN)))
            }
        }

        return if(readings.isEmpty()) null
        else TextComponent(readings.joinToString(", ") { reading ->
            "${reading.label}: ${reading.printout}"
        })
    }
}
