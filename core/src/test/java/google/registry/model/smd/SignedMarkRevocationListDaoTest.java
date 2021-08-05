// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.smd;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;

import com.google.common.collect.ImmutableMap;
import google.registry.model.EntityTestCase;
import org.junit.jupiter.api.Test;

public class SignedMarkRevocationListDaoTest extends EntityTestCase {

  public SignedMarkRevocationListDaoTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testSave_success() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save(list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load();
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list);
  }

  @Test
  void testSaveAndLoad_emptyList() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(fakeClock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.save(list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load();
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list, "revisionId");
  }

  @Test
  void testSave_multipleVersions() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save(list);
    assertThat(SignedMarkRevocationListDao.load().isSmdRevoked("mark", fakeClock.nowUtc()))
        .isTrue();

    // Now remove the revocation
    SignedMarkRevocationList secondList =
        SignedMarkRevocationList.create(fakeClock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.save(secondList);
    assertThat(SignedMarkRevocationListDao.load().isSmdRevoked("mark", fakeClock.nowUtc()))
        .isFalse();
  }

}
