package google.registry.bsa;

import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.common.collect.ImmutableMap;
import google.registry.bsa.common.BlockList;
import google.registry.bsa.http.BsaCredential;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.stream.Stream;
import javax.inject.Inject;

public class BlockListFetcher {

  private final HttpTransport httpTransport;
  private final BsaCredential credential;

  private final ImmutableMap<String, String> blockListUrls;

  @Inject
  BlockListFetcher(
      HttpTransport httpTransport,
      BsaCredential credential,
      ImmutableMap<String, String> blockListUrls) {
    this.httpTransport = httpTransport;
    this.credential = credential;
    this.blockListUrls = blockListUrls;
  }

  LazyBlockList fetch(BlockList blockList) {
    return null;
  }

  class LazyBlockList implements Closeable {

    private final BlockList blockList;

    private final HttpResponse response;

    LazyBlockList(BlockList blockList, HttpResponse response) {
      this.blockList = blockList;
      this.response = response;
    }

    BlockList getName() {
      return blockList;
    }

    String peekChecksum() {
      return "TODO"; // Depends on BSA impl: header or first line of file
    }

    Stream<String> read() {
      try {
        return new BufferedReader(
                new InputStreamReader(response.getContent(), response.getContentCharset()))
            .lines();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void close() {
      try {
        response.disconnect();
      } catch (IOException e) {
        // log it
      }
    }
  }
}
