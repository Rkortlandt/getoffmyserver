package org.rkortlandt.getoffmyserver;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class GetOffMyServer implements ModInitializer {
  public static final Logger LOGGER = LoggerFactory.getLogger("timerestrict");
  private static final int KICK_CHECK_INTERVAL_TICKS = 600;
  private int tickCounter = 0;

  public record TimeRange(LocalTime start, LocalTime end) {}

  public static final Map<DayOfWeek, TimeRange> restrictionSchedule = new EnumMap<>(DayOfWeek.class);
  public static String kickMessageTemplate = "§cServer access is restricted on §6%day% §cbetween §e%start_time% §cand §e%end_time%.";
  public static final List<String> BYPASS_PLAYERS = new ArrayList<>();

  private static final File BYPASS_PLAYERS_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "timerestrict_bypass.txt");
  private static final File SETTINGS_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "timerestrict_settings.properties");
  static final DateTimeFormatter HHMM_FORMATTER = DateTimeFormatter.ofPattern("HHmm");

  @Override
  public void onInitialize() {
    loadSettings();
    loadBypassPlayers();
    TimeRestrictCommands.register();

    ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
      ServerPlayerEntity player = handler.getPlayer();
      if (isCurrentlyRestricted()) {
        if (!isPlayerExempt(player)) {
          handler.disconnect(Text.literal(getKickMessage()));
          LOGGER.info("Denied {} from joining during restricted hours (not on bypass list).", player.getName().getString());
        }
      }
    });

    ServerTickEvents.END_SERVER_TICK.register(server -> {
      tickCounter++;
      if (tickCounter < KICK_CHECK_INTERVAL_TICKS) return;
      tickCounter = 0;

      if (isCurrentlyRestricted()) {
        String finalKickMessage = getKickMessage();
        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
          if (!isPlayerExempt(player)) {
            player.networkHandler.disconnect(Text.literal(finalKickMessage));
            LOGGER.info("Kicking online player {} due to time/day restriction.", player.getName().getString());
          }
        }
      }
    });
  }

  private boolean isPlayerExempt(ServerPlayerEntity player) {
    return player.hasPermissionLevel(2) || BYPASS_PLAYERS.contains(player.getName().getString());
  }

  private boolean isCurrentlyRestricted() {
    LocalTime nowTime = LocalTime.now();
    DayOfWeek nowDay = LocalDate.now().getDayOfWeek();
    TimeRange todayRange = restrictionSchedule.get(nowDay);
    if (todayRange == null) return false;

    LocalTime startTime = todayRange.start();
    LocalTime endTime = todayRange.end();
    return startTime.isBefore(endTime)
            ? nowTime.isAfter(startTime) && nowTime.isBefore(endTime)
            : nowTime.isAfter(startTime) || nowTime.isBefore(endTime);
  }

  private String getKickMessage() {
    DayOfWeek nowDay = LocalDate.now().getDayOfWeek();
    TimeRange todayRange = restrictionSchedule.get(nowDay);
    if (todayRange == null) return "Server is currently restricted.";

    DateTimeFormatter readableFormat = DateTimeFormatter.ofPattern("HH:mm");
    return kickMessageTemplate
            .replace("%start_time%", todayRange.start().format(readableFormat))
            .replace("%end_time%", todayRange.end().format(readableFormat))
            .replace("%day%", nowDay.toString());
  }

  public static LocalTime parseHHmm(String timeStr) throws DateTimeParseException {
    return LocalTime.parse(timeStr, HHMM_FORMATTER);
  }

  public static void loadSettings() {
    restrictionSchedule.clear();
    Properties props = new Properties();
    if (SETTINGS_FILE.exists()) {
      try (FileInputStream in = new FileInputStream(SETTINGS_FILE)) {
        props.load(in);
      } catch (IOException e) {
        LOGGER.error("Failed to load settings file.", e);
      }
    }
    kickMessageTemplate = props.getProperty("kick.message", "§cServer access is restricted on §6%day% §cbetween §e%start_time% §cand §e%end_time%.");

    for (DayOfWeek day : DayOfWeek.values()) {
      String dayKey = day.toString().toLowerCase() + ".times";
      String timeValue = props.getProperty(dayKey);
      if (timeValue != null && !timeValue.equalsIgnoreCase("off")) {
        try {
          String[] parts = timeValue.split("-");
          if (parts.length == 2) {
            restrictionSchedule.put(day, new TimeRange(parseHHmm(parts[0]), parseHHmm(parts[1])));
          }
        } catch (Exception e) {
          LOGGER.error("Invalid time format for {} in settings. Expected HHmm-HHmm", day);
        }
      }
    }
    if (props.isEmpty()) {
      addDefaultSchedule();
    }
    saveSettings();
  }

  private static void addDefaultSchedule() {
    try {
      TimeRange weekendRange = new TimeRange(parseHHmm("2300"), parseHHmm("0700"));
      restrictionSchedule.put(DayOfWeek.SATURDAY, weekendRange);
      restrictionSchedule.put(DayOfWeek.SUNDAY, weekendRange);
    } catch (DateTimeParseException ignored) {}
  }

  public static void saveSettings() {
    Properties props = new Properties();
    props.setProperty("kick.message", kickMessageTemplate);
    for (DayOfWeek day : DayOfWeek.values()) {
      String dayKey = day.toString().toLowerCase() + ".times";
      TimeRange range = restrictionSchedule.get(day);
      if (range != null) {
        props.setProperty(dayKey, range.start().format(HHMM_FORMATTER) + "-" + range.end().format(HHMM_FORMATTER));
      } else {
        props.setProperty(dayKey, "off");
      }
    }
    try (FileOutputStream out = new FileOutputStream(SETTINGS_FILE)) {
      props.store(out, "TimeRestrict Mod Settings. Use format HHmm-HHmm (e.g. 2300-0700), or 'off'.");
    } catch (IOException e) {
      LOGGER.error("Failed to save settings file.", e);
    }
  }

  public static void loadBypassPlayers() {
    BYPASS_PLAYERS.clear();
    if (BYPASS_PLAYERS_FILE.exists()) {
      try (BufferedReader reader = new BufferedReader(new FileReader(BYPASS_PLAYERS_FILE))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.trim().isEmpty()) BYPASS_PLAYERS.add(line.trim());
        }
      } catch (IOException e) {
        LOGGER.error("Failed to load bypass players list.", e);
      }
    }
  }

  public static void saveBypassPlayers() {
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(BYPASS_PLAYERS_FILE))) {
      for (String playerName : BYPASS_PLAYERS) {
        writer.write(playerName);
        writer.newLine();
      }
    } catch (IOException e) {
      LOGGER.error("Failed to save bypass players list.", e);
    }
  }
}