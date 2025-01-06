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
package google.registry.client;

import static com.google.common.io.Resources.getResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.joda.time.DateTime;

/** A simple EPP client that can be used for load testing. */
@Parameters(separators = " =")
@SuppressWarnings("FutureReturnValueIgnored")
public class EppClient implements Runnable {

  // TODO(b/354014191): Add loadtest handling for other EPP flows

  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  private static final String LOGIN_FILE = "login.xml";
  private static final String LOGOUT_FILE = "logout.xml";

  private static final String CONTACT_CREATE_FILE = "contact_create.xml";

  private static final String DOMAIN_CREATE_FILE = "domain_create.xml";

  static final AttributeKey<ArrayList<ZonedDateTime>> REQUEST_SENT =
      AttributeKey.valueOf("REQUEST_SENT");
  static final AttributeKey<ArrayList<ZonedDateTime>> RESPONSE_RECEIVED =
      AttributeKey.valueOf("RESPONSE_RECEIVED");
  static final AttributeKey<Integer> CHANNEL_NUMBER = AttributeKey.valueOf("CHANNEL_NUMBER");
  static final AttributeKey<Path> LOGGING_LOCATION = AttributeKey.valueOf("LOGGING_LOCATION");
  static final AttributeKey<Boolean> FORCE_TERMINATE = AttributeKey.valueOf("FORCE_TERMINATE");
  static final AttributeKey<ExecutorService> LOGGING_EXECUTOR =
      AttributeKey.valueOf("LOGGING_EXECUTOR");
  static final AttributeKey<Iterator<byte[]>> INPUT_ITERATOR =
      AttributeKey.valueOf("INPUT_ITERATOR");
  static final AttributeKey<Promise<Void>> LOGGING_REQUEST_COMPLETE =
      AttributeKey.valueOf("LOGGING_REQUEST_COMPLETE");
  private static final int PORT = 700;
  private static final int TIMEOUT_SECONDS = 600;
  private static final Random random = new Random();

  public static class InetAddressConverter implements IStringConverter<InetAddress> {

    @Override
    public InetAddress convert(String host) {
      try {
        return InetAddress.getByName(host);
      } catch (UnknownHostException e) {
        throw new IllegalArgumentException(host, e);
      }
    }
  }

  @Parameter(
      names = {"--help"},
      description = "Print this help message.",
      help = true)
  private boolean help = false;

  @Parameter(
      names = {"--host", "-h"},
      description = "Epp server hostname/IP to connect to.",
      converter = InetAddressConverter.class,
      required = true)
  private InetAddress host = null;

  @Parameter(
      names = {"--certificate"},
      description = "Certificate pem file.",
      required = true)
  private String certFileName = null;

  @Parameter(
      names = {"--key", "-k"},
      description = "Private key pem file.",
      required = true)
  private String keyFileName = null;

  @Parameter(
      names = {"--connections", "-cn"},
      description = "Number of connections that are made to the EPP server.")
  private int connections = 1;

  @Parameter(
      names = {"--client", "-c"},
      description = "Registrar client id.")
  private String client = "proxy";

  @Parameter(
      names = {"--password", "-pw"},
      description = "Registrar password.")
  private String password = "abcde12345";

  @Parameter(
      names = {"--domain_creates"},
      description = "The number of domains to create.")
  private int domainCreates = 0;

  @Parameter(
      names = {"--tld"},
      description = "TLD to create domains on.",
      required = true)
  private String tld = "loadtest";

  @Parameter(
      names = {"--force_terminate", "-ft"},
      description = "Whether to explicitly close the connection after receiving a logout response.")
  private boolean forceTerminate = false;

  public static void main(String[] args) {
    EppClient eppClient = new EppClient();
    JCommander jCommander = new JCommander(eppClient);
    jCommander.parse(args);
    if (eppClient.help) {
      jCommander.usage();
      return;
    }
    eppClient.run();
  }

  private static String generateRandomString(int length) {
    byte[] buffer = new byte[length];
    random.nextBytes(buffer);
    return BaseEncoding.base32().encode(buffer).toLowerCase(Locale.US);
  }

  private ImmutableList<String> makeInputList(ZonedDateTime now) {
    ImmutableList.Builder<String> templatesList = ImmutableList.builder();
    ImmutableList.Builder<String> inputList = ImmutableList.builder();
    templatesList.add(readStringFromFile(LOGIN_FILE));
    String randomContactString = generateRandomString(5);
    if (domainCreates > 0) {
      templatesList.add(
          readStringFromFile(CONTACT_CREATE_FILE)
              .replace("@@RANDOM_CONTACT@@", randomContactString));
    }
    for (int i = 0; i < domainCreates; i++) {
      String randomString = generateRandomString(5);
      templatesList.add(
          readStringFromFile(DOMAIN_CREATE_FILE)
              .replace("@@RANDOM_CONTACT@@", randomContactString)
              .replace("@@REPEAT_NUMBER@@", String.valueOf(i))
              .replace("@@RANDOM@@", randomString));
    }
    templatesList.add(readStringFromFile(LOGOUT_FILE));
    for (String template : templatesList.build()) {
      inputList.add(
          template
              .replace("@@CLIENT@@", client)
              .replace("@@PASSWORD@@", password)
              .replace("@@NOW@@", now.toString())
              .replace("@@TLD@@", tld));
    }
    return inputList.build();
  }

