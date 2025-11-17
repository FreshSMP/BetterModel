/**
 * This source file is part of BetterModel.
 * Copyright (c) 2024–2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */
package kr.toxicity.model.command

import io.papermc.paper.command.brigadier.CommandSourceStack
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.data.renderer.ModelRenderer
import kr.toxicity.model.util.*
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextDecoration
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.paper.PaperCommandManager
import org.incendo.cloud.parser.ParserDescriptor
import org.incendo.cloud.parser.standard.IntegerParser

fun commandModule(name: String, block: CommandModule.() -> Unit): CommandModule {
    return CommandModule(null, name).apply(block)
}

inline fun <reified T : Any> CommandContext<CommandSourceStack>.map(name: String, ifNull: () -> T): T =
    getOrDefault(name, null) ?: ifNull()

inline fun <reified T : Any> CommandContext<CommandSourceStack>.map(name: String, ifNull: T): T =
    getOrDefault(name, ifNull)

inline fun <reified T : Any> CommandContext<CommandSourceStack>.map(name: String): T = get(name)

inline fun <reified T : Any> CommandContext<CommandSourceStack>.mapNullable(name: String): T? =
    getOrDefault(name, null)

inline fun <reified T : Any> CommandContext<CommandSourceStack>.mapString(name: String, mapper: (String) -> T): T =
    map<String>(name).let(mapper)

inline fun <reified T : Any> CommandContext<CommandSourceStack>.mapNullableString(name: String, mapper: (String) -> T?): T? =
    mapNullable<String>(name)?.let(mapper)

inline fun CommandContext<CommandSourceStack>.mapToModel(name: String, ifNotFound: (String) -> ModelRenderer): ModelRenderer =
    mapString(name) { BetterModel.modelOrNull(it) ?: ifNotFound(it) }

inline fun CommandContext<CommandSourceStack>.mapToLimb(name: String, ifNotFound: (String) -> ModelRenderer): ModelRenderer =
    mapString(name) { BetterModel.limbOrNull(it) ?: ifNotFound(it) }

