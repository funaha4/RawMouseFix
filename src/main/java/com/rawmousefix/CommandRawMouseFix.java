package com.rawmousefix;

import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/** {@code /rawmousefix <status|scale <factor>|enable|disable|reload>}. */
public class CommandRawMouseFix extends CommandBase {

    @Override
    public String getCommandName() {
        return "rawmousefix";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/rawmousefix <status|scale <factor>|enable|disable|reload>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        try {
            dispatch(sender, args);
        } catch (Throwable t) {
            reply(sender, EnumChatFormatting.RED + "[RawMouseFix] command failed: " + t);
            Log.error("command failed: %s", String.valueOf(t));
        }
    }

    private void dispatch(ICommandSender sender, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        if ("status".equals(sub)) {
            reply(sender, EnumChatFormatting.AQUA + "[RawMouseFix] " + EnumChatFormatting.RESET
                    + RawMouseFix.describeState());
            return;
        }

        if ("scale".equals(sub)) {
            if (args.length < 2) {
                usage(sender, "scale <0.05-20.0>");
                return;
            }
            try {
                RawMouseFix.setScaleFactor(Double.parseDouble(args[1]));
                reply(sender, EnumChatFormatting.GREEN + "scaleFactor is now "
                        + String.format(Locale.ROOT, "%.3f", RawMouseFix.getScaleFactor()));
            } catch (NumberFormatException e) {
                usage(sender, "not a number: " + args[1]);
            }
            return;
        }

        if ("enable".equals(sub) || "disable".equals(sub)) {
            boolean enable = "enable".equals(sub);
            RawMouseFix.setEnabledSetting(enable);
            reply(sender, EnumChatFormatting.GREEN + "raw input "
                    + (enable ? "enabled" : "disabled (vanilla mouse handling)"));
            return;
        }

        if ("reload".equals(sub)) {
            RawMouseFix.reloadConfiguration();
            reply(sender, EnumChatFormatting.GREEN + "config reloaded: " + RawMouseFix.describeState());
            return;
        }

        usage(sender, getCommandUsage(sender));
    }

    private void reply(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }

    private void usage(ICommandSender sender, String detail) {
        reply(sender, EnumChatFormatting.RED + detail);
        Log.warn("%s", detail);
    }
}
