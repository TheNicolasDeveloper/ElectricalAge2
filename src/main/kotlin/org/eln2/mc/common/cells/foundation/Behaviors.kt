package org.eln2.mc.common.cells.foundation

import mcp.mobius.waila.api.IPluginConfig
import net.minecraft.server.level.ServerLevel
import org.ageseries.libage.sim.thermal.Temperature
import org.ageseries.libage.sim.thermal.ThermalUnits
import org.eln2.mc.Inj
import org.eln2.mc.ThermalBody
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.common.events.schedulePre
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.data.*
import org.eln2.mc.destroyPart
import org.eln2.mc.formattedPercentN
import org.eln2.mc.integration.WailaEntity
import org.eln2.mc.integration.WailaTooltipBuilder

/**
 * *A cell behavior* manages routines ([Subscriber]) that run on the simulation thread.
 * It is attached to a cell.
 * */
interface CellBehavior {
    /**
     * Called when the behavior is added to the container.
     * */
    fun onAdded(container: CellBehaviorContainer) {}

    /**
     * Called when the subscriber collection is being set up.
     * Subscribers can be added here.
     * */
    fun subscribe(subscribers: SubscriberCollection) {}

    /**
     * Called when the behavior is destroyed.
     * This can be caused by the cell being destroyed.
     * It can also be caused by the game object being detached, in the case of [ReplicatorBehavior]s.
     * */
    fun destroy() {}
}

// to remove
/**
 * Temporary staging storage for behaviors.
 * */
class CellBehaviorSource : CellBehavior {
    private val behaviors = ArrayList<CellBehavior>()

    fun add(behavior: CellBehavior): CellBehaviorSource {
        if (behavior is CellBehaviorSource) {
            behaviors.addAll(behavior.behaviors)
        } else {
            behaviors.add(behavior)
        }

        return this
    }

    override fun onAdded(container: CellBehaviorContainer) {
        behaviors.forEach { behavior ->
            if (container.behaviors.any { it.javaClass == behavior.javaClass }) {
                error("Duplicate behavior")
            }

            container.behaviors.add(behavior)
            behavior.onAdded(container)
        }

        require(container.behaviors.remove(this)) { "Failed to clean up behavior source" }
    }
}

operator fun CellBehavior.times(b: CellBehavior) = CellBehaviorSource().also { it.add(this).add(b) }

operator fun CellBehaviorSource.times(b: CellBehavior) = this.add(b)

/**
 * Container for multiple [CellBehavior]s. It is a Set. As such, there may be one instance of each behavior type.
 * */
class CellBehaviorContainer(private val cell: Cell) : DataEntity {
    val behaviors = ArrayList<CellBehavior>()

    fun addBehaviorInstance(b: CellBehavior) {
        if (behaviors.any { it.javaClass == b.javaClass }) {
            error("Duplicate behavior $b")
        }

        if (b is DataEntity) {
            dataNode.withChild(b.dataNode)
        }

        behaviors.add(b)

        b.onAdded(this)
    }

    fun forEach(action: ((CellBehavior) -> Unit)) = behaviors.forEach(action)
    inline fun <reified T : CellBehavior> getOrNull(): T? = behaviors.first { it is T } as? T
    inline fun <reified T : CellBehavior> get(): T = getOrNull() ?: error("Failed to get behavior")

    // todo remove
    inline fun <reified T : CellBehavior> add(behavior: T): CellBehaviorContainer {
        if (behaviors.any { it is T }) {
            error("Duplicate add behavior $behavior")
        }

        behaviors.add(behavior)

        if (behavior is DataEntity) {
            dataNode.withChild(behavior.dataNode)
        }

        behavior.onAdded(this)

        return this
    }

    fun destroy(behavior: CellBehavior) {
        require(behaviors.remove(behavior)) { "Illegal behavior remove $behavior" }

        if (behavior is DataEntity) {
            dataNode.children.removeIf { access -> access == behavior.dataNode }
        }

        behavior.destroy()
    }

    fun destroy() {
        behaviors.toList().forEach { destroy(it) }
    }

    override val dataNode: DataNode = DataNode()
}

fun interface ElectricalPowerAccessor {
    fun get(): Double
}

