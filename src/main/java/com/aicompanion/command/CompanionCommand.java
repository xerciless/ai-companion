package com.aicompanion.command;

import com.aicompanion.CompanionManager;
import com.aicompanion.brain.Blueprints;
import com.aicompanion.brain.CompanionBrain;
import com.aicompanion.data.CompanionConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * /companion ... - everything the Down Arrow menu does, also usable by typing.
 *
 *   /companion summon [name]       join the world
 *   /companion follow|stay|play|guard|wood|mine|collect|give|dance [name]
 *   /companion build house|tower|wall|bridge|shelter [name]
 *   /companion free|invincible|defend [name]   toggle settings
 *   /companion dismiss [name]      leave the world
 */
public final class CompanionCommand {
	private static final List<String> SIMPLE_ACTIONS = List.of(
			"follow", "come", "stay", "play", "guard", "wood", "collect", "give", "dance", "jump", "wave", "status", "inventory");

	private CompanionCommand() {
	}

	@FunctionalInterface
	private interface NamedAction {
		int run(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> named(String literal, NamedAction action) {
		return Commands.literal(literal)
				.executes(ctx -> action.run(ctx, null))
				.then(Commands.argument("name", StringArgumentType.word())
						.executes(ctx -> action.run(ctx, StringArgumentType.getString(ctx, "name"))));
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("companion").executes(CompanionCommand::help);

		root.then(named("summon", CompanionCommand::summon));
		root.then(named("join", CompanionCommand::summon));
		root.then(named("dismiss", (ctx, name) -> act(ctx, name, "leave")));
		root.then(named("leave", (ctx, name) -> act(ctx, name, "leave")));

		for (String a : SIMPLE_ACTIONS) {
			root.then(named(a, (ctx, name) -> act(ctx, name, a)));
		}

		LiteralArgumentBuilder<CommandSourceStack> build = Commands.literal("build");
		for (String kind : Blueprints.KINDS) {
			build.then(named(kind, (ctx, name) -> act(ctx, name, "build:" + kind)));
		}
		root.then(build);

		LiteralArgumentBuilder<CommandSourceStack> mine = Commands.literal("mine");
		mine.executes(ctx -> act(ctx, null, "mine"));
		mine.then(Commands.argument("name", StringArgumentType.word())
				.executes(ctx -> act(ctx, StringArgumentType.getString(ctx, "name"), "mine")));
		for (String ore : List.of("diamonds", "iron", "coal", "gold", "copper", "redstone", "lapis", "emeralds")) {
			mine.then(named(ore, (ctx, name) -> act(ctx, name, "mine:" + ore)));
		}
		root.then(mine);

		for (String setting : List.of("free", "invincible", "defend")) {
			root.then(named(setting, (ctx, name) -> act(ctx, name, setting + ":toggle")));
		}

		root.then(Commands.literal("list").executes(CompanionCommand::list));
		root.then(Commands.literal("reload").executes(ctx -> {
			CompanionConfig.load();
			ctx.getSource().sendSuccess(() -> Component.literal("AI Companion config reloaded."), false);
			return 1;
		}));

		dispatcher.register(root);
	}

	private static int summon(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String error = CompanionManager.summon(player, name);
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	private static int act(CommandContext<CommandSourceStack> ctx, String name, String action) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		CompanionBrain brain = CompanionManager.find(player, name);
		if (brain == null) {
			ctx.getSource().sendFailure(Component.literal("You don't have a companion here yet. Press the Down Arrow and click Join World!"));
			return 0;
		}
		String reply = brain.command(action, player);
		if (reply != null) {
			brain.say(reply);
		}
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		List<CompanionBrain> all = CompanionManager.all();
		if (all.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("No companions in the world right now."), false);
			return 1;
		}
		StringBuilder sb = new StringBuilder("Companions: ");
		for (int i = 0; i < all.size(); i++) {
			CompanionBrain b = all.get(i);
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(b.name).append(" (").append(b.sleeping ? "sleeping" : b.mode.description).append(")");
		}
		String text = sb.toString();
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.literal(
				"AI Companion: press the Down Arrow for the menu, or use /companion summon <name>, follow, stay, play, guard, "
						+ "wood, mine [diamonds], collect, give, build <house|tower|wall|bridge|shelter>, free, invincible, dismiss. "
						+ "You can also just talk to your companion in chat!"), false);
		return 1;
	}
}
