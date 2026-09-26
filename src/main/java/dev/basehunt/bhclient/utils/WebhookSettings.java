package dev.basehunt.bhclient.utils;

import dev.basehunt.bhclient.modules.WebhookNotifier;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.List;

/**
 * The webhook block every notifying module exposes. Keeping it in one place means the URL, username and
 * mention options behave identically everywhere instead of drifting between modules.
 */
public class WebhookSettings {
    public final Setting<Boolean> enabled;
    public final Setting<String> url;
    public final Setting<String> username;
    public final Setting<Boolean> mentionEveryone;
    public final Setting<String> mentionRoleId;
    public final Setting<Boolean> onlyOnServers;
    public final Setting<List<String>> serverWhitelist;

    public WebhookSettings(SettingGroup group) {
        enabled = group.add(new BoolSetting.Builder()
            .name("webhook-enabled")
            .description("Send this event to a Discord webhook.")
            .defaultValue(false)
            .build()
        );

        url = group.add(new StringSetting.Builder()
            .name("webhook-url")
            .description("Discord webhook URL. Stored in plain text in your Meteor config folder.")
            .defaultValue("")
            .wide()
            .visible(enabled::get)
            .build()
        );

        username = group.add(new StringSetting.Builder()
            .name("webhook-username")
            .description("Override the webhook display name.")
            .defaultValue("Base Hunting Client")
            .visible(enabled::get)
            .build()
        );

        mentionEveryone = group.add(new BoolSetting.Builder()
            .name("mention-everyone")
            .description("Prepend an @everyone mention to webhook messages.")
            .defaultValue(false)
            .visible(enabled::get)
            .build()
        );

        mentionRoleId = group.add(new StringSetting.Builder()
            .name("mention-role-id")
            .description("Role ID to ping, or leave blank to disable.")
            .defaultValue("")
            .visible(enabled::get)
            .build()
        );

        onlyOnServers = group.add(new BoolSetting.Builder()
            .name("only-on-servers")
            .description("Only send webhooks when the server address matches the whitelist below.")
            .defaultValue(false)
            .visible(enabled::get)
            .build()
        );

        serverWhitelist = group.add(new StringListSetting.Builder()
            .name("server-whitelist")
            .description("Server addresses to send webhooks from. Uses 'contains' matching.")
            .visible(() -> enabled.get() && onlyOnServers.get())
            .build()
        );
    }

    public boolean isEnabled() {
        return enabled.get() && !url.get().isBlank() && passesWhitelist();
    }

    private boolean passesWhitelist() {
        if (!onlyOnServers.get()) return true;

        String address = ServerUtils.serverAddress();
        for (String allowed : serverWhitelist.get()) {
            if (!allowed.isBlank() && address.contains(allowed.trim())) return true;
        }

        return false;
    }

    public String prefix() {
        StringBuilder builder = new StringBuilder();

        if (mentionEveryone.get()) builder.append("@everyone ");
        if (!mentionRoleId.get().isBlank()) builder.append("<@&").append(mentionRoleId.get().trim()).append("> ");

        return builder.toString();
    }

    public boolean send(WebhookManager.Embed embed) {
        if (!isEnabled()) return false;

        // Embeds carry no text of their own, so the mention has to ride along as message content or the
        // @everyone / role ping settings would never fire.
        String prefix = prefix();
        return WebhookManager.get().enqueueEmbed(
            url.get(),
            username.get(),
            embed,
            prefix.isBlank() ? null : prefix
        );
    }

    public boolean send(String content) {
        if (!isEnabled()) return false;

        return WebhookManager.get().enqueue(url.get(), username.get(), prefix() + content);
    }

    /** True when the standalone webhook module is enabled but has been muted, so nothing should be sent. */
    public static boolean globallyMuted() {
        WebhookNotifier notifier = Modules.get().get(WebhookNotifier.class);
        return notifier != null && notifier.isActive() && notifier.isMuted();
    }
}
