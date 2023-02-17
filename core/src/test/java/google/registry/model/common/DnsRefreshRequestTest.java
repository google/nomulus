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

import com.google.common.collect.ImmutableList;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.EntityTestCase;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DnsRefreshRequest}. */
class DnsRefreshRequestTest extends EntityTestCase {

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
  }
}
