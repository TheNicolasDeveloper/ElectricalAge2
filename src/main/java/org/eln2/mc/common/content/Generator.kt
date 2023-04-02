package org.eln2.mc.common.content

import mcp.mobius.waila.api.IPluginConfig
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.items.CapabilityItemHandler
import net.minecraftforge.items.ItemStackHandler
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.electrical.mna.Circuit
import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.ageseries.libage.sim.electrical.mna.component.VoltageSource
import org.ageseries.libage.sim.thermal.Simulator
import org.ageseries.libage.sim.thermal.Temperature
import org.ageseries.libage.sim.thermal.ThermalMass
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D
import org.eln2.mc.Eln2.LOGGER
import org.eln2.mc.annotations.CrossThreadAccess
import org.eln2.mc.annotations.RaceCondition
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.PartialModels.bbOffset
import org.eln2.mc.client.render.foundation.BasicPartRenderer
import org.eln2.mc.common.blocks.foundation.CellBlock
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.cells.foundation.behaviors.IThermalBodyAccessor
import org.eln2.mc.common.cells.foundation.behaviors.withElectricalHeatTransfer
import org.eln2.mc.common.cells.foundation.behaviors.withElectricalPowerConverter
import org.eln2.mc.common.cells.foundation.objects.*
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.events.EventScheduler
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.common.space.DirectionMask
import org.eln2.mc.common.space.RelativeRotationDirection
import org.eln2.mc.control.pid
import org.eln2.mc.extensions.DirectionExtensions.toVector3D
import org.eln2.mc.extensions.LibAgeExtensions.add
import org.eln2.mc.extensions.LibAgeExtensions.setPotentialEpsilon
import org.eln2.mc.extensions.LibAgeExtensions.setResistanceEpsilon
import org.eln2.mc.extensions.NbtExtensions.getTemperature
import org.eln2.mc.extensions.NbtExtensions.putTemperature
import org.eln2.mc.extensions.NbtExtensions.useSubTagIfPreset
import org.eln2.mc.extensions.NumberExtensions.formatted
import org.eln2.mc.extensions.NumberExtensions.formattedPercentN
import org.eln2.mc.integration.waila.IWailaProvider
import org.eln2.mc.integration.waila.TooltipBuilder
import org.eln2.mc.mathematics.Functions.avg
import org.eln2.mc.mathematics.Functions.bbVec
import org.eln2.mc.mathematics.Functions.lerp
import org.eln2.mc.mathematics.Functions.map
import org.eln2.mc.mathematics.epsilonEquals
import org.eln2.mc.mathematics.evaluate
import org.eln2.mc.mathematics.mappedHermite
import org.eln2.mc.sim.Datasets
import org.eln2.mc.sim.ThermalBody
import org.eln2.mc.utility.SelfDescriptiveUnitMultipliers.megaJoules
import kotlin.math.*

enum class GeneratorPowerDirection {
    Outgoing,
    Incoming
}

/**
 * Represents an Electrical Generator. It is characterised by a voltage and internal resistance.
 * */
class GeneratorObject : ElectricalObject(), IWailaProvider {
    var plusDirection = RelativeRotationDirection.Front
    var minusDirection = RelativeRotationDirection.Back

    override val connectionMask: DirectionMask
        get() = DirectionMask.ofRelatives(plusDirection, minusDirection)

    var internalResistance: Double = 1.0
        set(value){
            field = value
            resistor.ifPresent { it.setResistanceEpsilon(value) }
        }

    var potential: Double = 1.0
        set(value){
            field = value
            source.ifPresent { it.setPotentialEpsilon(value) }
        }

    private val resistor = ElectricalComponentHolder {
        Resistor().also { it.resistance = internalResistance }
    }

    private val source = ElectricalComponentHolder {
        VoltageSource().also { it.potential = potential }
    }

    val hasResistor get() = resistor.isPresent

    val resistorPower get() = resistor.instance.power

    val resistorCurrent get() = resistor.instance.current

    val powerFlowDirection get() =
        if(resistor.instance.current > 0) GeneratorPowerDirection.Incoming
        else GeneratorPowerDirection.Outgoing

    override val maxConnections = 2

    override fun offerComponent(neighbour: ElectricalObject): ElectricalComponentInfo {
        return when(val dir = directionOf(neighbour)){
            plusDirection -> resistor.offerExternal()
            minusDirection -> source.offerNegative()
            else -> error("Unhandled neighbor direction $dir")
        }
    }

