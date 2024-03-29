@file:Suppress("MemberVisibilityCanBePrivate", "PublicApiImplicitType", "PublicApiImplicitType", "unused", "LongLine")

package org.eln2.mc.common.content

import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items.BUCKET
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.phys.Vec3
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fluids.FluidType
import net.minecraftforge.fluids.ForgeFlowingFluid
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.thermal.Temperature
import org.ageseries.libage.sim.thermal.ThermalUnits
import org.eln2.mc.LOG
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.PartialModels.bbOffset
import org.eln2.mc.client.render.foundation.McColor
import org.eln2.mc.common.blocks.BlockRegistry.block
import org.eln2.mc.common.blocks.BlockRegistry.blockEntity
import org.eln2.mc.common.blocks.foundation.BasicCellBlock
import org.eln2.mc.common.blocks.foundation.BasicMBControllerBlock
import org.eln2.mc.common.cells.CellRegistry.cell
import org.eln2.mc.common.cells.CellRegistry.injCell
import org.eln2.mc.common.cells.foundation.BasicCellProvider
import org.eln2.mc.common.containers.ContainerRegistry.menu
import org.eln2.mc.common.entities.EntityRegistry.entity
import org.eln2.mc.common.fluids.FluidRegistry.FLUIDS
import org.eln2.mc.common.fluids.FluidRegistry.FLUID_TYPES
import org.eln2.mc.common.fluids.foundation.BasicFluidType
import org.eln2.mc.common.fluids.foundation.WATER_FLOW_TEXTURE
import org.eln2.mc.common.fluids.foundation.WATER_SOURCE_TEXTURE
import org.eln2.mc.common.items.ItemRegistry.item
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.parts.PartRegistry.part
import org.eln2.mc.common.parts.foundation.BasicCellPart
import org.eln2.mc.common.parts.foundation.BasicPartProvider
import org.eln2.mc.common.parts.foundation.basicPartRenderer
import org.eln2.mc.data.KW_HOURS
import org.eln2.mc.data.Quantity
import org.eln2.mc.data.withDirectionActualRule
import org.eln2.mc.mathematics.*
import kotlin.math.abs


/**
 * Joint registry for content classes.
 */
object Content {
    /**
     * Initializes the fields, in order to register the content.
     */
    fun initialize() {}

    //#region Wires

    val WIRE_MODEL_COPPER_ELECTRICAL_TEST = WireModel()
    val WIRE_MODEL_COPPER_THERMAL_TEST = WireModel().apply {
        replicateTemperature = true
        isElectrical = false
        damageThreshold = Temperature.from(2000.0, ThermalUnits.CELSIUS)
    }

    val ELECTRICAL_WIRE_CELL_COPPER =
        injCell<WireCell>("electrical_wire_cell_copper", WIRE_MODEL_COPPER_ELECTRICAL_TEST)
    val THERMAL_WIRE_CELL_COPPER = injCell<WireCell>("thermal_wire_cell_copper", WIRE_MODEL_COPPER_THERMAL_TEST)

    val ELECTRICAL_WIRE_PART_COPPER: PartRegistry.PartRegistryItem =
        part("electrical_wire_part_copper", BasicPartProvider({ a, b ->
            WirePart(a, b, ELECTRICAL_WIRE_CELL_COPPER.get(), WIRE_MODEL_COPPER_ELECTRICAL_TEST)
        }, Vec3(0.1, 0.1, 0.1)))

    val THERMAL_WIRE_PART_COPPER: PartRegistry.PartRegistryItem =
        part("thermal_wire_part_copper", BasicPartProvider({ a, b ->
            WirePart(a, b, THERMAL_WIRE_CELL_COPPER.get(), WIRE_MODEL_COPPER_THERMAL_TEST)
        }, Vec3(0.1, 0.1, 0.1)))


    val VOLTAGE_SOURCE_CELL = cell("voltage_source_cell", BasicCellProvider(::VoltageSourceCell))
    val VOLTAGE_SOURCE_PART = part("voltage_source_part", BasicPartProvider(::VoltageSourcePart, Vec3(0.3, 0.3, 0.3)))

    val GROUND_CELL = cell("ground_cell", BasicCellProvider(::GroundCell))
    val GROUND_PART = part("ground_part", BasicPartProvider(::GroundPart, Vec3(0.3, 0.3, 0.3)))

    val THERMAL_RADIATOR_CELL = cell("thermal_radiator_cell", BasicCellProvider {
        ThermalRadiatorCell(
            it,
            RadiatorModel(
                2000.0,
                100.0,
                Material.COPPER,
                100.0
            )
        )
    })
    val THERMAL_RADIATOR = part("thermal_radiator_part", BasicPartProvider(::RadiatorPart, Vec3(1.0, 3.0 / 16.0, 1.0)))

    val RESISTOR_CELL = cell("resistor_cell", BasicCellProvider(::ResistorCell))
    val RESISTOR_PART = part("resistor_part", BasicPartProvider(::ResistorPart, Vec3(1.0, 0.4, 0.4)))