/**
 * Integrates electrical power into energy. Injection is supported using [PowerField].
 * */
class ElectricalPowerConverterBehavior(private val accessor: ElectricalPowerAccessor) : CellBehavior {
    @Inj
    constructor(powerField: PowerField) : this(powerField.read)

    var energy: Double = 0.0
    var deltaEnergy: Double = 0.0

    override fun onAdded(container: CellBehaviorContainer) {}

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::simulationTick)
    }

    private fun simulationTick(dt: Double, p: SubscriberPhase) {
        deltaEnergy = accessor.get() * dt
        energy += deltaEnergy
    }
}

fun interface ThermalBodyAccessor {
    fun get(): ThermalBody
}

fun ThermalBodyAccessor.temperature(): TemperatureAccessor = TemperatureAccessor {
    this.get().tempK
}

/**
 * Converts dissipated electrical energy to thermal energy. Injection is supported using [ThermalBody]
 * */
class ElectricalHeatTransferBehavior(private val bodyAccessor: ThermalBodyAccessor) : CellBehavior {
    @Inj
    constructor(body: ThermalBody) : this({ body })

    private lateinit var converterBehavior: ElectricalPowerConverterBehavior

    override fun onAdded(container: CellBehaviorContainer) {
        converterBehavior = container.get()
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::simulationTick)
    }

    private fun simulationTick(dt: Double, p: SubscriberPhase) {
        bodyAccessor.get().energy += converterBehavior.deltaEnergy
        converterBehavior.energy -= converterBehavior.deltaEnergy
    }
}

fun CellBehaviorContainer.withElectricalPowerConverter(accessor: ElectricalPowerAccessor): CellBehaviorContainer =
    this.add(ElectricalPowerConverterBehavior(accessor))

fun CellBehaviorContainer.withElectricalHeatTransfer(getter: ThermalBodyAccessor): CellBehaviorContainer =
    this.add(ElectricalHeatTransferBehavior(getter))

fun interface TemperatureAccessor {
    fun get(): Double
}

fun interface ExplosionConsumer {
    fun explode()
}

data class TemperatureExplosionBehaviorOptions(
    /**
     * If the temperature is above this threshold, [increaseSpeed] will be used to increase the explosion score.
     * Otherwise, [decayRate] will be used to decrease it.
     * */
    val temperatureThreshold: Double = Temperature.from(600.0, ThermalUnits.CELSIUS).kelvin,

    /**
     * The score increase speed.
     * This value is scaled by the difference between the temperature and the threshold.
     * */
    val increaseSpeed: Double = 0.1,

    /**
     * The score decrease speed. This value is not controlled by temperature.
     * */
    val decayRate: Double = 0.25,
)

/**
 * The [TemperatureExplosionBehavior] will destroy the game object if a temperature is held
 * above a threshold for a certain time period, as specified in [TemperatureExplosionBehaviorOptions]
 * A **score** is used to determine if the object should blow up. The score is increased when the temperature is above threshold
 * and decreased when the temperature is under threshold. Once a score of 1 is reached, the explosion is enqueued
 * using the [Scheduler]
 * The explosion uses an [ExplosionConsumer] to access the game object. [ExplosionConsumer.explode] is called from the game thread.
 * If no consumer is specified, a default one is used. Currently, only [CellPart] is implemented.
 * Injection is supported using [TemperatureAccessor], [TemperatureField]
 * */