    override fun clearComponents() {
        resistor.clear()
        source.clear()
    }

    override fun addComponents(circuit: Circuit) {
        circuit.add(resistor)
        circuit.add(source)
    }

    override fun build() {
        resistor.connectInternal(source.offerPositive())

        connections.forEach { connectionInfo ->
            when(val direction = connectionInfo.direction){
                plusDirection -> resistor.connectExternal(this, connectionInfo)
                minusDirection -> source.connectNegative(this, connectionInfo)
                else -> error("Unhandled direction $direction")
            }
        }
    }

    override fun appendBody(builder: TooltipBuilder, config: IPluginConfig?) {
        builder.voltage(source.instance.potential)
        builder.text("Flow", powerFlowDirection)
        builder.power(abs(resistorPower))
    }
}

abstract class GeneratorCell(pos: CellPos, id: ResourceLocation) : CellBase(pos, id) {
    override fun createObjectSet(): SimulationObjectSet {
        return SimulationObjectSet(GeneratorObject())
    }

    val generatorObject get() = electricalObject as GeneratorObject
}

interface IBatteryView {
    val model: BatteryModel
    /**
     * Gets the total energy stored in the battery/
     * */
    val energy: Double

    /**
     * Gets the total energy exchanged by this battery.
     * */
    val energyIo: Double

    /**
     * Gets the battery current. This value's sign depends on the direction of flow.
     * If the current is incoming, it should be positive. If it is outgoing, it should be negative.
     * */
    val current: Double

    /**
     * Gets the life parameter of the battery.
     * */
    val life: Double

    /**
     * Gets the number of charge-discharge cycles this battery has been trough.
     * */
    val cycles: Double

    /**
     * Gets the charge percentage.
     * */
    val charge: Double

    /**
     * Gets the charge percentage, mapped using the battery's threshold parameter, as per [BatteryModel.damageChargeThreshold].
     * This value may be negative if the charge is under threshold.
     * */
    val thresholdCharge: Double

    /**
     * Gets the temperature of the battery.
     * */
    val temperature: Temperature
}

/**
 * The [IBatteryVoltageFunction] is used to compute the voltage of the battery based on the battery's state.
 * */
fun interface IBatteryVoltageFunction {
    fun computeVoltage(battery: IBatteryView, dt: Double): Double
}

/**
 * The [IBatteryResistanceFunction] is used to compute the internal resistance of the battery based on the battery's state.
 * It should never be zero, though this is not enforced and will likely result in a simulation error.
 * */
fun interface IBatteryResistanceFunction {
    fun computeResistance(battery: IBatteryView, dt: Double): Double
}

/**
 * The [IBatteryDamageFunction] computes a damage value in time, based on the battery's current state.
 * These values are deducted from the battery's life parameter. The final parameter is clamped.
 * */
fun interface IBatteryDamageFunction {
    fun computeDamage(battery: IBatteryView, dt: Double): Double
}

/**
 * The [IBatteryEnergyCapacityFunction] computes the capacity of the battery based on the battery's state.
 * This must be a value ranging from 0-1. The result is clamped to that range.
 * */
fun interface IBatteryEnergyCapacityFunction {
    fun computeCapacity(battery: IBatteryView): Double
}

object VoltageModels {
    /**
     * Gets a 12V Wet Cell Lead Acid Battery voltage function.
     * */
    val WET_CELL_12V = IBatteryVoltageFunction { view, _ ->
        val dataset = Datasets.LEAD_ACID_12V_WET
        val temperature = view.temperature.kelvin

        if(view.charge > view.model.damageChargeThreshold){
            dataset.evaluate(view.charge, temperature)
        }
        else{
            val progress = map(
                view.charge,
                0.0,
                view.model.damageChargeThreshold,
                0.0,
                1.0)

            val ceiling = dataset.evaluate(view.model.damageChargeThreshold, temperature)

            lerp(0.0, ceiling, progress)
        }
    }
}

object BatterySpecificHeats {
    // https://www.batterydesign.net/thermal/
    val PB_ACID_VENTED_FLOODED = 1080.0
    val PB_ACID_VRLA_GEL = 900.0
    val VRLA_AGM = 792.0
    val LI_ION_NCA = 830.0
    val LI_ION_NMC = 1040.0
    val LI_ION_NFP = 1145.0
}

