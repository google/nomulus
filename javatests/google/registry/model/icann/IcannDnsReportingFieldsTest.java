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

import org.joda.time.YearMonth;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import google.registry.testing.AppEngineRule;

import static com.google.common.truth.Truth.assertThat;

public class IcannDnsReportingFieldsTest {

  private IcannDnsReportingFields icannDns;

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Before
  public void init() {
    icannDns = new IcannDnsReportingFields.Builder()
        .setTld("wtf")
        .setDnsTcpQueriesReceivedCount(1)
        .setDnsTcpQueriesRespondedCount(1)
        .setDnsUdpQueriesReceivedCount(1)
        .setDnsUdpQueriesRespondedCount(1)
        .setYearMonth(new YearMonth())
        .build();
  }

  //DNS

  @Test
  public void testShouldCreateDns() {
    assertThat(icannDns.getTld()).contains("wtf");
    assertThat(icannDns.getDnsTcpQueriesReceivedCount()).isEqualTo(1);
    assertThat(icannDns.getDnsTcpQueriesRespondedCount()).isEqualTo(1);
    assertThat(icannDns.getDnsUdpQueriesReceivedCount()).isEqualTo(1);
    assertThat(icannDns.getDnsUdpQueriesRespondedCount()).isEqualTo(1);
    assertThat(icannDns.getYearMonth()).isEqualTo(new YearMonth());
  }

  @Test(expected = NullPointerException.class)
  public void testDnsGetWithNullTld() {
    icannDns.asBuilder().setTld(null).build();
  }

  @Test(expected = NullPointerException.class)
  public void testDnsGetWithNullYearMonth() {
    icannDns.asBuilder().setYearMonth(null).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testDnsGetWithInvalidDnsTcpQueriesReceivedCount() {
    icannDns.asBuilder().setDnsTcpQueriesReceivedCount(-1).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testDnsGetWithInvalidDnsTcpQueriesRespondedCount() {
    icannDns.asBuilder().setDnsTcpQueriesRespondedCount(-1).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testDnsGetWithInvalidDnsUdpQueriesReceivedCount() {
    icannDns.asBuilder().setDnsUdpQueriesReceivedCount(-1).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testDnsGetWithInvalidDnsUdpQueriesRespondedCount() {
    icannDns.asBuilder().setDnsUdpQueriesRespondedCount(-1).build();
  }
}