  private static String readStringFromFile(String filename) {
    try {
      return Resources.toString(getResource(EppClient.class, "resources/" + filename), UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read from file: resources/" + filename);
    }
  }

  private static KeyPair getKeyPair(String filename) throws IOException {
    byte[] keyBytes = Files.asCharSource(new File(filename), UTF_8).read().getBytes(UTF_8);
    try {
      PEMKeyPair pemPair =
          (PEMKeyPair)
              new PEMParser(new InputStreamReader(new ByteArrayInputStream(keyBytes), UTF_8))
                  .readObject();
      return new JcaPEMKeyConverter().setProvider("BC").getKeyPair(pemPair);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static X509Certificate getCertificate(String filename) throws IOException {
    byte[] certificateBytes = Files.asCharSource(new File(filename), UTF_8).read().getBytes(UTF_8);
    try {
      X509CertificateHolder certificateHolder =
          (X509CertificateHolder)
              new PEMParser(
                      new InputStreamReader(new ByteArrayInputStream(certificateBytes), UTF_8))
                  .readObject();
      return new JcaX509CertificateConverter().setProvider("BC").getCertificate(certificateHolder);
    } catch (IOException | CertificateException e) {
      throw new RuntimeException(e);
    }
  }

  private ChannelInitializer<SocketChannel> makeChannelInitializer(
      String outputFolder, ImmutableList<ExecutorService> loggingExecutors) throws IOException {
    return new ChannelInitializer<>() {

      private final ImmutableList<String> inputList =
          makeInputList(ZonedDateTime.now(ZoneOffset.UTC));
      private final KeyPair key = getKeyPair(keyFileName);
      private final X509Certificate cert = getCertificate(certFileName);
      private final LoggingHandler loggingHandler = new LoggingHandler(LogLevel.INFO);
      private final EppClientHandler eppClientHandler = new EppClientHandler();

      @Override
      protected void initChannel(SocketChannel ch) throws Exception {
        ch.attr(REQUEST_SENT).set(new ArrayList<>());
        ch.attr(RESPONSE_RECEIVED).set(new ArrayList<>());
        Path loggingLocation =
            Paths.get(String.format("%s/%d.log", outputFolder, ch.attr(CHANNEL_NUMBER).get()));
        ch.attr(LOGGING_LOCATION).set(loggingLocation);
        ch.attr(FORCE_TERMINATE).set(forceTerminate);
        ch.attr(LOGGING_EXECUTOR)
            .set(loggingExecutors.get(ch.attr(CHANNEL_NUMBER).get() % loggingExecutors.size()));

        ch.attr(INPUT_ITERATOR)
            .set(
                inputList.stream()
                    .map(
                        (String str) ->
                            str.replace(
                                    "@@CHANNEL_NUMBER@@",
                                    String.valueOf(ch.attr(CHANNEL_NUMBER).get()))
                                .replace("@@CHANNEL_NUMBER_MODULO@@", String.valueOf(0))
                                .getBytes(UTF_8))
                    .iterator());

        ch.pipeline()
            .addLast(
                SslContextBuilder.forClient()
                    .keyManager(key.getPrivate(), cert)
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build()
                    .newHandler(ch.alloc(), host.getHostName(), PORT));
        ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(512 * 1024, 0, 4, -4, 4));
        ch.pipeline().addLast(new LengthFieldPrepender(4, true));
        ch.pipeline().addLast(loggingHandler);
        ch.pipeline().addLast(eppClientHandler);
      }
    };
  }

  private static String createOutputFolder(String folderName) {
    Path folder = Paths.get(folderName);
    if (!folder.toFile().exists()) {
      folder.toFile().mkdirs();
    }
    System.out.printf("\nOutputs saved at %s\n", folder);
    return folderName;
  }

  @Override
  public void run() {
    String outputFolder = createOutputFolder(String.format("load-tests/%s", DateTime.now(UTC)));
    ImmutableList.Builder<ExecutorService> builder = ImmutableList.builderWithExpectedSize(5);
    for (int i = 0; i < 5; ++i) {
      builder.add(Executors.newSingleThreadExecutor());
    }
    final ImmutableList<ExecutorService> loggingExecutors = builder.build();

    EventLoopGroup eventLoopGroup = new NioEventLoopGroup();

    try {
      Bootstrap bootstrap =
          new Bootstrap()
              .group(eventLoopGroup)
              .channel(NioSocketChannel.class)
              .handler(makeChannelInitializer(outputFolder, loggingExecutors));

      List<ChannelFuture> channelFutures = new ArrayList<>();

      // Three requests: hello (from the proxy), login and logout
      int requestsPerConnection = 3;

      // If testing domain creates, add the number of domain creates per connection plus the one
      // contact created for each connection
      if (domainCreates > 0) {
        requestsPerConnection += domainCreates + 1;
      }

      for (int i = 0; i < connections; i++) {
        bootstrap.attr(CHANNEL_NUMBER, i);
        channelFutures.add(
            bootstrap
                .connect(host, PORT)
                .addListener(
                    (ChannelFuture cf) -> {
                      if (!cf.isSuccess()) {
                        System.out.printf("Cannot connect to %s:%s\n", host, PORT);
                      }
                    }));
      }

      LinkedHashSet<Integer> killedConnections = new LinkedHashSet<>();
      LinkedHashSet<Integer> incompleteConnections = new LinkedHashSet<>();
      List<Long> requestDurations = new ArrayList<>();
      ZonedDateTime startTime = null;
      ZonedDateTime endTime = null;
      int failedRequests = 0;

      // Wait for all channels to close.
      for (ChannelFuture channelFuture : channelFutures) {
        Channel channel = channelFuture.syncUninterruptibly().channel();
        int channelNumber = channel.attr(CHANNEL_NUMBER).get();
        if (!channel
            .closeFuture()
            .awaitUninterruptibly(
                TIMEOUT_SECONDS * 1000
                    - Duration.between(
                            channel.attr(REQUEST_SENT).get().getFirst(),
                            ZonedDateTime.now(ZoneOffset.UTC))
                        .toMillis())) {
          channel.close().syncUninterruptibly();
          killedConnections.add(channelNumber);
        }
      }

      for (ChannelFuture channelFuture : channelFutures) {
        Channel channel = channelFuture.channel();
        int channelNumber = channel.attr(CHANNEL_NUMBER).get();
        int responsesReceived = channel.attr(RESPONSE_RECEIVED).get().size();
        for (int i = 0; i < responsesReceived; i++) {
          requestDurations.add(
              Duration.between(
                      channel.attr(REQUEST_SENT).get().get(i),
                      channel.attr(RESPONSE_RECEIVED).get().get(i))
                  .toMillis());
        }
        if (responsesReceived != requestsPerConnection) {
          incompleteConnections.add(channelNumber);
          failedRequests += (requestsPerConnection - channel.attr(REQUEST_SENT).get().size());
        }
        if (responsesReceived > 0) {
          startTime = updateStartTime(channelFutures, channelNumber, startTime);
          endTime = updateEndTime(channelFutures, channelNumber, endTime);
        }
      }

      if (!incompleteConnections.isEmpty()) {
        System.out.printf("%d incomplete connections: ", incompleteConnections.size());
        for (int channelNumber : incompleteConnections) {
          System.out.printf("Channel %d not finished\n", channelNumber);
        }
        System.out.print("\n");
      }

      System.out.println();
      System.out.println("====== SUMMARY ======");
      System.out.printf("Number of connections: %d\n", connections);
      System.out.printf("Number of requests per connection: %d\n", requestsPerConnection);
      if (startTime != null && endTime != null) {
        System.out.printf(
            "Average Response QPS: %.2f\n",
            (double) requestDurations.size()
                * 1000.0
                / Duration.between(startTime, endTime).toMillis());
      }
      System.out.printf("Number of incomplete connections: %d\n", incompleteConnections.size());
      if (!killedConnections.isEmpty()) {
        System.out.printf("Force killed connections (%d): ", killedConnections.size());
        for (int channelNumber : killedConnections) {
          System.out.printf("%d ", channelNumber);
        }
        System.out.print("\n");
      }
      System.out.printf("Number of Failed Requests: %d\n", failedRequests);

      eventLoopGroup.shutdownGracefully();

      channelFutures.forEach(
          channelFuture -> {
            channelFuture.channel().attr(LOGGING_REQUEST_COMPLETE).get().syncUninterruptibly();
          });
      loggingExecutors.forEach(ExecutorService::shutdown);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private ZonedDateTime updateStartTime(
      List<ChannelFuture> channelFutures, int channelNumber, ZonedDateTime startTime) {
    ZonedDateTime channelStartTime =
        channelFutures.get(channelNumber).channel().attr(REQUEST_SENT).get().getFirst();
    if (startTime == null || startTime.isAfter(channelStartTime)) {
      return channelStartTime;
    }
    return startTime;
  }

  private ZonedDateTime updateEndTime(
      List<ChannelFuture> channelFutures, int channelNumber, ZonedDateTime endTime) {
    ZonedDateTime channelEndTime =
        channelFutures.get(channelNumber).channel().attr(RESPONSE_RECEIVED).get().getLast();

    if (endTime == null || endTime.isBefore(channelEndTime)) {
      return channelEndTime;
    }
    return endTime;
  }
}