object BatteryMaterials {
    val PB_ACID_TEST = Material(
        0.0,
        Material.LATEX_RUBBER.thermalConductivity,
        BatterySpecificHeats.PB_ACID_VENTED_FLOODED,
        0.0)
}

data class BatteryModel(
    val voltageFunction: IBatteryVoltageFunction,
    val resistanceFunction: IBatteryResistanceFunction,
    val damageFunction: IBatteryDamageFunction,
    val capacityFunction: IBatteryEnergyCapacityFunction,

    /**
     * The energy capacity of the battery. This is the total energy that can be stored.
     * */
    val energyCapacity: Double,

    /**
     * The charge percentage where, if the battery continues to discharge, it should start receiving damage.
     * */
    val damageChargeThreshold: Double,

    val material: Material,
    val mass: Double,
    val surfaceArea: Double)

data class BatteryState(val energy: Double, val life: Double, val energyIo: Double)

class BatteryCell(pos: CellPos, id: ResourceLocation, override val model: BatteryModel) :
    GeneratorCell(pos, id),
    IBatteryView {

    companion object {
        private const val ENERGY = "energy"
        private const val LIFE = "life"
        private const val ENERGY_IO = "energyIo"
    }

    override fun createObjectSet(): SimulationObjectSet {
        return SimulationObjectSet(GeneratorObject(), ThermalWireObject(this).also {
            it.body = ThermalBody(
                ThermalMass(model.material, null, model.mass),
                model.surfaceArea)
        })
    }

    init {
        behaviors.apply {
            withElectricalPowerConverter { generatorObject.resistorPower }
            withElectricalHeatTransfer { thermalWireObject.body }
        }
    }

    override var energy = 0.0

    override var energyIo = 0.0
        private set

    override var life = 1.0
        private set

    override val cycles
        get() = energyIo / model.energyCapacity

    override val current
        get() = generatorObject.resistorCurrent

    private val stateUpdate = AtomicUpdate<BatteryState>()

    override val charge get() = energy / model.energyCapacity

    override val thresholdCharge get() = map(
        charge,
        model.damageChargeThreshold,
        1.0,
        0.0,
        1.0)

    override val temperature: Temperature
        get() = thermalWireObject.body.temperature

    /**
     * Gets the capacity coefficient of this battery. It is computed using the [BatteryModel.capacityFunction].
     * */
    val capacityCoefficient
        get() = model.capacityFunction.computeCapacity(this).coerceIn(0.0, 1.0)

    /**
     * Gets the adjusted energy capacity of this battery. It is equal to the base energy capacity [BatteryModel.energyCapacity], scaled by [capacityCoefficient].
     * */
    val adjustedEnergyCapacity
        get() = model.energyCapacity * capacityCoefficient

    @CrossThreadAccess
    fun deserializeNbt(tag: CompoundTag) {
        stateUpdate.setLatest(BatteryState(
            tag.getDouble(ENERGY),
            tag.getDouble(LIFE),
            tag.getDouble(ENERGY_IO)))
    }

    @CrossThreadAccess
    fun serializeNbt(): CompoundTag{
        val tag = CompoundTag()

        tag.putDouble(ENERGY, energy)
        tag.putDouble(LIFE, life)
        tag.putDouble(ENERGY_IO, energyIo)

        return tag
    }

    override fun saveCellData(): CompoundTag {
        return serializeNbt()
    }

    override fun loadCellData(tag: CompoundTag) {
        deserializeNbt(tag)
    }

    override fun onGraphChanged() {
        graph.subscribers.addPreInstantaneous(this::simulationTick)
    }

    override fun onRemoving() {
        graph.subscribers.removeSubscriber(this::simulationTick)
    }

    private fun applyExternalUpdates(){
        stateUpdate.consume {
            energy = it.energy
            life = it.life
            energyIo = it.energyIo

            graph.setChanged()
        }
    }

    private fun simulateEnergyFlow(elapsed: Double) {
        // Get energy transfer:
        val transferredEnergy = abs(generatorObject.resistorPower * elapsed)

        // Update total IO:
        energyIo += transferredEnergy

        if(generatorObject.powerFlowDirection == GeneratorPowerDirection.Incoming){
            // Add energy into the system.

            energy += transferredEnergy
        } else {
            // Remove energy from the system.

            energy -= transferredEnergy
        }

        val capacity = adjustedEnergyCapacity

        // Clamp energy
        energy = energy.coerceIn(0.0, capacity)

        if(energy < 0){
            LOGGER.error("Negative battery energy $pos")
            energy = 0.0
        }
        else if(energy > capacity) {
            val extraEnergy = energy - capacity
            energy -= extraEnergy

            // Conserve energy by increasing temperature:
            thermalWireObject.body.thermalEnergy += extraEnergy
        }
    }

    private fun simulationTick(elapsed: Double, phase: SubscriberPhase){
        applyExternalUpdates()

        if(!generatorObject.hasResistor){
            return
        }

        simulateEnergyFlow(elapsed)

        generatorObject.potential = model.voltageFunction.computeVoltage(this, elapsed)
        generatorObject.internalResistance = model.resistanceFunction.computeResistance(this, elapsed)
        life -= model.damageFunction.computeDamage(this, elapsed)
        life = life.coerceIn(0.0, 1.0)

        graph.setChanged()
    }

    override fun appendBody(builder: TooltipBuilder, config: IPluginConfig?) {
        super.appendBody(builder, config)

        builder.text("Charge", thresholdCharge.formattedPercentN())
        builder.text("Life", life.formattedPercentN())
        builder.text("Cycles", cycles.formatted())
        builder.text("Capacity", capacityCoefficient.formattedPercentN())
        builder.energy(energy)
        builder.current(current)
    }

    private val thermalWireObject get() = thermalObject as ThermalWireObject
}

