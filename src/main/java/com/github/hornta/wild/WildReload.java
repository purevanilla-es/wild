package com.github.hornta.wild;

import com.github.hornta.carbon.ICommandHandler;
import com.github.hornta.carbon.message.MessageManager;
import com.github.hornta.carbon.message.Translation;
import org.bukkit.command.CommandSender;

public class WildReload implements ICommandHandler {
  public void handle(CommandSender commandSender, String[] strings, int typedArgs) {
    try {
      Wild.getInstance().getConfiguration().reload();
    } catch (Exception e) {
      MessageManager.sendMessage(commandSender, MessageKey.CONFIGURATION_RELOAD_FAILED);
      return;
    }
    Translation translation = Wild.getInstance().getTranslations().createTranslation(Wild.getInstance().getConfiguration().get(ConfigKey.LANGUAGE));
    MessageManager.getInstance().setPrimaryTranslation(translation);
    MessageManager.sendMessage(commandSender, MessageKey.CONFIGURATION_RELOADED);
  }
}
