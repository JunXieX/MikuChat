package com.junxiex.mikuchat.module.channel;

import com.junxiex.mikuchat.core.service.ChannelScope;
import com.junxiex.mikuchat.core.service.ChannelView;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 频道实例，由 channels/*.yml 加载。
 */
public final class Channel implements ChannelView {

    private final String id;
    private final String displayName;
    private final ChannelScope scope;
    private final String permission;
    private final String formatId;
    private final String antispamProfile;
    private final String filterProfile;
    private final boolean log;

    public Channel(String id, ConfigurationSection section) {
        this.id = id;
        this.displayName = section.getString("display-name", id);
        this.scope = "NETWORK".equalsIgnoreCase(section.getString("scope", "SERVER"))
                ? ChannelScope.NETWORK : ChannelScope.SERVER;
        String perm = section.getString("permission", "");
        this.permission = perm == null || perm.isBlank() ? null : perm;
        this.formatId = blankToNull(section.getString("format"));
        this.antispamProfile = blankToNull(section.getString("antispam"));
        this.filterProfile = blankToNull(section.getString("filter"));
        this.log = section.getBoolean("log", true);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public ChannelScope scope() {
        return scope;
    }

    @Override
    public String permission() {
        return permission;
    }

    @Override
    public String formatId() {
        return formatId;
    }

    @Override
    public String antispamProfile() {
        return antispamProfile;
    }

    @Override
    public String filterProfile() {
        return filterProfile;
    }

    @Override
    public boolean log() {
        return log;
    }

    @Override
    public boolean valid() {
        return true;
    }
}
