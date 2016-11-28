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

import java.io.IOException;

import google.registry.testing.AppEngineRule;

import static com.google.common.truth.Truth.assertThat;

public class IcannTldReportingFieldsTest {

  private IcannTldReportingFields icannTld;

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Before
  public void init() {
    icannTld = new IcannTldReportingFields.Builder()
        .setTld("wtf")
        .setAgpExemptedDomainsCount(1)
        .setAgpExemptionRequestsCount(1)
        .setAgpExemptionsGrantedCount(1)
        .setTransferDisputedLostCount(1)
        .setTransferDisputedNondecisionCount(1)
        .setTransferDisputedWonCount(1)
        .setYearMonth(new YearMonth())
        .build();
  }

  //TLD

  @Test
  public void testShouldCreateTld() throws IOException {
    assertThat(icannTld.getTld()).contains("wtf");
    assertThat(icannTld.getAgpExemptedDomainsCount()).isEqualTo(1);
    assertThat(icannTld.getAgpExemptionRequestsCount()).isEqualTo(1);
    assertThat(icannTld.getAgpExemptionsGrantedCount()).isEqualTo(1);
    assertThat(icannTld.getTransferDisputedLostCount()).isEqualTo(1);
    assertThat(icannTld.getTransferDisputedNondecisionCount()).isEqualTo(1);
    assertThat(icannTld.getTransferDisputedWonCount()).isEqualTo(1);
    assertThat(icannTld.getYearMonth()).isEqualTo(new YearMonth());
  }

  @Test(expected = NullPointerException.class)
  public void testTldGetWithNullTld() {
    icannTld.asBuilder().setTld(null).build();
  }

  @Test(expected = NullPointerException.class)
  public void testTldGetWithNullYearMonth() {
    icannTld.asBuilder().setYearMonth(null).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testTldGetWithInvalidAgpExemptedDomainsCount() {
    icannTld.asBuilder().setAgpExemptedDomainsCount(-1).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testTldGetWithInvalidAgpExemptionRequestsCount() {
    icannTld.asBuilder().setAgpExemptionRequestsCount(-1).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testTldGetWithInvalidAgpExemptionsGrantedCount() {
    icannTld.asBuilder().setAgpExemptionsGrantedCount(-1).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testTldGetWithInvalidTransferDisputedLostCount() {
    icannTld.asBuilder().setTransferDisputedLostCount(-1).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testTldGetWithInvalidTransferDisputedNondecisionCount() {
    icannTld.asBuilder().setTransferDisputedNondecisionCount(-1).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testTldGetWithInvalidTransferDisputedWonCount() {
    icannTld.asBuilder().setTransferDisputedWonCount(-1).build();
  }
}

