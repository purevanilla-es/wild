package com.github.hornta.wild;

import com.github.hornta.carbon.ICommandHandler;
import com.github.hornta.wild.events.TeleportEvent;
import com.github.hornta.wild.events.PreTeleportEvent;
import com.github.hornta.carbon.message.MessageManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class WildCommand implements ICommandHandler, Listener {
  private HashMap<UUID, RandomLocation> tasks = new HashMap<>();
  private HashMap<UUID, Long> playerCooldowns = new HashMap<>();
  private HashMap<UUID, Long> immortals = new HashMap<>();

  public void handle(CommandSender commandSender, String[] args, int numTypedArgs) {
    Player player;
    boolean checkCooldown;
    World world;
    double payAmount = 0;
    Economy economy;
    boolean showActionBarMessage = false;

    if (args.length >= 1) {
      player = Bukkit.getPlayer(args[0]);
    } else {
      player = (Player) commandSender;
    }

    if (tasks.containsKey(player.getUniqueId())) {
      return;
    }

    PreTeleportEvent preEvent = new PreTeleportEvent(TeleportCause.COMMAND, player);
    Bukkit.getPluginManager().callEvent(preEvent);
    if(preEvent.isCancelled()) {
      return;
    }

    if(preEvent.getOverrideLocation() != null) {
      player.teleport(preEvent.getOverrideLocation(), PlayerTeleportEvent.TeleportCause.COMMAND);
      player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
      player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(""));

      TeleportEvent teleportEvent = new TeleportEvent(preEvent.getOverrideLocation(), TeleportCause.COMMAND, player);
      Bukkit.getPluginManager().callEvent(teleportEvent);
      return;
    }

    if (numTypedArgs == 0 || (commandSender instanceof Player && player == commandSender)) {
      double amount = Wild.getInstance().getConfiguration().get(ConfigKey.CHARGE_AMOUNT);
      economy = Wild.getInstance().getEconomy();
      if (
        !player.hasPermission("wild.bypasscharge") &&
          economy != null &&
          (boolean)Wild.getInstance().getConfiguration().get(ConfigKey.CHARGE_ENABLED) &&
          amount != 0.0
      ) {
        if (economy.getBalance(player) < amount) {
          MessageManager.setValue("required", economy.format(amount));
          MessageManager.setValue("current", economy.format(economy.getBalance(player)));
          MessageManager.sendMessage(player, MessageKey.CHARGE);
          return;
        } else {
          payAmount = amount;
        }
      }
    }

    if (args.length == 0 || (commandSender instanceof Player && player == commandSender)) {
      checkCooldown = true;
      showActionBarMessage = true;
    } else {
      checkCooldown = false;
    }

    if (numTypedArgs == 0) {
      world = getWorldFromTarget(Wild.getInstance().getConfiguration().get(ConfigKey.WILD_DEFAULT_WORLD), player);
    } else if (numTypedArgs == 1) {
      world = player.getWorld();
    } else {
      world = Bukkit.getWorld(args[1]);
    }

    if (payAmount > 0) {
      checkCooldown = false;
    }

    if (world.getEnvironment() != World.Environment.NORMAL) {
      MessageManager.sendMessage(commandSender, MessageKey.ONLY_OVERWORLD);
      return;
    }

    if (args.length == 0) {
      List<String> disabledWorlds = Wild.getInstance().getConfiguration().get(ConfigKey.DISABLED_WORLDS);
      if (disabledWorlds.contains(world.getName())) {
        MessageManager.sendMessage(player, MessageKey.WORLD_DISABLED);
        return;
      }
    }

    long now = System.currentTimeMillis();
    if (checkCooldown && !player.hasPermission("wild.bypasscooldown") && playerCooldowns.containsKey(player.getUniqueId())) {
      long expire = playerCooldowns.get(player.getUniqueId());
      if (expire > now) {
        Util.setTimeUnitValues();
        MessageManager.setValue("time_left", Util.getTimeLeft((int) (expire - now) / 1000));
        MessageManager.sendMessage(player, MessageKey.COOLDOWN);
        return;
      }
    }

    if (showActionBarMessage) {
      player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(MessageManager.getMessage(MessageKey.SEARCHING_ACTION_BAR)));
    }

    RandomLocation randomLocation = new RandomLocation(player, world, payAmount);
    tasks.put(player.getUniqueId(), randomLocation);
    randomLocation.findLocation((Location loc) -> {
      if(loc != null) {
        if (randomLocation.getPayAmount() > 0) {
          EconomyResponse response = Wild.getInstance().getEconomy().withdrawPlayer(player, randomLocation.getPayAmount());
          if (response.type == EconomyResponse.ResponseType.SUCCESS) {
            MessageManager.setValue("amount", Wild.getInstance().getEconomy().format(randomLocation.getPayAmount()));
            MessageManager.sendMessage(player, MessageKey.CHARGE_SUCCESS);
          }
        }

        int immortal_duration = Wild.getInstance().getConfiguration().get(ConfigKey.IMMORTAL_DURATION_AFTER_TELEPORT);
        if (immortal_duration > 0) {
          immortals.put(player.getUniqueId(), System.currentTimeMillis() + immortal_duration);
        }
        player.teleport(loc, PlayerTeleportEvent.TeleportCause.COMMAND);
        playerCooldowns.put(player.getUniqueId(), now + (int) Wild.getInstance().getConfiguration().get(ConfigKey.COOLDOWN) * 1000);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(""));

        TeleportEvent teleportEvent = new TeleportEvent(loc, TeleportCause.COMMAND, player);
        Bukkit.getPluginManager().callEvent(teleportEvent);
      } else {
        MessageManager.sendMessage(commandSender, MessageKey.WILD_NOT_FOUND);
      }

      tasks.remove(player.getUniqueId());
    });
  }

  @EventHandler
  void onPlayerDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player)) {
      return;
    }

    Player player = (Player) event.getEntity();
    if (immortals.containsKey(player.getUniqueId())) {
      long expire = immortals.get(player.getUniqueId());
      if (System.currentTimeMillis() >= expire) {
        immortals.remove(player.getUniqueId());
      } else {
        event.setCancelled(true);
      }
    }
  }

  @EventHandler
  void onPlayerJoin(PlayerJoinEvent event) {
    if ((boolean)Wild.getInstance().getConfiguration().get(ConfigKey.WILD_ON_FIRST_JOIN_ENABLED) && !event.getPlayer().hasPlayedBefore()) {
      PreTeleportEvent preEvent = new PreTeleportEvent(TeleportCause.FIRST_JOIN, event.getPlayer());
      Bukkit.getPluginManager().callEvent(preEvent);
      if(preEvent.isCancelled()) {
        return;
      }

      if(preEvent.getOverrideLocation() != null) {
        Bukkit.getScheduler().runTaskLater(Wild.getInstance(), () -> {
          event.getPlayer().teleport(preEvent.getOverrideLocation(), PlayerTeleportEvent.TeleportCause.COMMAND);
          event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);

          TeleportEvent teleportEvent = new TeleportEvent(preEvent.getOverrideLocation(), TeleportCause.FIRST_JOIN, event.getPlayer());
          Bukkit.getPluginManager().callEvent(teleportEvent);
        }, 1);
        return;
      }

      String worldTarget = Wild.getInstance().getConfiguration().get(ConfigKey.WILD_ON_FIRST_JOIN_WORLD);
      World world = getWorldFromTarget(worldTarget, event.getPlayer());
      RandomLocation randomLocation = new RandomLocation(event.getPlayer(), world, 0);
      randomLocation.findLocation((Location loc) -> {
        if (loc != null) {
          Bukkit.getScheduler().runTaskLater(Wild.getInstance(), () -> {
            event.getPlayer().teleport(loc, PlayerTeleportEvent.TeleportCause.COMMAND);
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);

            TeleportEvent teleportEvent = new TeleportEvent(loc, TeleportCause.FIRST_JOIN, event.getPlayer());
            Bukkit.getPluginManager().callEvent(teleportEvent);
          }, 1);
        }
      });
    }
  }

  @EventHandler
  void onPlayerRespawn(PlayerRespawnEvent event) {
    if (Wild.getInstance().getConfiguration().get(ConfigKey.WILD_ON_DEATH_ENABLED)) {
      PreTeleportEvent preEvent = new PreTeleportEvent(TeleportCause.RESPAWN, event.getPlayer());
      Bukkit.getPluginManager().callEvent(preEvent);
      if(preEvent.isCancelled()) {
        return;
      }

      if(preEvent.getOverrideLocation() != null) {
        event.setRespawnLocation(preEvent.getOverrideLocation());
        event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
        TeleportEvent teleportEvent = new TeleportEvent(preEvent.getOverrideLocation(), TeleportCause.RESPAWN, event.getPlayer());
        Bukkit.getPluginManager().callEvent(teleportEvent);
        return;
      }

      String worldTarget = Wild.getInstance().getConfiguration().get(ConfigKey.WILD_ON_DEATH_WORLD);
      World world = getWorldFromTarget(worldTarget, event.getPlayer());
      RandomLocation randomLocation = new RandomLocation(event.getPlayer(), world, 0);
      randomLocation.findLocation((Location loc) -> {
        if (loc != null) {
          event.getPlayer().teleport(loc, PlayerTeleportEvent.TeleportCause.COMMAND);
          event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
          TeleportEvent teleportEvent = new TeleportEvent(loc, TeleportCause.RESPAWN, event.getPlayer());
          Bukkit.getPluginManager().callEvent(teleportEvent);
        }
      });
    }
  }

  private World getWorldFromTarget(String target, Player player) {
    World world;
    switch (target) {
      case "@same":
        world = player.getWorld();
        break;
      case "@random":
        List<String> disabledWorlds = Wild.getInstance().getConfiguration().get(ConfigKey.DISABLED_WORLDS);
        List<World> worlds = Bukkit.getWorlds().stream().filter((World w) -> {
          return w.getEnvironment() == World.Environment.NORMAL && !disabledWorlds.contains(w.getName());
        }).collect(Collectors.toList());
        if(worlds.isEmpty()) {
          world = player.getWorld();
        } else {
          world = worlds.get(Util.randInt(0, worlds.size() - 1));
        }
        break;
      default:
        world = Bukkit.getWorld(target);
    }

    if (world == null) {
      world = player.getWorld();
      Wild.getInstance().getLogger().log(Level.WARNING, "A world couldn't be found with target `" + target + "`");
    }

    return world;
  }
}
