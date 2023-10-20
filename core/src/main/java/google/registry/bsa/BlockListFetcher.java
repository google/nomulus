package google.registry.bsa;

import com.google.common.collect.ImmutableMap;
import google.registry.bsa.common.BlockList;
import google.registry.bsa.http.BsaCredential;
import google.registry.request.UrlConnectionService;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;

public class BlockListFetcher {

  private final UrlConnectionService urlConnectionService;
  private final BsaCredential credential;

  private final ImmutableMap<String, String> blockListUrls;

  @Inject
  BlockListFetcher(
      UrlConnectionService urlConnectionService,
      BsaCredential credential,
      ImmutableMap<String, String> blockListUrls) {
    this.urlConnectionService = urlConnectionService;
    this.credential = credential;
    this.blockListUrls = blockListUrls;
  }

  LazyBlockList fetch(BlockList blockList) {
    return null;
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

    // TODO: return Stream<byte[]>
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
