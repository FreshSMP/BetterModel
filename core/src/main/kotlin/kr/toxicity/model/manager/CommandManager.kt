/**
 * This source file is part of BetterModel.
 * Copyright (c) 2024–2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */
package kr.toxicity.model.manager

import io.papermc.paper.command.brigadier.CommandSourceStack
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.BetterModelPlugin.ReloadResult.*
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.pack.PackZipper
import kr.toxicity.model.api.tracker.EntityHideOption
import kr.toxicity.model.api.tracker.TrackerModifier
import kr.toxicity.model.api.version.MinecraftVersion
import kr.toxicity.model.command.*
import kr.toxicity.model.util.*
import net.kyori.adventure.text.format.NamedTextColor.*
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.incendo.cloud.bukkit.parser.location.LocationParser
import org.incendo.cloud.bukkit.parser.selector.MultipleEntitySelectorParser
import org.incendo.cloud.bukkit.parser.selector.SinglePlayerSelectorParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.paper.PaperCommandManager
import org.incendo.cloud.parser.standard.BooleanParser
import org.incendo.cloud.parser.standard.DoubleParser
import org.incendo.cloud.parser.standard.StringParser

object CommandManager : GlobalManager {

    private lateinit var commandManager: PaperCommandManager<CommandSourceStack>

    fun initialize(manager: PaperCommandManager<CommandSourceStack>) {
        this.commandManager = manager
    }

    override fun start() {

        val module = commandModule("bettermodel") {
            withAliases("bm")

            command("reload") {
                withAliases("re", "rl")
                withShortDescription("reloads this plugin.")
                executes { ctx -> reload(ctx.sender().sender) }
            }
            command("spawn") {
                withShortDescription("summons some model to given type")
                withAliases("s")
                withRequiredArgument("model", StringParser.stringParser())
                withOptionalArgument("type", StringParser.stringParser())
                withOptionalArgument("scale", DoubleParser.doubleParser(0.0))
                withOptionalArgument("location", LocationParser.locationParser())
                executes { ctx ->
                    val player = ctx.sender().sender as? Player
                        ?: return@executes ctx.sender().sender.audience().warn("Only players can use this command.")

                    spawn(player, ctx)
                }
            }
            command("test") {
                withShortDescription("Tests some model's animation to specific player")
                withAliases("t")
                withRequiredArgument("model", StringParser.stringParser())
                withRequiredArgument("animation", StringParser.stringParser())
                withOptionalArgument("player", SinglePlayerSelectorParser.singlePlayerSelectorParser())
                withOptionalArgument("location", LocationParser.locationParser())
                executes { ctx -> test(ctx.sender().sender, ctx) }
            }
            command("disguise") {
                withShortDescription("disguises self.")
                withAliases("d")
                withRequiredArgument("model", StringParser.stringParser())
                executes { ctx ->
                    val player = ctx.sender().sender as? Player
                        ?: return@executes ctx.sender().sender.audience().warn("Only players can use this command.")

                    disguise(player, ctx)
                }
            }
            command("undisguise") {
                withShortDescription("undisguises self.")
                withAliases("ud")
                withOptionalArgument("model", StringParser.stringParser())
                executes { ctx ->
                    val player = ctx.sender().sender as? Player
                        ?: return@executes ctx.sender().sender.audience().warn("Only players can use this command.")

                    undisguise(player, ctx)
                }
            }
            command("play") {
                withShortDescription("plays player animation.")
                withAliases("p")
                withRequiredArgument("limb", StringParser.stringParser())
                withRequiredArgument("animation", StringParser.stringParser())
                withOptionalArgument("loop_type", StringParser.stringParser())
                withOptionalArgument("hide", BooleanParser.booleanParser())
                executes { ctx ->
                    val player = ctx.sender().sender as? Player
                        ?: return@executes ctx.sender().sender.audience().warn("Only players can use this command.")

                    play(player, ctx)
                }
            }
            command("hide") {
                withShortDescription("hides some entities from target player.")
                withRequiredArgument("model", StringParser.stringParser())
                withRequiredArgument("player", SinglePlayerSelectorParser.singlePlayerSelectorParser())
                withRequiredArgument("entities", MultipleEntitySelectorParser.multipleEntitySelectorParser())

                executes { ctx -> hide(ctx.sender().sender, ctx) }
            }
            command("show") {
                withShortDescription("shows some entities to target player.")
                withRequiredArgument("model", StringParser.stringParser())
                withRequiredArgument("player", SinglePlayerSelectorParser.singlePlayerSelectorParser())
                withRequiredArgument("entities", MultipleEntitySelectorParser.multipleEntitySelectorParser())
                executes { ctx -> show(ctx.sender().sender, ctx) }
            }
            command("version") {
                withShortDescription("checks BetterModel's version.")
                withAliases("v")
                executes { ctx -> version(ctx.sender().sender) }
            }
        }

        module.build(commandManager)
    }