class BatteryPart(id: ResourceLocation, placementContext: PartPlacementContext, provider: CellProvider):
    CellPart(id, placementContext, provider),
    IItemPersistentPart {

    companion object {
        private const val BATTERY = "battery"
    }

    override val baseSize = bbVec(6.0, 8.0, 12.0)

    override fun createRenderer(): IPartRenderer {
        return BasicPartRenderer(this, PartialModels.BATTERY).also {
            it.downOffset = bbOffset(8.0)
        }
    }

    private val batteryCell get() = cell as BatteryCell

    override fun saveItemTag(tag: CompoundTag) {
        tag.put(BATTERY, batteryCell.serializeNbt())
    }

    override fun loadItemTag(tag: CompoundTag?) {
        tag?.useSubTagIfPreset(BATTERY, batteryCell::deserializeNbt)
    }

    override val order: ItemPersistentPartLoadOrder = ItemPersistentPartLoadOrder.AfterSim
}

/**
 * Thermal body with two connection sides.
 * */
class ThermalBiPoleObject: ThermalObject(), IWailaProvider {
    var b1 = ThermalBody.createDefault()
    var b2 = ThermalBody.createDefault()

    /**
     * The connection side of the first body.
     * */
    var b1Dir = RelativeRotationDirection.Front

    /**
     * The connection side of the second body.
     * */
    var b2Dir = RelativeRotationDirection.Back

    override val connectionMask: DirectionMask
        get() = DirectionMask.ofRelatives(b1Dir, b2Dir)

    override fun offerComponent(neighbour: ThermalObject): ThermalComponentInfo {
        return ThermalComponentInfo(when(val dir = directionOf(neighbour)){
            b1Dir -> b1
            b2Dir -> b2
            else -> error("Unhandled bi-pole direction $dir")
        })
    }

    override fun addComponents(simulator: Simulator) {
        simulator.add(b1)
        simulator.add(b2)
    }

    override fun appendBody(builder: TooltipBuilder, config: IPluginConfig?) {
        builder.temperature(b1.temperatureK)
        builder.temperature(b2.temperatureK)
    }
}

fun interface IThermalBodyProvider {
    fun get(): ThermalBody
}

interface IThermocoupleView {
    val model: ThermocoupleModel

    /**
     * Gets the absolute temperature difference between the hot and cold sides.
     * */
    val deltaTemperature: Temperature
}

/**
 * The [IThermocoupleVoltageFunction] computes a voltage based on the state of the thermocouple.
 * */
fun interface IThermocoupleVoltageFunction {
    fun compute(view: IThermocoupleView): Double
}

data class ThermocoupleModel(
    val efficiency: Double,
    val voltageFunction: IThermocoupleVoltageFunction
)

