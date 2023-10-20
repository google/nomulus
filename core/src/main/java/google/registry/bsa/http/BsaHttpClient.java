package google.registry.bsa.http;

import google.registry.bsa.common.BlockList;
import google.registry.request.UrlConnectionService;
import java.util.stream.Stream;
import javax.inject.Inject;

public class BsaHttpClient {

  private final UrlConnectionService urlConnectionService;

  @Inject
  BsaHttpClient(UrlConnectionService urlConnectionService) {
    this.urlConnectionService = urlConnectionService;
  }

  Stream<String> fetchBlockList(BlockList listName) {
    return null;
  }

  public void reportOrderProcessingStatus(String data) {}

  void addUnblockableDomains(String data) {}

  void removeUnblockableDomains(String data) {}
}