    val FURNACE_BLOCK_ENTITY = blockEntity("furnace", ::FurnaceBlockEntity) { FURNACE_BLOCK.block.get() }
    val FURNACE_CELL = cell("furnace_cell", BasicCellProvider(::FurnaceCell))
    val FURNACE_BLOCK = block("furnace", tab = null) { FurnaceBlock() }
    val FURNACE_MENU = menu("furnace_menu", ::FurnaceMenu)

    val BATTERY_CELL_100V = cell("battery_cell_t", BasicCellProvider { createInfo ->
        BatteryCell(
            createInfo, BatteryModel(
                voltageFunction = BatteryVoltageModels.WET_CELL_12V,
                resistanceFunction = { _, _ -> 100 * 1e-3 },
                damageFunction = { battery, dt ->
                    val currentThreshold = 100.0 //A

                    // if current > threshold, 0.0001% per second for every amp
                    // if charge < threshold, amplify everything by 10delta

                    val absCurrent = abs(battery.current)

                    val currentTerm = if (absCurrent > currentThreshold) {
                        (absCurrent - currentThreshold) * (0.0001 / 100.0)
                    } else 0.0

                    val amplification = if (battery.charge < battery.model.damageChargeThreshold) {
                        (battery.model.damageChargeThreshold - battery.charge) * 10
                    } else 0.0

                    currentTerm * dt * (1.0 + amplification)
                },
                capacityFunction = { battery ->
                    // Test capacity func: 0% after 5 cycles
                    // life has 90% impact.

                    val lifeTerm = -(1 - battery.life) * 0.90
                    val cyclesTerm = -lerp(0.0, 1.0, battery.cycles / 5.0)

                    1 + lifeTerm + cyclesTerm
                },
                energyCapacity = Quantity(2.2, KW_HOURS),
                0.5,
                BatteryMaterials.PB_ACID_TEST,
                20.0,
                0.3
            )
        )
            .also { it.energy = it.model.energyCapacity * 0.9 }
    })

    val BATTERY_PART_100V =
        part("battery_part_100v", BasicPartProvider({ a, b -> BatteryPart(a, b, BATTERY_CELL_100V.get()) }, vec3(1.0)))

    val THERMOCOUPLE_CELL = cell("thermocouple_cell", BasicCellProvider { createInfo ->
        ThermocoupleCell(
            createInfo,
            dirActualMap(plusDir = Base6Direction3d.Front, minusDir = Base6Direction3d.Back),
            dirActualMap(plusDir = Base6Direction3d.Left, minusDir = Base6Direction3d.Right)
        ).also {
            it.generatorObj.ruleSet.withDirectionActualRule(DirectionMask.FRONT + DirectionMask.BACK)
            it.thermalBipoleObj.ruleSet.withDirectionActualRule(DirectionMask.LEFT + DirectionMask.RIGHT)
        }
    })
    val THERMOCOUPLE_PART = part("thermocouple_part", BasicPartProvider({ id, context ->
        ThermocouplePart(id, context)
    }, Vec3(0.5, 15.0 / 16.0, 0.5)))

    val HEAT_GENERATOR_CELL = cell("heat_generator_cell", BasicCellProvider(::HeatGeneratorCell))
    val HEAT_GENERATOR_PART = part("heat_generator_part", BasicPartProvider({ id, context ->
        BasicCellPart(
            id,
            context,
            vec3(1.0),
            HEAT_GENERATOR_CELL.get(),
            basicPartRenderer(PartialModels.THERMAL_WIRE_CROSSING_FULL, 0.0)
        )
    }, vec3(1.0)))
    val HEAT_GENERATOR_BLOCK = block("heat_generator", tab = null) { HeatGeneratorBlock() }
    val HEAT_GENERATOR_BLOCK_ENTITY =
        blockEntity("heat_generator", ::HeatGeneratorBlockEntity) { HEAT_GENERATOR_BLOCK.block.get() }
    val HEAT_GENERATOR_MENU = menu("heat_generator_menu", ::HeatGeneratorMenu)

    val PHOTOVOLTAIC_GENERATOR_CELL = cell("photovoltaic_cell", BasicCellProvider {
        PhotovoltaicGeneratorCell(it, PhotovoltaicModels.test24Volts())
    })

    val PHOTOVOLTAIC_PANEL_PART = part("photovoltaic_panel_part", BasicPartProvider({ id, context ->
        BasicCellPart(
            id,
            context,
            Vec3(1.0, bbSize(2.0), 1.0),
            PHOTOVOLTAIC_GENERATOR_CELL.get(),
            basicPartRenderer(
                PartialModels.SOLAR_PANEL_ONE_BLOCK,
                bbOffset(2.0)
            )
        )
    }, Vec3(1.0, bbSize(2.0), 1.0)))

    val LIGHT_CELL = cell("light_cell", BasicCellProvider {
        LightCell(
            it,
            LightModel({ pwr -> pwr / 100.0 }, 0.1)
        )
    })
    val LIGHT_PART =
        part("light_part", BasicPartProvider({ a, b -> LightPart(a, b, LIGHT_CELL.get()) }, bbVec(8.0, 4.0, 5.0)))


