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

package google.registry.model.icann;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

import google.registry.testing.AppEngineRule;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.icann.IcannReportField.Type.DNS;
import static google.registry.model.icann.IcannReportField.Type.REGISTRAR;
import static google.registry.model.icann.IcannReportField.Type.TLD;

public class IcannReportFieldTest {

  private IcannReportField icannRegistrar, icannDns, icannTld;

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Before
  public void init() {

    icannRegistrar = new IcannReportField.Builder()
        .setId("1")
        .setTld(".wtf")
        .setName("ramp-up-registrars")
        .setCount(1)
        .setMonth("01")
        .setYear("2016")
        .setType(IcannReportField.Type.REGISTRAR)
        .build();

    icannDns = new IcannReportField.Builder()
        .setId("1")
        .setName("dns-udp-queries-received")
        .setCount(1)
        .setMonth("01")
        .setYear("2016")
        .setType(IcannReportField.Type.DNS)
        .build();

    icannTld = new IcannReportField.Builder()
        .setId("1")
        .setTld(".wtf")
        .setName("transfer-disputed-won")
        .setCount(1)
        .setMonth("01")
        .setYear("2016")
        .setType(IcannReportField.Type.TLD)
        .build();
  }

  @Test
  public void testShouldCreateRegistrar() {
    assertThat(icannRegistrar.getId()).contains("1");
    assertThat(icannRegistrar.getName()).contains("ramp-up-registrars");
    assertThat(icannRegistrar.getTld()).contains(".wtf");
    assertThat(icannRegistrar.getType()).isEqualTo(REGISTRAR);
    assertThat(icannRegistrar.getYear()).contains("2016");
    assertThat(icannRegistrar.getMonth()).contains("01");
    assertThat(icannRegistrar.getCount()).isEqualTo(1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidRegistrar() {
    icannRegistrar.asBuilder().setName("invalid").build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRegistrarGetWithInvalidCount() {
    icannRegistrar.asBuilder().setCount(-1).build();
  }

  //DNS

  @Test
  public void testShouldCreateDns() {
    assertThat(icannDns.getId()).contains("1");
    assertThat(icannDns.getName()).contains("dns-udp-queries-received");
    assertThat(icannDns.getType()).isEqualTo(DNS);
    assertThat(icannDns.getYear()).contains("2016");
    assertThat(icannDns.getMonth()).contains("01");
    assertThat(icannDns.getCount()).isEqualTo(1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidDns() {
    icannDns.asBuilder().setName("invalid").build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testDnsGetWithInvalidCount() {
    icannDns.asBuilder().setCount(-1).build();
  }

  //TLD

  @Test
  public void testShouldCreateTld() throws IOException {
    assertThat(icannTld.getId()).contains("1");
    assertThat(icannTld.getName()).contains("transfer-disputed-won");
    assertThat(icannTld.getTld()).contains(".wtf");
    assertThat(icannTld.getType()).isEqualTo(TLD);
    assertThat(icannTld.getYear()).contains("2016");
    assertThat(icannTld.getMonth()).contains("01");
    assertThat(icannTld.getCount()).isEqualTo(1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidTld() {
    icannTld.asBuilder().setName("invalid").build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testTldGetWithInvalidCount() {
    icannTld.asBuilder().setCount(-1).build();
  }
}
