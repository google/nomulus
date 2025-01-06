// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
package google.registry.reporting.icann;

import static com.google.common.truth.Truth.assertThat;

import org.joda.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CloudDnsCountQueryCoordinator}. */
public class CloudDnsCountQueryCoordinatorTest {
  public CloudDnsCountQueryCoordinatorTest() {}

  private final YearMonth yearMonth = new YearMonth(2017, 9);
  CloudDnsCountQueryCoordinator coordinator = new CloudDnsCountQueryCoordinator();

  @BeforeEach
  public void setUp() {
    coordinator.projectId = "domain-registry-test";
    coordinator.icannReportingDataSet = "icann_reporting";
  }

  @Test
  public void testPreparatoryQueryConstruction() {
    assertThat(coordinator.getPlxDnsTableQuery(yearMonth))
        .isEqualTo(ReportingTestData.loadFile("prepare_dns_counts_cloud_test.sql"));
  }

  @Test
  public void testQueryCreation() {
    assertThat(coordinator.createQuery())
        .isEqualTo(ReportingTestData.loadFile("dns_counts_cloud_test.sql"));
  }
}
