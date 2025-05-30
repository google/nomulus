// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.dns.writer.powerdns;

import static com.google.common.base.Suppliers.memoize;
import static google.registry.util.ResourceUtils.readResourceUtf8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.RegistryEnvironment;
import google.registry.util.YamlUtils;
import jakarta.inject.Singleton;
import java.util.function.Supplier;

/**
 * Configuration manager for PowerDNS settings.
 *
 * <p>This class is responsible for loading the PowerDNS configuration from the YAML files, found in
 * the {@code files/power-dns} directory.
 */
public final class PowerDnsConfig {

  // expected config file locations for PowerDNS
  private static final String ENVIRONMENT_CONFIG_FORMAT = "files/power-dns/%s.yaml";
  private static final String YAML_CONFIG_PROD =
      readResourceUtf8(PowerDnsConfig.class, "files/power-dns/_default.yaml");

  /**
   * Loads the {@link PowerDnsConfigSettings} POJO from the YAML configuration files.
   *
   * <p>The {@code _default.yaml} file in this directory is loaded first, and a fatal error is
   * thrown if it cannot be found or if there is an error parsing it. Separately, the
   * environment-specific config file named {@code ENVIRONMENT.yaml} is also loaded and those values
   * merged into the POJO.
   */
  static PowerDnsConfigSettings getConfigSettings() {
    String configFilePath =
        String.format(
            ENVIRONMENT_CONFIG_FORMAT, Ascii.toLowerCase(RegistryEnvironment.get().name()));
    String customYaml = readResourceUtf8(PowerDnsConfig.class, configFilePath);
    return YamlUtils.getConfigSettings(YAML_CONFIG_PROD, customYaml, PowerDnsConfigSettings.class);
  }

  /** Dagger module that provides DNS configuration settings. */
  @Module
  public static final class PowerDnsConfigModule {

    /** Parsed PowerDNS configuration settings. */
    @Singleton
    @Provides
    static PowerDnsConfigSettings providePowerDnsConfigSettings() {
      return CONFIG_SETTINGS.get();
    }

    /** Base URL of the PowerDNS server. */
    @Provides
    @Config("powerDnsBaseUrl")
    public static String providePowerDnsBaseUrl(PowerDnsConfigSettings config) {
      return config.powerDns.baseUrl;
    }

    /** API key for the PowerDNS server. */
    @Provides
    @Config("powerDnsApiKey")
    public static String providePowerDnsApiKey(PowerDnsConfigSettings config) {
      return config.powerDns.apiKey;
    }

    /** Whether DNSSEC is enabled for the PowerDNS server. */
    @Provides
    @Config("powerDnsDnssecEnabled")
    public static Boolean providePowerDnsDnssecEnabled(PowerDnsConfigSettings config) {
      return config.powerDns.dnssecEnabled;
    }

    /** Whether TSIG is enabled for the PowerDNS server. */
    @Provides
    @Config("powerDnsTsigEnabled")
    public static Boolean providePowerDnsTsigEnabled(PowerDnsConfigSettings config) {
      return config.powerDns.tsigEnabled;
    }

    /** Default SOA MNAME for the TLD zone. */
    @Provides
    @Config("powerDnsRootNameServers")
    public static ImmutableList<String> providePowerDnsRootNameServers(
        PowerDnsConfigSettings config) {
      return ImmutableList.copyOf(config.powerDns.rootNameServers);
    }

    /** Default SOA RNAME for the TLD zone. */
    @Provides
    @Config("powerDnsSoaName")
    public static String providePowerDnsSoaName(PowerDnsConfigSettings config) {
      return config.powerDns.soaName;
    }

    private PowerDnsConfigModule() {}
  }

  /**
   * Memoizes loading of the {@link PowerDnsConfigSettings} POJO.
   *
   * <p>Memoizing without cache expiration is used because the app must be re-deployed in order to
   * change the contents of the YAML config files.
   */
  @VisibleForTesting
  public static final Supplier<PowerDnsConfigSettings> CONFIG_SETTINGS =
      memoize(PowerDnsConfig::getConfigSettings);

  private PowerDnsConfig() {}
}