    private fun disguise(player: Player, ctx: CommandContext<CommandSourceStack>) {
        val model = ctx.mapToModel("model") { 
            player.audience().warn("Unable to find this model: $it")
            return
        }
        model.getOrCreate(player)
    }

    private fun hide(sender: CommandSender, ctx: CommandContext<CommandSourceStack>) {
        val model = ctx.get<String>("model")
        val player = ctx.get<Player>("player")
        val entities = ctx.get<List<Entity>>("entities")
        
        if (!entities.any { it.toRegistry()?.tracker(model)?.hide(player) == true }) {
            sender.audience().warn("Failed to hide any of provided entities.")
        }
    }

    private fun show(sender: CommandSender, ctx: CommandContext<CommandSourceStack>) {
        val model = ctx.get<String>("model")
        val player = ctx.get<Player>("player")
        val entities = ctx.get<List<Entity>>("entities")
        
        if (!entities.any { it.toRegistry()?.tracker(model)?.show(player) == true }) {
            sender.audience().warn("Failed to show any of provided entities.")
        }
    }

    private fun undisguise(player: Player, ctx: CommandContext<CommandSourceStack>) {
        val model = ctx.mapNullable<String>("model")
        if (model != null) {
            player.toTracker(model)?.close() 
                ?: player.audience().warn("Cannot find this model to undisguise: $model")
        } else {
            player.toRegistry()?.close() 
                ?: player.audience().warn("Cannot find any model to undisguise")
        }
    }

    private fun spawn(player: Player, ctx: CommandContext<CommandSourceStack>) {
        val model = ctx.mapToModel("model") { 
            player.audience().warn("Unable to find this model: $it")
            return
        }
        val type = ctx.mapNullableString("type") { str ->
            runCatching {
                EntityType.valueOf(str.uppercase())
            }.onFailure { _ ->
                player.audience().warn("Invalid entity type: '$str'. Using default.")
            }.getOrNull()
        } ?: EntityType.HUSK
        val scale = ctx.getOrDefault("scale", 1.0)
        val loc = ctx.optional<Location>("location").orElse(player.location)
        PLUGIN.scheduler().task(loc) {
            loc.run {
                (world ?: player.world).spawnEntity(this, type).apply {
                    if (PLUGIN.version() >= MinecraftVersion.V1_21 && this is LivingEntity) {
                        getAttribute(ATTRIBUTE_SCALE)?.baseValue = scale
                    }
                }
            }.takeIf {
                it.isValid
            }?.let { entity ->
                model.create(entity)
            } ?: player.audience().warn("Entity spawning has been blocked.")
        }
    }

    private fun version(sender: CommandSender) {
        val audience = sender.audience()
        audience.info("Searching version, please wait...")
        PLUGIN.scheduler().asyncTask {
            val version = LATEST_VERSION
            audience.infoNotNull(
                emptyComponentOf(),
                "Current: ${PLUGIN.semver()}".toComponent(),
                version.release?.let { v -> 
                    componentOf("Latest release: ") { append(v.toURLComponent()) } 
                },
                version.snapshot?.let { v -> 
                    componentOf("Latest snapshot: ") { append(v.toURLComponent()) } 
                }
            )
        }
    }

