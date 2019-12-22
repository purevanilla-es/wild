package com.github.hornta.wild;

import com.github.hornta.wild.engine.WildManager;
import com.github.hornta.wild.events.DropUnsafeLocationEvent;
import com.github.hornta.wild.events.FoundLocationEvent;
import com.github.hornta.wild.events.PollLocationEvent;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.LinkedList;

public class ProcessQueueTask extends BukkitRunnable {
  private final WildManager wildManager;

  public ProcessQueueTask(WildManager wildManager) {
    this.wildManager = wildManager;
  }

  @Override
  public void run() {
    PlayerSearch search = wildManager.getCurrentlyLooking().peek();
    if(search == null) {
      return;
    }

    WildPlugin.debug("%s is search for a location in world %s caused by %s", Bukkit.getPlayer(search.getUuid()).getName(), search.getWorld().getName(), search.getCause());
    LinkedList<Location> locations = wildManager.getLocations(search.getWorld());
    Location location = locations.poll();
    if(location == null) {
      WildPlugin.debug("Location not found... skipping");
      return;
    }

    Bukkit.getPluginManager().callEvent(new PollLocationEvent(location));

    PaperLib.getChunkAtAsync(location).thenAccept((Chunk c) -> {
      Bukkit.getScheduler().runTaskLater(wildManager.getPlugin(), () -> {
        Block highestBlock = location.getWorld().getHighestBlockAt((int)location.getX(), (int)location.getZ());
        if(!Util.isSafeStandBlock(highestBlock)) {
          search.incrementTries();
          DropUnsafeLocationEvent drop = new DropUnsafeLocationEvent(search, highestBlock.getLocation());
          Bukkit.getPluginManager().callEvent(drop);
          return;
        }
        wildManager.getCurrentlyLooking().poll();

        FoundLocationEvent event = new FoundLocationEvent(search, location);
        Bukkit.getPluginManager().callEvent(event);
      }, 0);
    });
  }
}
