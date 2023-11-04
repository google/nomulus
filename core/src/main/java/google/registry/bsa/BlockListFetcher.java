// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa;

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.client.http.HttpMethods;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import google.registry.bsa.api.BsaCredential;
import google.registry.bsa.api.BsaException;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.UrlConnectionService;
import google.registry.util.Retrier;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;

/** Fetches data from the BSA API. */
public class BlockListFetcher {

  private final UrlConnectionService urlConnectionService;
  private final BsaCredential credential;

  private final ImmutableMap<String, String> blockListUrls;
  private final Retrier retrier;

  @Inject
  BlockListFetcher(
      UrlConnectionService urlConnectionService,
      BsaCredential credential,
      @Config("bsaDataUrls") ImmutableMap<String, String> blockListUrls,
      Retrier retrier) {
    this.urlConnectionService = urlConnectionService;
    this.credential = credential;
    this.blockListUrls = blockListUrls;
    this.retrier = retrier;
  }

  LazyBlockList fetch(BlockList blockList) {
    // TODO: use more informative exceptions to describe retriable errors
    return retrier.callWithRetry(
        () -> tryFetch(blockList),
        e -> e instanceof BsaException && ((BsaException) e).isRetriable());
  }

  LazyBlockList tryFetch(BlockList blockList) {
    try {
      HttpsURLConnection connection =
          (HttpsURLConnection)
              urlConnectionService.createConnection(
                  new java.net.URL(blockListUrls.get(blockList.name())));
      connection.setRequestMethod(HttpMethods.GET);
      connection.setRequestProperty("Authorization", "Bearer " + credential.getAuthToken());
      int code = connection.getResponseCode();
      if (code != SC_OK) {
        String errorDetails = "";
        try (InputStream errorStream = connection.getErrorStream()) {
          errorDetails = new String(ByteStreams.toByteArray(errorStream), UTF_8);
        } catch (Exception e) {
          // ignore
        }
        throw new BsaException(
            String.format(
                "Status code: [%s], error: [%s], details: [%s]",
                code, connection.getResponseMessage(), errorDetails),
            /* retriable= */ true);
      }
      return new LazyBlockList(blockList, connection);
    } catch (IOException e) {
      throw new BsaException(e, /* retriable= */ true);
    } catch (GeneralSecurityException e) {
      throw new BsaException(e, /* retriable= */ false);
    }
  }

  static class LazyBlockList implements Closeable {

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

    void consumeAll(BiConsumer<byte[], Integer> consumer) throws IOException {
      try (InputStream inputStream = connection.getInputStream()) {
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
          consumer.accept(buffer, bytesRead);
        }
      }
    }

    @Override
    public void close() {
      connection.disconnect();
    }
  }
}
