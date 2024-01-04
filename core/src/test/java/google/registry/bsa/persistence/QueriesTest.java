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

package google.registry.bsa.persistence;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.persistence.Queries.deleteBsaLabelByLabels;
import static google.registry.bsa.persistence.Queries.queryBsaDomainInUseByLabels;
import static google.registry.bsa.persistence.Queries.queryBsaLabelByLabels;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import google.registry.bsa.persistence.BsaDomainInUse.Reason;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link Queries}. */
class QueriesTest {

  FakeClock fakeClock = new FakeClock(DateTime.parse("2023-11-09T02:08:57.880Z"));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @BeforeEach
  void setup() {
    tm().transact(
            () -> {
              tm().putAll(
                      ImmutableList.of(
                          new BsaLabel("label1", fakeClock.nowUtc()),
                          new BsaLabel("label2", fakeClock.nowUtc()),
                          new BsaLabel("label3", fakeClock.nowUtc())));
              tm().putAll(
                      ImmutableList.of(
                          BsaDomainInUse.of("label1.app", Reason.REGISTERED),
                          BsaDomainInUse.of("label1.dev", Reason.RESERVED),
                          BsaDomainInUse.of("label2.page", Reason.REGISTERED),
                          BsaDomainInUse.of("label3.app", Reason.REGISTERED)));
            });
  }

  @Test
  void queryBsaDomainInUseByLabels_oneLabel() {
    assertThat(
            tm().transact(
                    () ->
                        queryBsaDomainInUseByLabels(ImmutableList.of("label1"))
                            .map(BsaDomainInUse::toVkey)
                            .collect(toImmutableList())))
        .containsExactly(
            BsaDomainInUse.vKey("label1", "app"), BsaDomainInUse.vKey("label1", "dev"));
  }

  @Test
  void queryBsaDomainInUseByLabels_twoLabels() {
    assertThat(
            tm().transact(
                    () ->
                        queryBsaDomainInUseByLabels(ImmutableList.of("label1", "label2"))
                            .map(BsaDomainInUse::toVkey)
                            .collect(toImmutableList())))
        .containsExactly(
            BsaDomainInUse.vKey("label1", "app"),
            BsaDomainInUse.vKey("label1", "dev"),
            BsaDomainInUse.vKey("label2", "page"));
  }

  @Test
  void queryBsaLabelByLabels_oneLabel() {
    assertThat(
            tm().transact(
                    () ->
                        queryBsaLabelByLabels(ImmutableList.of("label1"))
                            .collect(toImmutableList())))
        .containsExactly(new BsaLabel("label1", fakeClock.nowUtc()));
  }

  @Test
  void queryBsaLabelByLabels_twoLabels() {
    assertThat(
            tm().transact(
                    () ->
                        queryBsaLabelByLabels(ImmutableList.of("label1", "label2"))
                            .collect(toImmutableList())))
        .containsExactly(
            new BsaLabel("label1", fakeClock.nowUtc()), new BsaLabel("label2", fakeClock.nowUtc()));
  }

  @Test
  void deleteBsaLabelByLabels_oneLabel() {
    assertThat(tm().transact(() -> deleteBsaLabelByLabels(ImmutableList.of("label1"))))
        .isEqualTo(1);
    assertThat(tm().transact(() -> tm().loadAllOf(BsaLabel.class)))
        .containsExactly(
            new BsaLabel("label2", fakeClock.nowUtc()), new BsaLabel("label3", fakeClock.nowUtc()));
    assertThat(
            tm().transact(
                    () ->
                        tm().loadAllOfStream(BsaDomainInUse.class)
                            .map(BsaDomainInUse::toVkey)
                            .collect(toImmutableList())))
        .containsExactly(
            BsaDomainInUse.vKey("label2", "page"), BsaDomainInUse.vKey("label3", "app"));
  }

  @Test
  void deleteBsaLabelByLabels_twoLabels() {
    assertThat(tm().transact(() -> deleteBsaLabelByLabels(ImmutableList.of("label1", "label2"))))
        .isEqualTo(2);
    assertThat(tm().transact(() -> tm().loadAllOf(BsaLabel.class)))
        .containsExactly(new BsaLabel("label3", fakeClock.nowUtc()));
    assertThat(
            tm().transact(
                    () ->
                        tm().loadAllOfStream(BsaDomainInUse.class)
                            .map(BsaDomainInUse::toVkey)
                            .collect(toImmutableList())))
        .containsExactly(BsaDomainInUse.vKey("label3", "app"));
  }
}
