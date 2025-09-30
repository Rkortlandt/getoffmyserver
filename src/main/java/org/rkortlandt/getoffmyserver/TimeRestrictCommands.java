package org.rkortlandt.getoffmyserver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.time.DayOfWeek;
import java.util.Locale;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class TimeRestrictCommands {

  public static void register() {
    CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
      dispatcher.register(literal("timerestrict")
              .requires(source -> source.hasPermissionLevel(2))
              .then(literal("bypass")
                      .then(literal("add").then(argument("player", StringArgumentType.word()).executes(TimeRestrictCommands::addBypassPlayer)))
                      .then(literal("remove").then(argument("player", StringArgumentType.word()).executes(TimeRestrictCommands::removeBypassPlayer)))
                      .then(literal("list").executes(TimeRestrictCommands::listBypassPlayers)))
              .then(literal("status").executes(TimeRestrictCommands::showStatus))
              .then(literal("set")
                      .then(argument("day", StringArgumentType.word())
                              .then(argument("times", StringArgumentType.word()).executes(TimeRestrictCommands::setDaySchedule))))
              .then(literal("clear")
                      .then(argument("day", StringArgumentType.word()).executes(TimeRestrictCommands::clearDaySchedule)))
      );
    });
  }

  private static int setDaySchedule(CommandContext<ServerCommandSource> context) {
    String dayStr = StringArgumentType.getString(context, "day").toUpperCase(Locale.ROOT);
    String timesStr = StringArgumentType.getString(context, "times");
    DayOfWeek day;
    try {
      day = DayOfWeek.valueOf(dayStr);
    } catch (IllegalArgumentException e) {
      context.getSource().sendFeedback(() -> Text.literal("§cInvalid day: " + dayStr), false);
      return 0;
    }

    try {
      String[] parts = timesStr.split("-");
      if (parts.length != 2) throw new IllegalArgumentException();
      GetOffMyServer.TimeRange newRange = new GetOffMyServer.TimeRange(GetOffMyServer.parseHHmm(parts[0]), GetOffMyServer.parseHHmm(parts[1]));
      GetOffMyServer.restrictionSchedule.put(day, newRange);
      GetOffMyServer.saveSettings();
      context.getSource().sendFeedback(() -> Text.literal("§aRestriction for " + day + " set to " + timesStr), true);
      return 1;
    } catch (Exception e) {
      context.getSource().sendFeedback(() -> Text.literal("§cInvalid format. Use Day HHmm-HHmm (e.g., MONDAY 2300-0700)."), false);
      return 0;
    }
  }

  private static int clearDaySchedule(CommandContext<ServerCommandSource> context) {
    String dayStr = StringArgumentType.getString(context, "day").toUpperCase(Locale.ROOT);
    DayOfWeek day;
    try {
      day = DayOfWeek.valueOf(dayStr);
    } catch (IllegalArgumentException e) {
      context.getSource().sendFeedback(() -> Text.literal("§cInvalid day: " + dayStr), false);
      return 0;
    }
    if (GetOffMyServer.restrictionSchedule.remove(day) != null) {
      GetOffMyServer.saveSettings();
      context.getSource().sendFeedback(() -> Text.literal("§aCleared restrictions for " + day), true);
    } else {
      context.getSource().sendFeedback(() -> Text.literal("§eNo restrictions were set for " + day), false);
    }
    return 1;
  }

  private static int showStatus(CommandContext<ServerCommandSource> context) {
    StringBuilder status = new StringBuilder("§6--- TimeRestrict Schedule ---\n");
    for (DayOfWeek day : DayOfWeek.values()) {
      GetOffMyServer.TimeRange range = GetOffMyServer.restrictionSchedule.get(day);
      String timeDisplay = "Off";
      if (range != null) {
        timeDisplay = range.start().format(GetOffMyServer.HHMM_FORMATTER) + "-" + range.end().format(GetOffMyServer.HHMM_FORMATTER);
      }
      String dayName = day.toString().substring(0, 1) + day.toString().substring(1).toLowerCase();
      status.append(String.format("§e%s: §f%s\n", dayName, timeDisplay));
    }
    context.getSource().sendFeedback(() -> Text.literal(status.toString()), false);
    return 1;
  }

  private static int addBypassPlayer(CommandContext<ServerCommandSource> context) {
    String playerName = StringArgumentType.getString(context, "player");
    if (GetOffMyServer.BYPASS_PLAYERS.contains(playerName)) {
      context.getSource().sendFeedback(() -> Text.literal("§cPlayer " + playerName + " is already on the bypass list."), false);
      return 0;
    }
    GetOffMyServer.BYPASS_PLAYERS.add(playerName);
    GetOffMyServer.saveBypassPlayers();
    context.getSource().sendFeedback(() -> Text.literal("§aAdded " + playerName + " to the bypass list."), true);
    return 1;
  }

  private static int removeBypassPlayer(CommandContext<ServerCommandSource> context) {
    String playerName = StringArgumentType.getString(context, "player");
    if (!GetOffMyServer.BYPASS_PLAYERS.contains(playerName)) {
      context.getSource().sendFeedback(() -> Text.literal("§cPlayer " + playerName + " is not on the bypass list."), false);
      return 0;
    }
    GetOffMyServer.BYPASS_PLAYERS.remove(playerName);
    GetOffMyServer.saveBypassPlayers();
    context.getSource().sendFeedback(() -> Text.literal("§aRemoved " + playerName + " from the bypass list."), true);
    return 1;
  }

  private static int listBypassPlayers(CommandContext<ServerCommandSource> context) {
    if (GetOffMyServer.BYPASS_PLAYERS.isEmpty()) {
      context.getSource().sendFeedback(() -> Text.literal("§6The bypass list is empty."), false);
    } else {
      String playerList = String.join(", ", GetOffMyServer.BYPASS_PLAYERS);
      context.getSource().sendFeedback(() -> Text.literal("§6Players on bypass list: §e" + playerList), false);
    }
    return 1;
  }
}