    val VOLTAGE_METER_ITEM = item("voltage_meter") { UniversalMeter(readVoltage = true) }
    val CURRENT_METER_ITEM = item("current_meter") { UniversalMeter(readCurrent = true) }
    val TEMPERATURE_METER_ITEM = item("temperature_meter") { UniversalMeter(readTemperature = true) }
    val UNIVERSAL_METER_ITEM = item("universal_meter") {
        UniversalMeter(
            readVoltage = true,
            readCurrent = true,
            readTemperature = true
        )
    }

    val MB_HEATER_HEAT_PORT_CELL = cell("heater_heat_port_cell", BasicCellProvider(::HeaterHeatPortCell))
    val MB_HEATER_HEAT_PORT_BLOCK = block("heater_heat_port_block") { BasicCellBlock(MB_HEATER_HEAT_PORT_CELL) }
    val MB_HEATER_POWER_PORT_CELL = cell("heater_power_port_cell", BasicCellProvider(::HeaterPowerPortCell))
    val MB_HEATER_POWER_PORT_BLOCK = block("heater_power_port_block") { BasicCellBlock(MB_HEATER_POWER_PORT_CELL) }
    val MB_HEATER_CTRL_BLOCK = block("heater_controller_block") { BasicMBControllerBlock(::HeaterCtrlBlockEntity) }
    val MB_HEATER_CTRL_BLOCK_ENTITY =
        blockEntity("heater_controller_block_entity", ::HeaterCtrlBlockEntity) { MB_HEATER_CTRL_BLOCK.block.get() }

    val GRID_CELL = injCell<GridCell>("grid_cell")
    val GRID_TEST_BLOCK = block("grid_test_block") { GridCellBlock() }
    val GRID_TEST_BLOCK_ENTITY =
        blockEntity("grid_test_block_entity", ::GridCellBlockEntity) { GRID_TEST_BLOCK.block.get() }
    val GRID_CONNECT_ITEM = item("grid_connect_item", ::GridConnectItem)
    val GRID_CONNECTION_ENTITY = entity("grid_connection_entity", MobCategory.MISC, ::GridConnectionEntity)

    // to be removed
    val LATEX_SAP_FLUID_TYPE: RegistryObject<FluidType> = FLUID_TYPES.register("latex_sap_fluid_type") {
        BasicFluidType(
            properties = FluidType.Properties.create().viscosity(500),
            source = WATER_SOURCE_TEXTURE,
            flow = WATER_FLOW_TEXTURE,
            tint = McColor(255, 255, 255)
        )
    }
    val LATEX_SAP_SOURCE_FLUID: RegistryObject<ForgeFlowingFluid.Source> =
        FLUIDS.register("latex_sap_fluid_source") { ForgeFlowingFluid.Source(LATEX_SAP_FLUID_PROPERTIES) }
    val LATEX_SAP_FLOW_FLUID: RegistryObject<FlowingFluid> =
        FLUIDS.register("latex_sap_fluid_flow") { ForgeFlowingFluid.Flowing(LATEX_SAP_FLUID_PROPERTIES) }
    val LATEX_SAP_BUCKET = item("latex_sap_bucket_item") {
        BucketItem(LATEX_SAP_SOURCE_FLUID, Item.Properties().craftRemainder(BUCKET).stacksTo(1))
    }
    val LATEX_SAP_FLUID_BLOCK = block("latex_sap_fluid_block") {
        LiquidBlock(
            { LATEX_SAP_SOURCE_FLUID.get() },
            BlockBehaviour.Properties.of(net.minecraft.world.level.material.Material.WATER)
        )
    }
    val LATEX_SAP_FLUID_PROPERTIES: ForgeFlowingFluid.Properties =
        ForgeFlowingFluid.Properties(LATEX_SAP_FLUID_TYPE, LATEX_SAP_SOURCE_FLUID, LATEX_SAP_FLOW_FLUID)
            .bucket { LATEX_SAP_BUCKET.item.get() }
            .block { LATEX_SAP_FLUID_BLOCK.block.get() as LiquidBlock }

    @Mod.EventBusSubscriber
    object ClientSetup {
        @SubscribeEvent
        @JvmStatic
        fun clientSetup(event: FMLClientSetupEvent) {
            event.enqueueWork {
                clientWork()
                LOG.info("Content registry client-sided setup complete.")
            }
        }

        private fun clientWork() {
            MenuScreens.register(FURNACE_MENU.get(), ::FurnaceScreen)
            MenuScreens.register(HEAT_GENERATOR_MENU.get(), ::HeatGeneratorScreen)
            ItemBlockRenderTypes.setRenderLayer(LATEX_SAP_SOURCE_FLUID.get(), RenderType.translucent());
            ItemBlockRenderTypes.setRenderLayer(LATEX_SAP_FLOW_FLUID.get(), RenderType.translucent());
        }
    }
}
