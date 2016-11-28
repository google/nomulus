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

public class IcannRegistrarReportingFieldsTest {

  private IcannRegistrarReportingFields icannRegistrar;

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Before
  public void init() {
    icannRegistrar = new IcannRegistrarReportingFields.Builder()
        .setClientId("1")
        .setTld("wtf")
        .setPreRampUpRegistrarsCount(1)
        .setRampUpRegistrarsCount(1)
        .setZfaPasswordsCount(1)
        .setYearMonth(new YearMonth())
        .build();
  }

  @Test
  public void testShouldCreateRegistrar() {
    assertThat(icannRegistrar.getClientId()).contains("1");
    assertThat(icannRegistrar.getTld()).contains("wtf");
    assertThat(icannRegistrar.getPreRampUpRegistrarsCount()).isEqualTo(1);
    assertThat(icannRegistrar.getRampUpRegistrarsCount()).isEqualTo(1);
    assertThat(icannRegistrar.getZfaPasswordsCount()).isEqualTo(1);
    assertThat(icannRegistrar.getYearMonth()).isEqualTo(new YearMonth());
  }

  @Test(expected = NullPointerException.class)
  public void testRegistrarGetWithNullTld() {
    icannRegistrar.asBuilder().setTld(null).build();
  }

  @Test(expected = NullPointerException.class)
  public void testRegistrarGetWithNullClientId() {
    icannRegistrar.asBuilder().setClientId(null).build();
  }

  @Test(expected = NullPointerException.class)
  public void testRegistrarGetWithNullYearMonth() {
    icannRegistrar.asBuilder().setYearMonth(null).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRegistrarGetWithInvalidPreRampUpRegistrarsCount() {
    icannRegistrar.asBuilder().setPreRampUpRegistrarsCount(-1).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRegistrarGetWithInvalidRampUpRegistrarsCount() {
    icannRegistrar.asBuilder().setRampUpRegistrarsCount(-1).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRegistrarGetWithInvalidZfaPasswordsCount() {
    icannRegistrar.asBuilder().setZfaPasswordsCount(-1).build();
  }
}