class TemperatureExplosionBehavior(
    val temperatureAccessor: TemperatureAccessor,
    val options: TemperatureExplosionBehaviorOptions,
    val consumer: ExplosionConsumer,
) : CellBehavior, WailaEntity {
    private var score = 0.0
    private var enqueued = false

    @Inj
    constructor(temperatureAccessor: TemperatureAccessor, options: TemperatureExplosionBehaviorOptions, cell: Cell) :
        this(temperatureAccessor, options, { defaultNotifier(cell) })

    @Inj
    constructor(temperatureAccessor: TemperatureAccessor, consumer: ExplosionConsumer) :
        this(temperatureAccessor, TemperatureExplosionBehaviorOptions(), consumer)

    @Inj
    constructor(temperatureField: TemperatureField, options: TemperatureExplosionBehaviorOptions, cell: Cell) :
        this(temperatureField::readK, options, cell)

    @Inj
    constructor(temperatureField: TemperatureField, cell: Cell) :
        this(temperatureField::readK, TemperatureExplosionBehaviorOptions(), cell)


    override fun onAdded(container: CellBehaviorContainer) {}

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addSubscriber(SubscriberOptions(10, SubscriberPhase.Post), this::simulationTick)
    }

    private fun simulationTick(dt: Double, phase: SubscriberPhase) {
        val temperature = temperatureAccessor.get()

        if (temperature > options.temperatureThreshold) {
            val difference = temperature - options.temperatureThreshold

            score += options.increaseSpeed * difference * dt
        } else {
            score -= options.decayRate * dt
        }

        if (score >= 1) {
            blowUp()
        }

        score = score.coerceIn(0.0, 1.0)
    }

    private fun blowUp() {
        if (!enqueued) {
            enqueued = true

            schedulePre(0) {
                consumer.explode()
            }
        }
    }

    override fun appendWaila(builder: WailaTooltipBuilder, config: IPluginConfig?) {
        builder.text("Explode", score.formattedPercentN())
    }

    companion object {
        fun defaultNotifier(cell: Cell) {
            val container = cell.container ?: return

            if (container is MultipartBlockEntity) {
                if (container.isRemoved) {
                    return
                }

                val part = container.getPart(cell.pos.requireLocator<FaceLocator>())
                    ?: return

                val level = (part.placement.level as ServerLevel)

                level.destroyPart(part, true)
            } else {
                error("Cannot explode $container")
            }
        }
    }
}

fun CellBehaviorContainer.withExplosionBehavior(
    temperatureAccessor: TemperatureAccessor,
    options: TemperatureExplosionBehaviorOptions,
    explosionNotifier: ExplosionConsumer,
): CellBehaviorContainer {

    this.add(TemperatureExplosionBehavior(temperatureAccessor, options, explosionNotifier))

    return this
}

fun CellBehaviorContainer.withStandardExplosionBehavior(
    cell: Cell,
    threshold: Double,
    temperatureAccessor: TemperatureAccessor,
): CellBehaviorContainer {
    return withExplosionBehavior(temperatureAccessor, TemperatureExplosionBehaviorOptions(threshold, 0.1, 0.25)) {
        val container = cell.container ?: return@withExplosionBehavior

        if (container is MultipartBlockEntity) {
            if (container.isRemoved) {
                return@withExplosionBehavior
            }

            val part = container.getPart(cell.pos.requireLocator<FaceLocator>())
                ?: return@withExplosionBehavior

            val level = (part.placement.level as ServerLevel)

            level.destroyPart(part, true)
        } else {
            error("Cannot explode $container")
        }
    }
}

// todo remove

/**
 * Registers a set of standard cell behaviors:
 * - [ElectricalPowerConverterBehavior]
 *      - converts power into energy
 * - [ElectricalHeatTransferBehavior]
 *      - moves energy to the heat mass from the electrical converter
 * - [TemperatureExplosionBehavior]
 *      - explodes part when a threshold temperature is held for a certain time period
 * */
fun standardBehavior(cell: Cell, power: ElectricalPowerAccessor, thermal: ThermalBodyAccessor) =
    ElectricalPowerConverterBehavior(power) *
        ElectricalHeatTransferBehavior(thermal) *
        TemperatureExplosionBehavior(
            thermal.temperature(),
            TemperatureExplosionBehaviorOptions(
                temperatureThreshold = 600.0,
                increaseSpeed = 0.1,
                decayRate = 0.25
            )
        ) {
            val container = cell.container
                ?: return@TemperatureExplosionBehavior

            if (container is MultipartBlockEntity) {
                if (container.isRemoved) {
                    return@TemperatureExplosionBehavior
                }

                val part = container.getPart(cell.pos.requireLocator<FaceLocator>())
                    ?: return@TemperatureExplosionBehavior

                val level = (part.placement.level as ServerLevel)

                level.destroyPart(part, true)
            } else {
                error("Cannot explode $container")
            }
        }