    private fun reload(sender: CommandSender) {
        val audience = sender.audience()
        PLUGIN.scheduler().asyncTask {
            audience.info("Start reloading. please wait...")
            when (val result = PLUGIN.reload(sender)) {
                is OnReload -> audience.warn("This plugin is still on reload!")
                is Success -> {
                    audience.info(
                        emptyComponentOf(),
                        "Reload completed. (${result.totalTime().withComma()}ms)".toComponent(GREEN),
                        "Assets reload time - ${result.assetsTime().withComma()}ms".toComponent {
                            color(GRAY)
                            hoverEvent("Reading all config and model.".toComponent().toHoverEvent())
                        },
                        "Packing time - ${result.packingTime().withComma()}ms".toComponent {
                            color(GRAY)
                            hoverEvent("Packing all model to resource pack.".toComponent().toHoverEvent())
                        },
                        "${BetterModel.models().size.withComma()} of models are loaded successfully. (${result.length().toByteFormat()})".toComponent(YELLOW),
                        (if (result.packResult.changed()) 
                            "${result.packResult.size().withComma()} of files are zipped." 
                        else 
                            "Zipping is skipped due to the same result.").toComponent(YELLOW),
                        emptyComponentOf()
                    )
                }
                is Failure -> {
                    audience.warn(
                        emptyComponentOf(),
                        "Reload failed.".toComponent(),
                        "Please read the log to find the problem.".toComponent(),
                        emptyComponentOf()
                    )
                    audience.warn()
                    result.throwable.handleException("Reload failed.")
                }
            }
        }
    }

    private fun play(player: Player, ctx: CommandContext<CommandSourceStack>) {
        val audience = player.audience()
        val limb = ctx.mapToLimb("limb") { 
            audience.warn("Unable to find this limb: $it")
            return
        }
        val animation = ctx.mapString("animation") { 
            limb.animation(it).orElse(null)?.also { anim -> return@mapString anim }
            audience.warn("Unable to find this animation: $it")
            return
        }
        val loopType = ctx.mapNullableString("loop_type") {
            runCatching {
                AnimationIterator.Type.valueOf(it.uppercase())
            }.onFailure { _ ->
                audience.warn("Invalid loop type: '$it'. Using default.")
            }.getOrNull()
        }
        val hide = ctx.mapNullable<Boolean>("hide") != false
        limb.getOrCreate(player, TrackerModifier.DEFAULT) {
            it.hideOption(if (hide) EntityHideOption.DEFAULT else EntityHideOption.FALSE)
        }.run {
            if (!animate(animation, AnimationModifier(0, 0, loopType), ::close)) close()
        }
    }

    private fun test(sender: CommandSender, ctx: CommandContext<CommandSourceStack>) {
        val audience = sender.audience()
        val model = ctx.mapToModel("model") { 
            audience.warn("Unable to find this model: $it")
            return
        }
        val animation = ctx.mapString("animation") { str -> 
            model.animation(str).orElse(null)?.also { anim -> return@mapString anim }
            audience.warn("Unable to find this animation: $str")
            return
        }
        val player = ctx.optional<Player>("player").orElse(null) ?: (sender as? Player ?: run {
            audience.warn("Unable to find target player.")
            return
        })
        val location = ctx.optional<Location>("location").orElse(null) ?: player.location.apply {
            add(Vector(0, 0, 10).rotateAroundY(-Math.toRadians(yaw.toDouble())))
            yaw += 180
        }
        PLUGIN.scheduler().task(location) {
            model.create(location).run {
                spawn(player)
                animate(animation, AnimationModifier(0, 0, AnimationIterator.Type.PLAY_ONCE), ::close)
            }
        }
    }

    override fun reload(pipeline: ReloadPipeline, zipper: PackZipper) {

    }

    override fun end() {

    }
}