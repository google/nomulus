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
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.EntityTestCase;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DnsRefreshRequest}. */
public class DnsRefreshRequestTest extends EntityTestCase {

  DnsRefreshRequestTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  DnsRefreshRequest request;

  @Test
  void testPersistence() {
    request =
        new DnsRefreshRequest(TargetType.DOMAIN, "test.example", "example", fakeClock.nowUtc());
    fakeClock.advanceOneMilli();
    insertInDb(request);
    fakeClock.advanceOneMilli();
    ImmutableList<DnsRefreshRequest> requests = loadAllOf(DnsRefreshRequest.class);
    assertThat(requests.size()).isEqualTo(1);
    assertThat(requests.get(0).getType()).isEqualTo(TargetType.DOMAIN);
    assertThat(requests.get(0).getName()).isEqualTo("test.example");
    assertThat(requests.get(0).getTld()).isEqualTo("example");
    assertThat(requests.get(0).getRequestTime()).isEqualTo(fakeClock.nowUtc().minusMillis(2));
    assertThat(requests.get(0).getProcessTime()).isEqualTo(START_OF_TIME);
  }

  @Test
  void testNullValues() {
    assertThrows(
        NullPointerException.class,
        () -> new DnsRefreshRequest(null, "test.exmaple", "example", fakeClock.nowUtc()));
    assertThrows(
        NullPointerException.class,
        () -> new DnsRefreshRequest(TargetType.DOMAIN, null, "example", fakeClock.nowUtc()));
    assertThrows(
        NullPointerException.class,
        () -> new DnsRefreshRequest(TargetType.DOMAIN, "test.example", null, fakeClock.nowUtc()));
    assertThrows(
        NullPointerException.class,
        () -> new DnsRefreshRequest(TargetType.DOMAIN, "test.example", "example", null));
  }

  @Test
  void testUpdateProcesstime() {
    request =
        new DnsRefreshRequest(TargetType.DOMAIN, "test.example", "example", fakeClock.nowUtc());
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> request.updateProcessTime(fakeClock.nowUtc())))
        .hasMessageThat()
        .contains("must be later than request time");

    fakeClock.advanceOneMilli();
    fakeClock.advanceOneMilli();

    DnsRefreshRequest newRequest = request.updateProcessTime(fakeClock.nowUtc());
    assertThat(newRequest.getType()).isEqualTo(request.getType());
    assertThat(newRequest.getName()).isEqualTo(request.getName());
    assertThat(newRequest.getTld()).isEqualTo(request.getTld());
    assertThat(newRequest.getRequestTime()).isEqualTo(request.getRequestTime());
    assertThat(newRequest.getProcessTime()).isEqualTo(fakeClock.nowUtc());

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> newRequest.updateProcessTime(fakeClock.nowUtc().minusMillis(1))))
        .hasMessageThat()
        .contains("must be later than the old one");
  }
}
