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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Maps.newTreeMap;
import static com.google.common.collect.Multimaps.newListMultimap;
import static com.google.common.collect.Multimaps.toMultimap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import google.registry.bsa.api.BlockOrder.OrderType;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Helpers for generating {@link BlockOrder} and {@link UnblockableDomain} reports. */
public final class JsonSerializations {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private JsonSerializations() {}

  public static Optional<String> toInProgressOrdersReport(Stream<BlockOrder> orders) {
    ImmutableList<ImmutableMap<String, Object>> maps =
        orders.map(JsonSerializations::asInProgressOrder).collect(toImmutableList());
    if (maps.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(GSON.toJson(maps));
  }

  public static Optional<String> toCompletedOrdersReport(Stream<BlockOrder> orders) {
    ImmutableList<ImmutableMap<String, Object>> maps =
        orders.map(JsonSerializations::asCompletedOrder).collect(toImmutableList());
    if (maps.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(GSON.toJson(maps));
  }

  public static Optional<String> toUnblockableDomainsReport(Stream<UnblockableDomain> domains) {
    ImmutableMultimap<String, String> reasonToNames =
        ImmutableMultimap.copyOf(
            domains.collect(
                toMultimap(
                    domain -> domain.reason().name().toLowerCase(Locale.ROOT),
                    UnblockableDomain::domainName,
                    () -> newListMultimap(newTreeMap(), Lists::newArrayList))));

    if (reasonToNames.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(GSON.toJson(reasonToNames.asMap()));
  }

  public static Optional<String> toUnblockableDomainsRemovalReport(Stream<String> domainNames) {
    ImmutableList<String> domainsList = domainNames.collect(toImmutableList());
    if (domainsList.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(GSON.toJson(domainsList));
  }

  private static ImmutableMap<String, Object> asInProgressOrder(BlockOrder order) {
    String status =
        order.orderType().equals(OrderType.CREATE) ? "ActivationInProgress" : "ReleaseInProgress";
    return ImmutableMap.of("blockOrderId", order.orderId(), "status", status);
  }

  private static ImmutableMap<String, Object> asCompletedOrder(BlockOrder order) {
    String status = order.orderType().equals(OrderType.CREATE) ? "Active" : "Closed";
    return ImmutableMap.of("blockOrderId", order.orderId(), "status", status);
  }
}
