package google.registry.bsa;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.bsa.common.Order;
import google.registry.bsa.common.Order.OrderType;
import java.util.stream.Stream;

final class JsonSerializer {

  private static final Gson GSON = new Gson();

  private JsonSerializer() {}

  private static final ImmutableMap<OrderType, String> IN_PROGRESS_STATUS_CONVERSION =
      ImmutableMap.of(
          Order.OrderType.CREATE,
          "ActivationInProgress",
          Order.OrderType.DELETE,
          "ReleaseInProgress");
  private static final ImmutableMap<OrderType, String> COMPLETED_ORDER_STATUS_CONVERSION =
      ImmutableMap.of(Order.OrderType.CREATE, "Active", Order.OrderType.DELETE, "Closed");

  public static String toInProgressStatusReport(Stream<Order> orders) {
    return toOrderStatusReport(orders, IN_PROGRESS_STATUS_CONVERSION);
  }

  public static String toCompletedStatusReport(Stream<Order> orders) {
    return toOrderStatusReport(orders, COMPLETED_ORDER_STATUS_CONVERSION);
  }

  private static String toOrderStatusReport(
      Stream<Order> orders, ImmutableMap<OrderType, String> typeToStringMap) {
    return GSON.toJson(
        orders
            .map(order -> toMap(order, typeToStringMap))
            .collect(ImmutableList.toImmutableList()));
  }

  private static ImmutableMap<?, ?> toMap(
      Order order, ImmutableMap<OrderType, String> typeToStringMap) {
    return ImmutableMap.of(
        "blockOrderId", order.orderId(), "status", typeToStringMap.get(order.orderType()));
  }
}
