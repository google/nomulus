// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.proxy;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.proxy.ProxyConfig.Environment.LOCAL;
import static google.registry.proxy.ProxyConfig.getProxyConfig;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.proxy.ProxyConfig.Environment;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ProxyModule}. */
class ProxyModuleTest {

  private static final ProxyConfig PROXY_CONFIG = getProxyConfig(LOCAL);
  private final ProxyModule proxyModule = new ProxyModule();

  @Test
  void testSuccess_parseArgs_defaultArgs() {
    String[] args = {};
    proxyModule.parse(args);
    assertThat(proxyModule.provideWhoisPort(PROXY_CONFIG)).isEqualTo(PROXY_CONFIG.whois.port);
    assertThat(proxyModule.provideEppPort(PROXY_CONFIG)).isEqualTo(PROXY_CONFIG.epp.port);
    assertThat(proxyModule.provideHealthCheckPort(PROXY_CONFIG))
        .isEqualTo(PROXY_CONFIG.healthCheck.port);
    assertThat(proxyModule.provideHttpWhoisProtocol(PROXY_CONFIG))
        .isEqualTo(PROXY_CONFIG.webWhois.httpPort);
    assertThat(proxyModule.provideHttpsWhoisProtocol(PROXY_CONFIG))
        .isEqualTo(PROXY_CONFIG.webWhois.httpsPort);
    assertThat(proxyModule.provideEnvironment()).isEqualTo(LOCAL);
    assertThat(proxyModule.log).isFalse();
  }

  @Test
  void testFailure_parseArgs_loggingInProduction() {
    String[] args = {"--env", "production", "--log"};
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              proxyModule.parse(args);
            });
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("Logging cannot be enabled for production environment");
  }

  @Test
  void testFailure_parseArgs_wrongArguments() {
    String[] args = {"--wrong_flag", "some_value"};
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> proxyModule.parse(args));
    assertThat(thrown).hasMessageThat().contains("--wrong_flag");
  }

  @Test
  void testSuccess_parseArgs_log() {
    String[] args = {"--log"};
    proxyModule.parse(args);
    assertThat(proxyModule.log).isTrue();
  }

  @Test
  void testSuccess_parseArgs_customWhoisPort() {
    String[] args = {"--whois", "12345"};
    proxyModule.parse(args);
    assertThat(proxyModule.provideWhoisPort(PROXY_CONFIG)).isEqualTo(12345);
  }

  @Test
  void testSuccess_parseArgs_customEppPort() {
    String[] args = {"--epp", "22222"};
    proxyModule.parse(args);
    assertThat(proxyModule.provideEppPort(PROXY_CONFIG)).isEqualTo(22222);
  }

  @Test
  void testSuccess_parseArgs_customHealthCheckPort() {
    String[] args = {"--health_check", "23456"};
    proxyModule.parse(args);
    assertThat(proxyModule.provideHealthCheckPort(PROXY_CONFIG)).isEqualTo(23456);
  }

  @Test
  void testSuccess_parseArgs_customhttpWhoisPort() {
    String[] args = {"--http_whois", "12121"};
    proxyModule.parse(args);
    assertThat(proxyModule.provideHttpWhoisProtocol(PROXY_CONFIG)).isEqualTo(12121);
  }

  @Test
  void testSuccess_parseArgs_customhttpsWhoisPort() {
    String[] args = {"--https_whois", "21212"};
    proxyModule.parse(args);
    assertThat(proxyModule.provideHttpsWhoisProtocol(PROXY_CONFIG)).isEqualTo(21212);
  }

  @Test
  void testSuccess_parseArgs_customEnvironment() {
    String[] args = {"--env", "ALpHa"};
    proxyModule.parse(args);
    assertThat(proxyModule.provideEnvironment()).isEqualTo(Environment.ALPHA);
  }

  @Test
  void testFailure_parseArgs_wrongEnvironment() {
    String[] args = {"--env", "beta"};
    ParameterException e = assertThrows(ParameterException.class, () -> proxyModule.parse(args));
    assertThat(e).hasMessageThat().contains("Invalid value for --env parameter");
  }
}