fun interface IGeneratorGetAccessor {
    fun get(): GeneratorObject
}

class ThermocoupleBehavior(
    private val generatorAccessor: IGeneratorGetAccessor,
    private val coldAccessor: IThermalBodyProvider,
    private val hotAccessor: IThermalBodyProvider,

    override val model: ThermocoupleModel): ICellBehavior, IThermocoupleView, IWailaProvider {

    private data class BodyPair(val hot: ThermalBody, val cold: ThermalBody, val inverted: Boolean)

    override fun onAdded(container: CellBehaviorContainer) {}

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPreInstantaneous(this::preTick)
        subscribers.addPostInstantaneous(this::postTick)
    }

    override fun destroy(subscribers: SubscriberCollection) {
        subscribers.removeSubscriber(this::preTick)
        subscribers.removeSubscriber(this::postTick)
    }

    private fun getSortedBodyPair(): BodyPair {
        var cold = coldAccessor.get()
        var hot = hotAccessor.get()

        var inverted = false

        if(cold.temperatureK > hot.temperatureK) {
            val temp = cold
            cold = hot
            hot = temp
            inverted = true
        }

        return BodyPair(hot, cold, inverted)
    }

    private var energyTransfer = 0.0

    private var totalConverted = 0.0
    private var totalRemoved = 0.0

    private fun preTick(dt: Double, phase: SubscriberPhase) {
        preTickThermalElectrical(dt)
    }

    private fun preTickThermalElectrical(dt: Double) {
        val bodies = getSortedBodyPair()
        val generator = generatorAccessor.get()

        energyTransfer =
            (bodies.hot.temperatureK - bodies.cold.temperatureK) * // DeltaT

                // P.S. I expect the dipoles to have the same material. What to do here?
                avg(bodies.hot.mass.material.specificHeat, bodies.cold.mass.material.specificHeat) * // Specific Heat

                systemMass // Mass

        // Maximum energy that can be converted into electrical energy.

        val convertibleEnergy = energyTransfer * model.efficiency

        val targetVoltage = model.voltageFunction.compute(this)
        val generatorResistance = generator.internalResistance

        val targetCurrent = targetVoltage / generatorResistance
        val targetPower = targetVoltage * targetCurrent

        val maximumPower = convertibleEnergy / dt

        val power = min(maximumPower, targetPower)

        var voltage = sqrt(power * generatorResistance)

        if(bodies.inverted) {
            voltage = -voltage
        }

        generator.potential = voltage
    }

    private fun postTick(dt: Double, phase: SubscriberPhase) {
        val bodies = getSortedBodyPair()

        val transferredEnergy = abs(generatorAccessor.get().resistorPower * dt)

        totalConverted += transferredEnergy

        val wastedEnergy = (transferredEnergy / model.efficiency) * (1 - model.efficiency)

        val removedEnergy = wastedEnergy + transferredEnergy

        totalRemoved += removedEnergy

        // We converted some thermal energy into electrical energy.
        // Wasted energy is energy that was moved into the cold body. We removed (wasted + transferred)
        // from the hot side, and added (wasted) to the cold side.
        bodies.hot.thermalEnergy -= removedEnergy
        bodies.cold.thermalEnergy += wastedEnergy
    }

    override fun appendBody(builder: TooltipBuilder, config: IPluginConfig?) {
        val pair = getSortedBodyPair()

        builder.text("Hot E", pair.hot.thermalEnergy.formatted())
        builder.text("Cold E", pair.cold.thermalEnergy.formatted())
        builder.text("Hot T", pair.hot.temperatureK.formatted())
        builder.text("Cold T", pair.cold.temperatureK.formatted())
        builder.text("dE", energyTransfer.formatted())
        builder.text("Total wasted", totalRemoved.formatted())
        builder.text("Total converted", totalConverted.formatted())
    }

    override val deltaTemperature: Temperature
        get() {
            val bodies = getSortedBodyPair()

            return bodies.hot.temperature - bodies.cold.temperature
        }

    /**
     * Gets the total mass of the system (hot body + cold body)
     * */
    private val systemMass get() = coldAccessor.get().mass.mass + hotAccessor.get().mass.mass
}

