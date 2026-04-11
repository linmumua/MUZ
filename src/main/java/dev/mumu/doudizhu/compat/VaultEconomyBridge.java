package dev.mumu.doudizhu.compat;

import dev.mumu.doudizhu.DoudizhuPlugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultEconomyBridge {
    private final DoudizhuPlugin plugin;
    private Economy economy;
    private String statusDetail = "未检测";
    private String providerPluginName;
    private String availableProvidersDetail = "无";

    public VaultEconomyBridge(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean refreshConnection(List<String> preferredNames) {
        Plugin vault = plugin.getServer().getPluginManager().getPlugin("Vault");
        if (vault == null) {
            economy = null;
            statusDetail = "未安装";
            providerPluginName = null;
            availableProvidersDetail = "无";
            return false;
        }
        if (!vault.isEnabled()) {
            economy = null;
            statusDetail = "已安装但未启用";
            providerPluginName = null;
            availableProvidersDetail = "无";
            return false;
        }
        Collection<RegisteredServiceProvider<Economy>> registrations = plugin.getServer().getServicesManager().getRegistrations(Economy.class);
        availableProvidersDetail = describeRegistrations(registrations);
        if (registrations == null || registrations.isEmpty()) {
            economy = null;
            providerPluginName = null;
            statusDetail = "未找到 Economy Provider | 已注册: " + availableProvidersDetail;
            return false;
        }
        RegisteredServiceProvider<Economy> registration = selectRegistration(registrations, preferredNames);
        if (registration == null || registration.getProvider() == null) {
            economy = null;
            providerPluginName = null;
            statusDetail = "未找到可用的 Economy Provider | 已注册: " + availableProvidersDetail;
            return false;
        }
        Economy provider = registration.getProvider();
        if (!provider.isEnabled()) {
            economy = null;
            providerPluginName = registration.getPlugin() == null ? null : registration.getPlugin().getName();
            statusDetail = "Economy Provider 未启用: " + provider.getName() + " | 已注册: " + availableProvidersDetail;
            return false;
        }
        economy = provider;
        providerPluginName = registration.getPlugin() == null ? null : registration.getPlugin().getName();
        statusDetail = provider.getName() + ownerPluginSuffix() + " | 已注册: " + availableProvidersDetail;
        return true;
    }

    public boolean isHooked() {
        return economy != null;
    }

    public String statusDetail() {
        return statusDetail;
    }

    public String providerName() {
        return economy == null ? null : economy.getName();
    }

    public String providerPluginName() {
        return providerPluginName;
    }

    public String availableProvidersDetail() {
        return availableProvidersDetail;
    }

    public boolean ensureAccount(OfflinePlayer player) {
        if (!isHooked()) {
            return false;
        }
        if (economy.hasAccount(player)) {
            return true;
        }
        return economy.createPlayerAccount(player);
    }

    public double balance(OfflinePlayer player) {
        if (!isHooked() || player == null) {
            return 0.0;
        }
        return economy.getBalance(player);
    }

    public EconomyResponse withdraw(OfflinePlayer player, double amount) {
        if (!isHooked()) {
            return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, "Vault 未接入");
        }
        return economy.withdrawPlayer(player, amount);
    }

    public EconomyResponse deposit(OfflinePlayer player, double amount) {
        if (!isHooked()) {
            return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, "Vault 未接入");
        }
        return economy.depositPlayer(player, amount);
    }

    private RegisteredServiceProvider<Economy> selectRegistration(Collection<RegisteredServiceProvider<Economy>> registrations, List<String> preferredNames) {
        if (registrations == null || registrations.isEmpty()) {
            return null;
        }
        List<String> normalizedPreferred = normalizeNames(preferredNames);
        if (!normalizedPreferred.isEmpty()) {
            for (String preferred : normalizedPreferred) {
                for (RegisteredServiceProvider<Economy> registration : registrations) {
                    Economy provider = registration.getProvider();
                    String providerName = provider == null ? "" : provider.getName();
                    String pluginName = registration.getPlugin() == null ? "" : registration.getPlugin().getName();
                    if (matches(preferred, providerName) || matches(preferred, pluginName)) {
                        return registration;
                    }
                }
            }
        }
        RegisteredServiceProvider<Economy> direct = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (direct != null && direct.getProvider() != null) {
            return direct;
        }
        return registrations.iterator().next();
    }

    private List<String> normalizeNames(List<String> names) {
        List<String> normalized = new ArrayList<>();
        if (names == null) {
            return normalized;
        }
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                normalized.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private boolean matches(String expected, String actual) {
        return actual != null && !actual.isBlank() && actual.trim().toLowerCase(Locale.ROOT).contains(expected);
    }

    private String describeRegistrations(Collection<RegisteredServiceProvider<Economy>> registrations) {
        if (registrations == null || registrations.isEmpty()) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        for (RegisteredServiceProvider<Economy> registration : registrations) {
            Economy provider = registration.getProvider();
            String providerName = provider == null ? "unknown-provider" : provider.getName();
            String pluginName = registration.getPlugin() == null ? "unknown-plugin" : registration.getPlugin().getName();
            parts.add(providerName + "@" + pluginName);
        }
        return String.join(", ", parts);
    }

    private String ownerPluginSuffix() {
        return providerPluginName == null || providerPluginName.isBlank() ? "" : "@" + providerPluginName;
    }
}
