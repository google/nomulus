/*
 *
 *  *  Copyright 2016 The Domain Registry Authors. All Rights Reserved.
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *
 */

package google.registry.config;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static com.google.common.truth.Truth.assertThat;

/**
 * Unit tests for {@link RegistryEnvironment}.
 */
@RunWith(JUnit4.class)
public class ConfigModuleTest {

  @Test
  public void testProvideDpmlTld() throws Exception {
    assertThat(ConfigModule.provideDpmlTld()).isEqualTo("dpml.zone");
  }

  @Test
  public void testProvideSshIdentity() throws Exception {
    assertThat(ConfigModule.provideSshIdentity()).isEqualTo("mercury-donuts-test@example.test");
  }

  @Test
  public void testProvideWhoisDisclaimer2() throws Exception {
    assertThat(ConfigModule.provideWhoisDisclaimer()).isEqualTo("Terms of Use: Users accessing the"
        + " Donuts WHOIS service must agree to use the data "
        + "only for lawful purposes, and under under no circumstances use the data to: Allow, "
        + "enable, or otherwise support the transmission by e-mail, telephone, or facsimile "
        + "of mass unsolicited, commercial advertising or solicitations to entities other "
        + "than the registrar's own existing customers. Enable high volume, automated, "
        + "electronic processes that send queries or data to the systems of Donuts or "
        + "any ICANN-accredited registrar, except as reasonably necessary to register "
        + "domain names or modify existing registrations. When using the Donuts Whois "
        + "service, please consider the following: The Whois service is not a replacement "
        + "for standard EPP commands to the SRS service. Whois is not considered authoritative "
        + "for registered domain objects. The Whois service may be scheduled for downtime "
        + "during production or OT&E maintenance periods. Queries to the Whois services are "
        + "throttled. If too many queries are received from a single IP address within a "
        + "specified time, the service will begin to reject further queries for a period of "
        + "time to prevent disruption of Whois service access.");
  }
}