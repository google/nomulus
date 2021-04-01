// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.io.Resources;
import google.registry.util.ResourceUtils;
import google.registry.util.SqlTemplate;
import org.joda.time.YearMonth;

/**
 * DNS Count query for the basic case.
 */
public class BasicDnsCountQueryCoordinator implements DnsCountQueryCoordinator {

  BasicDnsCountQueryCoordinator(DnsCountQueryCoordinator.Params params) {}

  @Override
  public String createQuery(YearMonth yearMonth) {
    return SqlTemplate.create(
            ResourceUtils.readResourceUtf8(
                Resources.getResource(this.getClass(), "sql/" + "dns_counts.sql")))
        .build();
  }

  @Override
  public void prepareForQuery(YearMonth yearMonth) {}
}
