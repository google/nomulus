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
import static org.junit.Assert.assertThrows;

import google.registry.monitoring.blackbox.exception.EppClientException;
import google.registry.monitoring.blackbox.exception.FailureException;
import google.registry.monitoring.blackbox.exception.UndeterminedStateException;
import google.registry.monitoring.blackbox.message.EppRequestMessage;
import google.registry.monitoring.blackbox.message.EppResponseMessage;
import google.registry.monitoring.blackbox.util.EppUtils;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Unit tests for {@link EppActionHandler} and {@link EppMessageHandler} as well as integration
 * tests for both of them.
 *
 * <p>Attempts to test how well {@link EppActionHandler} works when responding to all possible types
 * of {@link EppResponseMessage}s with corresponding {@link EppRequestMessage} sent down channel
 * pipeline.
 */
@RunWith(Parameterized.class)
public class EppActionHandlerTest {

  private static final String USER_ID = "TEST_ID";
  private static final String USER_PASSWORD = "TEST_PASSWORD";
  private static final String USER_CLIENT_TRID = "prober-localhost-1234567891011-0";
  private static final String FAILURE_TRID = "TEST_FAILURE_TRID";
  private static final String DOMAIN_NAME = "TEST_DOMAIN_NAME.test";
  private static final String SERVER_ID = "TEST_SERVER_ID";

  @Parameter(0)
  public EppRequestMessage message;

  private EmbeddedChannel channel;
  private EppActionHandler actionHandler = new EppActionHandler();
  private EppMessageHandler messageHandler = new EppMessageHandler(EppUtils.getGreetingResponse());

  // We test all relevant EPP actions
  @Parameters(name = "{0}")
  public static EppRequestMessage[] data() {
    return new EppRequestMessage[] {
      EppUtils.getHelloMessage(EppUtils.getGreetingResponse()),
      EppUtils.getLoginMessage(EppUtils.getSuccessResponse(), USER_ID, USER_PASSWORD),
      EppUtils.getCreateMessage(EppUtils.getSuccessResponse()),
      EppUtils.getCreateMessage(EppUtils.getFailureResponse()),
      EppUtils.getDeleteMessage(EppUtils.getSuccessResponse()),
      EppUtils.getDeleteMessage(EppUtils.getFailureResponse()),
      EppUtils.getLogoutMessage(EppUtils.getSuccessResponse()),
      EppUtils.getCheckMessage(EppUtils.getDomainExistsResponse()),
      EppUtils.getCheckMessage(EppUtils.getDomainNotExistsResponse())
    };
  }

  /** Setup main three handlers to be used in pipeline. */
  @Before
  public void setup() throws EppClientException {
    message.modifyMessage(USER_CLIENT_TRID, DOMAIN_NAME);
  }

  private void setupEmbeddedChannel(ChannelHandler... handlers) {
    channel = new EmbeddedChannel(handlers);
  }

  private Document getResponse(EppResponseMessage response, boolean fail, String clTrid)
      throws IOException, EppClientException {
    if (response.name().equals("greeting")) {
      if (fail) {
        return EppUtils.getBasicResponse(true, clTrid, SERVER_ID);
      } else {
        return EppUtils.getGreeting();
      }
    } else if (response.name().equals("domainExists")) {
      return EppUtils.getDomainCheck(!fail, clTrid, SERVER_ID, DOMAIN_NAME);

    } else if (response.name().equals("domainNotExists")) {
      return EppUtils.getDomainCheck(fail, clTrid, SERVER_ID, DOMAIN_NAME);

    } else if (response.name().equals("success")) {
      return EppUtils.getBasicResponse(!fail, clTrid, SERVER_ID);

    } else {
      return EppUtils.getBasicResponse(fail, clTrid, SERVER_ID);
    }
  }

  @Test
  public void testBasicAction_Success_Embedded()
      throws SAXException, IOException, EppClientException, FailureException {
    // We simply use an embedded channel in this instance
    setupEmbeddedChannel(actionHandler);

    ChannelFuture future = actionHandler.getFinishedFuture();

    EppResponseMessage response = message.getExpectedResponse();

    response.getDocument(
        EppUtils.docToByteBuf(getResponse(message.getExpectedResponse(), false, USER_CLIENT_TRID)));

    channel.writeInbound(response);

    ChannelFuture unusedFuture = future.syncUninterruptibly();

    assertThat(future.isSuccess()).isTrue();
  }

  @Test
  public void testBasicAction_FailCode_Embedded()
      throws SAXException, IOException, EppClientException, FailureException {
    // We simply use an embedded channel in this instance
    setupEmbeddedChannel(actionHandler);

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

  @Test
  public void testBasicAction_FailTRID_Embedded()
      throws SAXException, IOException, EppClientException, FailureException {
    // We simply use an embedded channel in this instance
    setupEmbeddedChannel(actionHandler);

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

  @Test
  public void testIntegratedAction_Success_Embedded()
      throws IOException, SAXException, UndeterminedStateException {
    // We simply use an embedded channel in this instance
    setupEmbeddedChannel(messageHandler, actionHandler);

    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(message);

    channel.writeInbound(
        EppUtils.docToByteBuf(getResponse(message.getExpectedResponse(), false, USER_CLIENT_TRID)));

    ChannelFuture unusedFuture = future.syncUninterruptibly();

    assertThat(future.isSuccess()).isTrue();
  }

  @Test
  public void testIntegratedAction_FailCode_Embedded()
      throws IOException, SAXException, UndeterminedStateException {
    // We simply use an embedded channel in this instance
    setupEmbeddedChannel(messageHandler, actionHandler);

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

  @Test
  public void testIntegratedAction_FailTRID_Embedded()
      throws IOException, SAXException, UndeterminedStateException {
    // We simply use an embedded channel in this instance
    setupEmbeddedChannel(messageHandler, actionHandler);

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
