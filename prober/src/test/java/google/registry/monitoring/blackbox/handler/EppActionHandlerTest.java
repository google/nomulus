// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
// limitations under the License

package google.registry.monitoring.blackbox.handler;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.monitoring.blackbox.exception.FailureException;
import google.registry.monitoring.blackbox.message.EppRequestMessage;
import google.registry.monitoring.blackbox.message.EppResponseMessage;
import google.registry.monitoring.blackbox.util.EppUtils;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;

/**
 * Unit tests for {@link EppActionHandler} and {@link EppMessageHandler} as well as integration
 * tests for both of them.
 *
 * <p>Attempts to test how well {@link EppActionHandler} works when responding to all possible types
 * of {@link EppResponseMessage}s with corresponding {@link EppRequestMessage} sent down channel
 * pipeline.
 */
class EppActionHandlerTest {

  private static final String USER_ID = "TEST_ID";
  private static final String USER_PASSWORD = "TEST_PASSWORD";
  private static final String USER_CLIENT_TRID = "prober-localhost-1234567891011-0";
  private static final String FAILURE_TRID = "TEST_FAILURE_TRID";
  private static final String DOMAIN_NAME = "TEST_DOMAIN_NAME.test";
  private static final String SERVER_ID = "TEST_SERVER_ID";

  private EmbeddedChannel channel;
  private EppActionHandler actionHandler = new EppActionHandler();
  private EppMessageHandler messageHandler = new EppMessageHandler(EppUtils.getGreetingResponse());

  @SuppressWarnings("unused")
  static Stream<Arguments> provideTestCombinations() {
    return Stream.of(
            EppUtils.getHelloMessage(EppUtils.getGreetingResponse()),
            EppUtils.getLoginMessage(EppUtils.getSuccessResponse(), USER_ID, USER_PASSWORD),
            EppUtils.getCreateMessage(EppUtils.getSuccessResponse()),
            EppUtils.getCreateMessage(EppUtils.getFailureResponse()),
            EppUtils.getDeleteMessage(EppUtils.getSuccessResponse()),
            EppUtils.getDeleteMessage(EppUtils.getFailureResponse()),
            EppUtils.getLogoutMessage(EppUtils.getSuccessResponse()),
            EppUtils.getCheckMessage(EppUtils.getDomainExistsResponse()),
            EppUtils.getCheckMessage(EppUtils.getDomainNotExistsResponse()))
        .map(Arguments::of);
  }

  private void setupRequestAndChannels(EppRequestMessage message, ChannelHandler... handlers)
      throws Exception {
    message.modifyMessage(USER_CLIENT_TRID, DOMAIN_NAME);
    channel = new EmbeddedChannel(handlers);
  }

  private Document getResponse(EppResponseMessage response, boolean fail, String clTrid)
      throws Exception {
    switch (response.name()) {
      case "greeting":
        if (fail) {
          return EppUtils.getBasicResponse(true, clTrid, SERVER_ID);
        } else {
          return EppUtils.getGreeting();
        }
      case "domainExists":
        return EppUtils.getDomainCheck(!fail, clTrid, SERVER_ID, DOMAIN_NAME);
      case "domainNotExists":
        return EppUtils.getDomainCheck(fail, clTrid, SERVER_ID, DOMAIN_NAME);
      case "success":
        return EppUtils.getBasicResponse(!fail, clTrid, SERVER_ID);
      default:
        return EppUtils.getBasicResponse(fail, clTrid, SERVER_ID);
    }
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testBasicAction_Success_Embedded(EppRequestMessage message) throws Exception {
    // We simply use an embedded channel in this instance
    setupRequestAndChannels(message, actionHandler);
    ChannelFuture future = actionHandler.getFinishedFuture();
    EppResponseMessage response = message.getExpectedResponse();
    response.getDocument(
        EppUtils.docToByteBuf(getResponse(message.getExpectedResponse(), false, USER_CLIENT_TRID)));
    channel.writeInbound(response);
    ChannelFuture unusedFuture = future.syncUninterruptibly();
    assertThat(future.isSuccess()).isTrue();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testBasicAction_FailCode_Embedded(EppRequestMessage message) throws Exception {
    // We simply use an embedded channel in this instance
    setupRequestAndChannels(message, actionHandler);
    ChannelFuture future = actionHandler.getFinishedFuture();
    EppResponseMessage response = message.getExpectedResponse();
    response.getDocument(
        EppUtils.docToByteBuf(getResponse(message.getExpectedResponse(), true, USER_CLIENT_TRID)));
    channel.writeInbound(response);

    assertThrows(
        FailureException.class,
        () -> {
          ChannelFuture unusedFuture = future.syncUninterruptibly();
        });
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testBasicAction_FailTRID_Embedded(EppRequestMessage message) throws Exception {
    // We simply use an embedded channel in this instance
    setupRequestAndChannels(message, actionHandler);
    ChannelFuture future = actionHandler.getFinishedFuture();
    EppResponseMessage response = message.getExpectedResponse();
    response.getDocument(
        EppUtils.docToByteBuf(getResponse(message.getExpectedResponse(), false, FAILURE_TRID)));
    channel.writeInbound(response);

    if (message.getExpectedResponse().name().equals("greeting")) {
      ChannelFuture unusedFuture = future.syncUninterruptibly();
      assertThat(future.isSuccess()).isTrue();
    } else {
      assertThrows(
          FailureException.class,
          () -> {
            ChannelFuture unusedFuture = future.syncUninterruptibly();
          });
    }
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testIntegratedAction_Success_Embedded(EppRequestMessage message) throws Exception {
    // We simply use an embedded channel in this instance
    setupRequestAndChannels(message, messageHandler, actionHandler);
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(message);
    channel.writeInbound(
        EppUtils.docToByteBuf(getResponse(message.getExpectedResponse(), false, USER_CLIENT_TRID)));
    ChannelFuture unusedFuture = future.syncUninterruptibly();
    assertThat(future.isSuccess()).isTrue();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testIntegratedAction_FailCode_Embedded(EppRequestMessage message) throws Exception {
    // We simply use an embedded channel in this instance
    setupRequestAndChannels(message, messageHandler, actionHandler);
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(message);
    channel.writeInbound(
        EppUtils.docToByteBuf(getResponse(message.getExpectedResponse(), true, USER_CLIENT_TRID)));

    assertThrows(
        FailureException.class,
        () -> {
          ChannelFuture unusedFuture = future.syncUninterruptibly();
        });
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testIntegratedAction_FailTRID_Embedded(EppRequestMessage message) throws Exception {
    // We simply use an embedded channel in this instance
    setupRequestAndChannels(message, messageHandler, actionHandler);
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(message);
    channel.writeInbound(
        EppUtils.docToByteBuf(getResponse(message.getExpectedResponse(), false, FAILURE_TRID)));

    if (message.getExpectedResponse().name().equals("greeting")) {
      ChannelFuture unusedFuture = future.syncUninterruptibly();
      assertThat(future.isSuccess()).isTrue();
    } else {
      assertThrows(
          FailureException.class,
          () -> {
            ChannelFuture unusedFuture = future.syncUninterruptibly();
          });
    }
  }
}