class ThermocoupleCell(pos: CellPos, id: ResourceLocation) : GeneratorCell(pos, id) {
    init {
        behaviors.add(ThermocoupleBehavior(
            { generatorObject },
            { thermalBiPole.b1 },
            { thermalBiPole.b2 },
            ThermocoupleModel(0.5) { view ->
                val temperature = view.deltaTemperature.kelvin.coerceIn(0.0, 20.0)

                map(temperature, 0.0, 20.0, 0.0, 100.0)
            }))
    }

    override fun createObjectSet(): SimulationObjectSet {
        return SimulationObjectSet(
            GeneratorObject().also {
                it.potential = 100.0
            },
            ThermalBiPoleObject().also {
                it.b1Dir = RelativeRotationDirection.Left
                it.b2Dir = RelativeRotationDirection.Right
            })
    }

    private val thermalBiPole get() = thermalObject as ThermalBiPoleObject

    val b1Temperature get() = thermalBiPole.b1.temperature
    val b2Temperature get() = thermalBiPole.b2.temperature
}

class ThermocouplePart(id: ResourceLocation, placementContext: PartPlacementContext) : CellPart(id, placementContext, Content.THERMOCOUPLE_CELL.get()) {
    companion object {
        private const val LEFT_TEMP = "left"
        private const val RIGHT_TEMP = "right"
    }

    override val baseSize: Vec3
        get() = Vec3(1.0, 15.0 / 16.0, 1.0)

    override fun createRenderer(): IPartRenderer {
        return RadiantBipoleRenderer(
            this,
            PartialModels.PELTIER_BODY,
            PartialModels.PELTIER_LEFT,
            PartialModels.PELTIER_RIGHT,
            bbOffset(15.0),
            0f
        )
    }

    override fun getSyncTag(): CompoundTag {
        return CompoundTag().also { tag ->
            val cell = cell as ThermocoupleCell

            tag.putTemperature(LEFT_TEMP, cell.b2Temperature)
            tag.putTemperature(RIGHT_TEMP, cell.b1Temperature)
        }
    }

    override fun handleSyncTag(tag: CompoundTag) {
        val renderer = renderer as? RadiantBipoleRenderer
            ?: return

        renderer.updateLeftSideTemperature(tag.getTemperature(LEFT_TEMP))
        renderer.updateRightSideTemperature(tag.getTemperature(RIGHT_TEMP))
    }

    override fun onCellAcquired() {
        sendTemperatureUpdates()
    }

    private fun sendTemperatureUpdates() {
        if(!isAlive) {
            return
        }

        syncChanges()

        EventScheduler.scheduleWorkPre(20, this::sendTemperatureUpdates)
    }
}

data class HeatGeneratorFuelMass(
    @RaceCondition
    var fuelAmount: Double,         // Unit
    val fuelEnergyCapacity: Double, // Energy/Unit
    val suggestedBurnRate: Double,  // Unit/Second
    val fuelTemperature: Double
) {
    val availableEnergy get() = fuelAmount * fuelEnergyCapacity

    /**
     * Gets the maximum energy that can be produced in the specified time [dt], using the specified mass [burnRate].
     * */
    fun getTheoreticalTransfer(dt: Double, burnRate: Double): Double {
        return burnRate * fuelEnergyCapacity * dt
    }

    /**
     * Gets the energy produced in the specified time [dt], using the specified mass [burnRate], taking into account the
     * amount of fuel remaining.
     * */
    fun getTransfer(dt: Double, burnRate: Double): Double {
        return min(getTheoreticalTransfer(dt, burnRate), availableEnergy)
    }

    /**
     * Removes the amount of mass corresponding to [energy] from the system.
     * */
    fun removeEnergy(energy: Double) {
        fuelAmount -= energy / fuelEnergyCapacity
    }
}

object Fuels {
    /**
     * Gets a coal fuel mass.
     * */
    fun coal(mass: Double): HeatGeneratorFuelMass {
        return HeatGeneratorFuelMass(
            fuelAmount = mass,
            fuelEnergyCapacity = megaJoules(24.0),
            suggestedBurnRate = 0.005,
            fuelTemperature = 2000.0
        )
    }
}

private class FuelBurnerBehavior(val bodyGetter: IThermalBodyAccessor): ICellBehavior, IWailaProvider {
    private var fuel: HeatGeneratorFuelMass? = null

