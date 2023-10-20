package google.registry.bsa;

import com.google.common.collect.ImmutableList;
import google.registry.bsa.common.Order;
import google.registry.bsa.common.UnblockableDomain;
import google.registry.bsa.http.BsaHttpClient;
import google.registry.util.MoreStreams;
import google.registry.util.Retrier;
import java.io.IOException;
import java.util.stream.Stream;
import javax.inject.Inject;

public class BsaBulkApiClient {

  private static final int UPLOAD_BATCH_SIZE = 100_000;

  private final BsaHttpClient httpClient;
  private final Retrier retrier;

  @Inject
  BsaBulkApiClient(BsaHttpClient httpClient, Retrier retrier) {
    this.httpClient = httpClient;
    this.retrier = retrier;
  }

  void startProcessingOrders(Stream<Order> orders) {
    String data = JsonSerializer.toInProgressStatusReport(orders);
    retrier.callWithRetry(() -> httpClient.reportOrderProcessingStatus(data), IOException.class);
  }

  void completeProcessingOrders(Stream<Order> orders) {
    String data = JsonSerializer.toCompletedStatusReport(orders);
    retrier.callWithRetry(() -> httpClient.reportOrderProcessingStatus(data), IOException.class);
  }

  void uploadUnblockableDomains(Stream<UnblockableDomain> unblockables) {
    MoreStreams.batchedStream(unblockables, UPLOAD_BATCH_SIZE)
        .forEach(this::uploadUnblockableDomains);
  }

  void uploadUnblockableDomains(ImmutableList<UnblockableDomain> unblockables) {
    // TODO
  }
}
