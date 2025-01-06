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

package google.registry.bsa.api;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.api.JsonSerializations.toCompletedOrdersReport;
import static google.registry.bsa.api.JsonSerializations.toInProgressOrdersReport;
import static google.registry.bsa.api.JsonSerializations.toUnblockableDomainsReport;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import google.registry.bsa.api.BlockOrder.OrderType;
import google.registry.bsa.api.UnblockableDomain.Reason;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JsonSerializations}. */
class JsonSerializationsTest {

  @Test
  void toJson_inProgress_oneOrder() {
    // Below can be replaced with TextBlock in Java 15+
    // Put lines in an ImmutableList to preserve the look of the text both inside and outside IDE:
    // the parameter name blurbs inserted by IDE shift the indentation. Same for all tests below.
    String expected =
        Joiner.on('\n')
            .join(
                ImmutableList.of(
                    "[",
                    "  {",
                    "    \"blockOrderId\": 1,",
                    "    \"status\": \"ActivationInProgress\"",
                    "  }",
                    "]"));
    assertThat(toInProgressOrdersReport(Stream.of(BlockOrder.create(1, OrderType.CREATE))))
        .hasValue(expected);
  }

  @Test
  void toJson_inProgress_multiOrders() {
    String expected =
        Joiner.on('\n')
            .join(
                ImmutableList.of(
                    "[",
                    "  {",
                    "    \"blockOrderId\": 1,",
                    "    \"status\": \"ActivationInProgress\"",
                    "  },",
                    "  {",
                    "    \"blockOrderId\": 2,",
                    "    \"status\": \"ReleaseInProgress\"",
                    "  }",
                    "]"));
    assertThat(
            toInProgressOrdersReport(
                Stream.of(
                    BlockOrder.create(1, OrderType.CREATE),
                    BlockOrder.create(2, OrderType.DELETE))))
        .hasValue(expected);
  }

  @Test
  void toJson_completed_oneOrder() {
    String expected =
        Joiner.on('\n')
            .join(
                ImmutableList.of(
                    "[",
                    "  {",
                    "    \"blockOrderId\": 1,",
                    "    \"status\": \"Active\"",
                    "  }",
                    "]"));
    assertThat(toCompletedOrdersReport(Stream.of(BlockOrder.create(1, OrderType.CREATE))))
        .hasValue(expected);
  }

  @Test
  void toJson_completed_multiOrders() {
    String expected =
        Joiner.on('\n')
            .join(
                ImmutableList.of(
                    "[",
                    "  {",
                    "    \"blockOrderId\": 1,",
                    "    \"status\": \"Active\"",
                    "  },",
                    "  {",
                    "    \"blockOrderId\": 2,",
                    "    \"status\": \"Closed\"",
                    "  }",
                    "]"));
    assertThat(
            toCompletedOrdersReport(
                Stream.of(
                    BlockOrder.create(1, OrderType.CREATE),
                    BlockOrder.create(2, OrderType.DELETE))))
        .hasValue(expected);
  }

  @Test
  void toJson_UnblockableDomains_empty() {
    assertThat(toUnblockableDomainsReport(Stream.<UnblockableDomain>of())).isEmpty();
  }

  @Test
  void toJson_UnblockableDomains_notEmpty() {
    String expected =
        Joiner.on('\n')
            .join(
                ImmutableList.of(
                    "{",
                    "  \"invalid\": [",
                    "    \"b.app\"",
                    "  ],",
                    "  \"registered\": [",
                    "    \"a.ing\",",
                    "    \"d.page\"",
                    "  ],",
                    "  \"reserved\": [",
                    "    \"c.dev\"",
                    "  ]",
                    "}"));
    assertThat(
            toUnblockableDomainsReport(
                Stream.of(
                    new UnblockableDomain("a.ing", Reason.REGISTERED),
                    new UnblockableDomain("b.app", Reason.INVALID),
                    new UnblockableDomain("c.dev", Reason.RESERVED),
                    new UnblockableDomain("d.page", Reason.REGISTERED))))
        .hasValue(expected);
  }
}