    private val pid = pid(
        kP = 10.0,
        kI = 0.01,
        kD = 0.1
    )

    /**
     * Replaces the fuel in this burner and resets the control system.
     * */
    fun replaceFuel(fuel: HeatGeneratorFuelMass) {
        this.fuel = fuel
        pid.unwind()

        pid.minControl = 0.0
        pid.maxControl = fuel.suggestedBurnRate
        pid.setPoint = fuel.fuelTemperature
    }

    /**
     * Gets the available energy in this burner.
     * */
    val availableEnergy get() = fuel?.availableEnergy ?: 0.0

    private var burnRateSignal = 0.0

    override fun onAdded(container: CellBehaviorContainer) { }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPreInstantaneous(this::simulationTick)
    }

    override fun destroy(subscribers: SubscriberCollection) {
        subscribers.removeSubscriber(this::simulationTick)
    }

    private fun simulationTick(dt: Double, phase: SubscriberPhase) {
        val fuel = this.fuel
            ?: return

        val body = bodyGetter.get()

        burnRateSignal = pid.update(body.temperatureK, dt)

        val energyTransfer = fuel.getTransfer(dt, burnRateSignal)

        fuel.removeEnergy(energyTransfer)

        body.thermalEnergy += energyTransfer
    }

    override fun appendBody(builder: TooltipBuilder, config: IPluginConfig?) {
        builder.text("Control Signal x1000", (burnRateSignal * 1000).formatted(2))
        builder.text("Fuel", (fuel?.fuelAmount ?: 0.0).formatted())
    }
}

class HeatGeneratorCell(pos: CellPos, id: ResourceLocation) : CellBase(pos, id) {
    init {
        behaviors.add(
            FuelBurnerBehavior { thermalWireObject.body }
        )
    }

    /**
     * If true, this burner needs more fuel to continue burning. Internally, this checks if the available energy is less than a threshold value.
     * */
    val needsFuel get() = behaviors.getBehavior<FuelBurnerBehavior>().availableEnergy epsilonEquals 0.0

    fun replaceFuel(mass: HeatGeneratorFuelMass) {
        behaviors.getBehavior<FuelBurnerBehavior>().replaceFuel(mass)
    }

    override fun createObjectSet(): SimulationObjectSet {
        return SimulationObjectSet(ThermalWireObject(this))
    }

    private val thermalWireObject get() = thermalObject as ThermalWireObject
}

class HeatGeneratorBlockEntity(pos: BlockPos, state: BlockState): CellBlockEntity(pos, state, Content.HEAT_GENERATOR_BLOCK_ENTITY.get()) {
    companion object {
        const val FUEL_SLOT = 0

        fun tick(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
            if (pLevel == null || pBlockEntity == null) {
                return
            }

            if (pBlockEntity !is HeatGeneratorBlockEntity) {
                LOGGER.error("Got $pBlockEntity instead of heat generator")
                return
            }

            if (!pLevel.isClientSide) {
                pBlockEntity.serverTick()
            }
        }
    }

    class InventoryHandler : ItemStackHandler(1) {
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
            if(stack.item != Items.COAL) {
                return ItemStack.EMPTY
            }

            return super.insertItem(slot, stack, simulate)
        }
    }

    private val inventoryHandler = InventoryHandler()
    private val inventoryHandlerLazy = LazyOptional.of { inventoryHandler }

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if(cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return inventoryHandlerLazy.cast()
        }

        return super.getCapability(cap, side)
    }

    fun serverTick() {
        val cell = heatGeneratorCell
            ?: return

        if(!cell.needsFuel) {
            return
        }

        val stack = inventoryHandler.extractItem(FUEL_SLOT, 1, false)

        if(stack.isEmpty) {
            return
        }

        cell.replaceFuel(Fuels.coal(1.0))
    }

    private val heatGeneratorCell get() = cell as? HeatGeneratorCell
}

class HeatGeneratorBlock : CellBlock() {
    override fun getCellProvider(): ResourceLocation {
        return Content.HEAT_GENERATOR_CELL.id
    }

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState): BlockEntity {
        return HeatGeneratorBlockEntity(pPos, pState)
    }

    override fun <T : BlockEntity?> getTicker(
        pLevel: Level,
        pState: BlockState,
        pBlockEntityType: BlockEntityType<T>
    ): BlockEntityTicker<T> {
        return BlockEntityTicker(HeatGeneratorBlockEntity::tick)
    }
}

