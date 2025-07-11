package kr.toxicity.model.compatibility.citizens.command

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.compatibility.citizens.trait.ModelTrait
import kr.toxicity.model.manager.ModelManagerImpl
import net.citizensnpcs.api.command.Arg
import net.citizensnpcs.api.command.Arg.CompletionsProvider
import net.citizensnpcs.api.command.Command
import net.citizensnpcs.api.command.CommandContext
import net.citizensnpcs.api.command.CommandMessages
import net.citizensnpcs.api.npc.NPC
import org.bukkit.command.CommandException
import org.bukkit.command.CommandSender

class ModelCommand {
    @Command(
        aliases = ["npc"],
        usage = "model [model]",
        desc = "",
        modifiers = ["model"],
        min = 1,
        max = 2,
        permission = "citizens.npc.model"
    )
    @Suppress("UNUSED")
    fun model(args: CommandContext, sender: CommandSender, npc: NPC?, @Arg(1, completionsProvider = TabComplete::class) model: String?) {
        if (npc == null) throw CommandException(CommandMessages.MUST_HAVE_SELECTED)
        npc.getOrAddTrait(ModelTrait::class.java).renderer = model?.let {
            ModelManagerImpl.renderer(it)
        }
        sender.sendMessage("Set ${npc.name}'s model to $model.")
    }

    private class TabComplete : CompletionsProvider {
        override fun getCompletions(p0: CommandContext?, p1: CommandSender?, p2: NPC?): MutableCollection<String> = BetterModel.plugin().modelManager().keys()
    }
}