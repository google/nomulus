// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.dns.DnsUtils;
import google.registry.model.EntityTestCase;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DnsRefreshRequest}. */
public class DnsRefreshRequestTest extends EntityTestCase {

  DnsRefreshRequestTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  private final DnsRefreshRequest request =
      new DnsRefreshRequest(
          DnsUtils.TargetType.DOMAIN, "test.example", "example", fakeClock.nowUtc());

  @Test
  void testPersistence() {
    assertThat(request.getLastProcessTime()).isEqualTo(START_OF_TIME);
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().insert(request));
    fakeClock.advanceOneMilli();
    ImmutableList<DnsRefreshRequest> requests = loadAllOf(DnsRefreshRequest.class);
    assertThat(requests.size()).isEqualTo(1);
    assertThat(requests.get(0)).isEqualTo(request);
  }

  @Test
  void testNullValues() {
    // type
    assertThrows(
        NullPointerException.class,
        () -> new DnsRefreshRequest(null, "test.example", "example", fakeClock.nowUtc()));
    // name
    assertThrows(
        NullPointerException.class,
        () ->
            new DnsRefreshRequest(DnsUtils.TargetType.DOMAIN, null, "example", fakeClock.nowUtc()));
    // tld
    assertThrows(
        NullPointerException.class,
        () ->
            new DnsRefreshRequest(
                DnsUtils.TargetType.DOMAIN, "test.example", null, fakeClock.nowUtc()));
    // request time
    assertThrows(
        NullPointerException.class,
        () -> new DnsRefreshRequest(DnsUtils.TargetType.DOMAIN, "test.example", "example", null));
  }

  @Test
  void testUpdateProcessTime() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> request.updateProcessTime(fakeClock.nowUtc())))
        .hasMessageThat()
        .contains("must be later than request time");

    fakeClock.advanceOneMilli();
    fakeClock.advanceOneMilli();

    DnsRefreshRequest newRequest = request.updateProcessTime(fakeClock.nowUtc());
    assertAboutImmutableObjects().that(newRequest).isEqualExceptFields(request, "lastProcessTime");
    assertThat(newRequest.getLastProcessTime()).isEqualTo(fakeClock.nowUtc());

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> newRequest.updateProcessTime(fakeClock.nowUtc().minusMillis(1))))
        .hasMessageThat()
        .contains("must be later than the old one");
  }
}
