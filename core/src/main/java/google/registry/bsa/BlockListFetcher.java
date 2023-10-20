package google.registry.bsa;

import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.client.http.HttpMethods;
import com.google.common.collect.ImmutableMap;
import google.registry.bsa.common.BlockList;
import google.registry.bsa.http.BsaCredential;
import google.registry.request.UrlConnectionService;
import google.registry.util.Retrier;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;

public class BlockListFetcher {

  private final UrlConnectionService urlConnectionService;
  private final BsaCredential credential;

  private final ImmutableMap<String, String> blockListUrls;
  private final Retrier retrier;

  @Inject
  BlockListFetcher(
      UrlConnectionService urlConnectionService,
      BsaCredential credential,
      ImmutableMap<String, String> blockListUrls,
      Retrier retrier) {
    this.urlConnectionService = urlConnectionService;
    this.credential = credential;
    this.blockListUrls = blockListUrls;
    this.retrier = retrier;
  }

  LazyBlockList fetch(BlockList blockList) {
    // TODO: use more informative exceptions to describe retriable errors
    return retrier.callWithRetry(() -> tryFetch(blockList), IOException.class);
  }

  LazyBlockList tryFetch(BlockList blockList) throws IOException {
    try {
      HttpsURLConnection connection =
          (HttpsURLConnection)
              urlConnectionService.createConnection(
                  new java.net.URL(blockListUrls.get(blockList.name())));
      connection.setRequestMethod(HttpMethods.GET);
      connection.setRequestProperty("Authorization", "Bearer " + credential.getAuthToken());
      int code = connection.getResponseCode();
      if (code != SC_OK) {
        // TODO: retry on code such as Access denied and service temporarily unavailable.
        throw new IOException(
            String.format("Error: %s, [%s]", code, connection.getResponseMessage()));
      }
      return new LazyBlockList(blockList, connection);
    } catch (GeneralSecurityException e) {
      throw new RuntimeException(e);
    }
  }

  class LazyBlockList implements Closeable {

    private final BlockList blockList;

    private final HttpsURLConnection connection;

    LazyBlockList(BlockList blockList, HttpsURLConnection connection) {
      this.blockList = blockList;
      this.connection = connection;
    }

    BlockList getName() {
      return blockList;
    }

    String peekChecksum() {
      return "TODO"; // Depends on BSA impl: header or first line of file
    }

    void consumeAll(BiConsumer<byte[], Integer> consumer) {
      try (InputStream inputStream = connection.getInputStream()) {
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
          consumer.accept(buffer, bytesRead);
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void close() {
      try {
        connection.disconnect();
      } catch (Throwable e) {
        // log it
      }
    }
  }
}