class CommandModule(
    parent: CommandModule?,
    private val commandName: String
) {
    private companion object {
        const val PAGE_SPLIT_INDEX = 6

        val prefix = listOf(
            emptyComponentOf(),
            "------ BetterModel ${PLUGIN.semver()} ------".toComponent(GRAY),
            emptyComponentOf()
        )

        val fullPrefix = listOf(
            prefix,
            listOf(
                componentOf {
                    decorate(TextDecoration.BOLD)
                    append(spaceComponentOf())
                    append("[Wiki]".toComponent {
                        color(AQUA)
                        toURLComponent("https://github.com/toxicity188/BetterModel/wiki")
                    })
                    append(spaceComponentOf())
                    append("[Download]".toComponent {
                        color(GREEN)
                        toURLComponent("https://modrinth.com/plugin/bettermodel/versions")
                    })
                    append(spaceComponentOf())
                    append("[Discord]".toComponent {
                        color(BLUE)
                        toURLComponent("https://discord.com/invite/rePyFESDbk")
                    })
                },
                emptyComponentOf(),
                componentOf(
                    "    <arg>".toComponent(RED),
                    spaceComponentOf(),
                    " - required".toComponent()
                ),
                componentOf(
                    "    [arg]".toComponent(DARK_AQUA),
                    spaceComponentOf(),
                    " - optional".toComponent()
                ),
                emptyComponentOf()
            )
        ).flatten()

        fun TextComponent.Builder.toURLComponent(url: String) = hoverEvent(componentOf(
            url.toComponent(DARK_AQUA),
            lineComponentOf(),
            lineComponentOf(),
            "Click to open link.".toComponent()
        ).toHoverEvent()).clickEvent(ClickEvent.openUrl(url))

        fun String.toTypeName() = lowercase().replace('_', ' ')
    }

    private val rootName: String = parent?.rootName?.let { "$it $commandName" } ?: commandName
    private val rootPermission: String = parent?.rootPermission?.let { "$it.$commandName" } ?: commandName

    private val rootNameComponent = "/$rootName".toComponent(YELLOW)
    private val sub = mutableMapOf<String, SubCommand>()
    private val maxPage get() = sub.size / PAGE_SPLIT_INDEX + 1
    private val helpComponentRange get() = 1..maxPage
    private val helpComponents by lazy {
        helpComponentRange.map { index ->
            (if (index == 1) fullPrefix else prefix).toMutableList()
                .also { list ->
                    sub.values.toList().subList(PAGE_SPLIT_INDEX * (index - 1), (PAGE_SPLIT_INDEX * index).coerceAtMost(sub.size)).forEach {
                        list += it.toComponent()
                    }
                    list += "/$rootName [help] [page] - help command.".toComponent(LIGHT_PURPLE)
                    list += emptyComponentOf()
                    list += "---------< Page $index / $maxPage >---------".toComponent(GRAY)
                }.toTypedArray()
        }
    }

    private var mainExecutor: ((CommandContext<CommandSourceStack>) -> Unit)? = null
    private var shortDesc: String = "No description"
    private val aliases = mutableListOf<String>()

    fun command(name: String, block: SubCommandBuilder.() -> Unit) {
        val builder = SubCommandBuilder(name, "$rootPermission.$name")
        builder.block()
        sub[name] = builder.build(rootNameComponent, rootName)
    }

    fun commandModule(name: String, block: CommandModule.() -> Unit): CommandModule {
        return CommandModule(this, name).apply(block)
    }

    fun withShortDescription(description: String) {
        shortDesc = description
    }

    fun withAliases(vararg names: String) {
        aliases.addAll(names)
    }

    fun executes(executor: (CommandContext<CommandSourceStack>) -> Unit) {
        mainExecutor = executor
    }

    fun build(manager: PaperCommandManager<CommandSourceStack>) {
        manager.command(
            manager.commandBuilder(commandName, *aliases.toTypedArray())
                .permission(rootPermission)
                .literal("help", "h")
                .optional("page", IntegerParser.integerParser(1, maxPage))
                .handler { context ->
                    val sender = context.sender().sender
                    val page = context.getOrDefault("page", 1)
                        .coerceAtLeast(1)
                        .coerceAtMost(maxPage)
                    sender.audience().info(*helpComponents[page - 1])
                }
        )

        sub.values.forEach { subCmd ->
            var builder = manager.commandBuilder(commandName, *aliases.toTypedArray())
                .permission(rootPermission)
                .literal(subCmd.name, *subCmd.aliases.toTypedArray())
                .permission(subCmd.permission)

            subCmd.arguments.forEach { arg ->
                builder = if (arg.required) {
                    builder.required(arg.name, arg.parser)
                } else {
                    builder.optional(arg.name, arg.parser)
                }
            }

            manager.command(builder.handler(subCmd.executor))
        }

        mainExecutor?.let { executor ->
            manager.command(
                manager.commandBuilder(commandName, *aliases.toTypedArray())
                    .permission(rootPermission)
                    .optional("page", IntegerParser.integerParser(1, maxPage))
                    .handler { context ->
                        if (context.contains("page")) {
                            val page = context.get<Int>("page")
                                .coerceAtLeast(1)
                                .coerceAtMost(maxPage)
                            context.sender().sender.audience().info(*helpComponents[page - 1])
                        } else {
                            executor(context)
                        }
                    }
            )
        } ?: run {
            manager.command(
                manager.commandBuilder(commandName, *aliases.toTypedArray())
                    .permission(rootPermission)
                    .optional("page", IntegerParser.integerParser(1, maxPage))
                    .handler { context ->
                        val sender = context.sender().sender
                        val page = context.getOrDefault("page", 1)
                            .coerceAtLeast(1)
                            .coerceAtMost(maxPage)
                        sender.audience().info(*helpComponents[page - 1])
                    }
            )
        }
    }

    internal data class SubCommand(
        val name: String,
        val aliases: List<String>,
        val permission: String,
        val shortDescription: String,
        val arguments: List<ArgumentInfo>,
        val executor: (CommandContext<CommandSourceStack>) -> Unit,
        val rootNameComponent: TextComponent,
        val rootName: String
    ) {
        fun toComponent(): TextComponent = componentOf {
            append(rootNameComponent)
            append(spaceComponentOf())
            append(name.toComponent())
            arguments.forEach { arg ->
                append(spaceComponentOf())
                append(arg.toComponent())
            }
            append(" - ".toComponent(DARK_GRAY))
            append(shortDescription.toComponent(GRAY))
            hoverEvent(componentOf(
                if (aliases.isNotEmpty()) componentOf(
                    "Aliases:".toComponent(DARK_AQUA),
                    lineComponentOf(),
                    componentWithLineOf(*aliases.map(String::toComponent).toTypedArray())
                ) else emptyComponentOf(),
                lineComponentOf(),
                lineComponentOf(),
                "Click to suggest command.".toComponent()
            ).toHoverEvent())
            clickEvent(ClickEvent.suggestCommand("/$rootName $name"))
        }
    }

    internal data class ArgumentInfo(
        val name: String,
        val parser: ParserDescriptor<CommandSourceStack, *>,
        val required: Boolean,
        val typeName: String
    ) {
        fun toComponent(): TextComponent = componentOf {
            content(if (required) "<${name.toTypeName()}>" else "[${name.toTypeName()}]")
            color(if (required) RED else DARK_AQUA)
            hoverEvent(typeName.toTypeName().toComponent().toHoverEvent())
        }
    }

    class SubCommandBuilder(
        private val name: String,
        private val permission: String
    ) {
        private val aliases = mutableListOf<String>()
        private var shortDescription = "No description"
        private val arguments = mutableListOf<ArgumentInfo>()
        private var executor: ((CommandContext<CommandSourceStack>) -> Unit)? = null

        fun withAliases(vararg names: String) {
            aliases.addAll(names)
        }

        fun withShortDescription(description: String) {
            shortDescription = description
        }

        fun <T : Any> withRequiredArgument(
            name: String,
            parser: ParserDescriptor<CommandSourceStack, T>,
            typeName: String = parser.toString()
        ) {
            arguments.add(ArgumentInfo(name, parser, true, typeName))
        }

        fun <T : Any> withOptionalArgument(
            name: String,
            parser: ParserDescriptor<CommandSourceStack, T>,
            typeName: String = parser.toString()
        ) {
            arguments.add(ArgumentInfo(name, parser, false, typeName))
        }

        fun executes(executor: (CommandContext<CommandSourceStack>) -> Unit) {
            this.executor = executor
        }

        internal fun build(rootNameComponent: TextComponent, rootName: String): SubCommand {
            return SubCommand(
                name = name,
                aliases = aliases,
                permission = permission,
                shortDescription = shortDescription,
                arguments = arguments,
                executor = executor ?: { },
                rootNameComponent = rootNameComponent,
                rootName = rootName
            )
        }
    }
}