/**
 * Represents a view over a solar illuminated body.
 * */
interface IIlluminatedBodyView {
    /**
     * Gets the current angle of the sun.
     * @see ServerLevel.getSunAngle
     * */
    val sunAngle: Double

    /**
     * Gets whether this body's view to the sun is obstructed.
     * @see ServerLevel.canSeeSky
     * */
    val isObstructed: Boolean

    /**
     * Gets the normal direction of the surface.
     * */
    val normal: Direction
}

abstract class SolarIlluminationBehavior(private val cell: CellBase): ICellBehavior, IIlluminatedBodyView {
    // Is it fine to access these from our simulation threads?
    override val sunAngle: Double
        get() = cell.graph.level.getSunAngle(0f).toDouble()

    override val isObstructed: Boolean
        get() = !cell.graph.level.canSeeSky(cell.pos.blockPos)
}

abstract class SolarGeneratorBehavior(val generatorCell: GeneratorCell): SolarIlluminationBehavior(generatorCell) {
    override fun onAdded(container: CellBehaviorContainer) { }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addSubscriber(SubscriberOptions(100, SubscriberPhase.Pre), this::simulationTick)
    }

    override fun destroy(subscribers: SubscriberCollection) {
        subscribers.removeSubscriber(this::simulationTick)
    }

    private fun simulationTick(dt: Double, phase: SubscriberPhase) {
        if(!generatorCell.hasGraph){
            // weird.
            return
        }

        update()
    }

    abstract fun update()
}

/**
 * The [IPhotovoltaicVoltageFunction] computes a voltage based on the photovoltaic panel's state.
 * */
fun interface IPhotovoltaicVoltageFunction {
    fun compute(view: IIlluminatedBodyView): Double
}

data class PhotovoltaicModel(
    val voltageFunction: IPhotovoltaicVoltageFunction,
    val panelResistance: Double
)

object PhotovoltaicModels {
    // We map angle difference to a voltage coefficient. 0 - directly overhead, 1 - under horizon
    private val TEST_SPLINE = mappedHermite().apply {
        point(0.0, 1.0)
        point(0.95, 0.8)
        point(1.0, 0.0)
    }.buildHermite()

    private fun voltageTest(maximumVoltage: Double): IPhotovoltaicVoltageFunction {
        return IPhotovoltaicVoltageFunction { view ->
            if(view.isObstructed) {
                return@IPhotovoltaicVoltageFunction 0.0
            }

            val passProgress = when (val sunAngle = Math.toDegrees(view.sunAngle)) {
                in 270.0..360.0 -> {
                    map(sunAngle, 270.0, 360.0, 0.0, 0.5)
                }
                in 0.0..90.0 -> {
                    map(sunAngle, 0.0, 90.0, 0.5, 1.0)
                }
                else -> {
                    // Under horizon

                    return@IPhotovoltaicVoltageFunction 0.0
                }
            }

            // Sun moves around Z

            val passAngle = passProgress * Math.PI
            val sunDirection3 = Vector3D(cos(passAngle), sin(passAngle), 0.0)
            val panelDirection3 = view.normal.toVector3D()
            val rayAngle = Math.toDegrees(Vector3D.angle(sunDirection3, panelDirection3))

            val value = TEST_SPLINE.evaluate(map(
                rayAngle,
                0.0,
                90.0,
                0.0,
                1.0))

            return@IPhotovoltaicVoltageFunction value * maximumVoltage
        }
    }

    fun test24Volts(): PhotovoltaicModel {
        //https://www.todoensolar.com/285w-24-volt-AmeriSolar-Solar-Panel
        return PhotovoltaicModel(voltageTest(32.0), 3.5)
    }
}

class PhotovoltaicBehavior(cell: GeneratorCell, val model: PhotovoltaicModel) : SolarGeneratorBehavior(cell) {
    override fun update() {
        generatorCell.generatorObject.potential = model.voltageFunction.compute(this)
    }

    override val normal: Direction
        get() = generatorCell.pos.face
}


class PhotovoltaicGeneratorCell(pos: CellPos, id: ResourceLocation, model: PhotovoltaicModel) : GeneratorCell(pos, id) {
    init {
        behaviors.add(PhotovoltaicBehavior(this, model))
    }
}
