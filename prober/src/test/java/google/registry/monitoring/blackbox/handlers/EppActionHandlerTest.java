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

package google.registry.monitoring.blackbox.handlers;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;

import google.registry.monitoring.blackbox.exceptions.EppClientException;
import google.registry.monitoring.blackbox.exceptions.FailureException;
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.messages.EppRequestMessage;
import google.registry.monitoring.blackbox.messages.EppResponseMessage;
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
import org.xml.sax.SAXException;

/**
 * Unit tests for {@link EppActionHandler} and {@link EppMessageHandler} as well as integration
 * tests for both of them.
 *
 * <p>Attempts to test how well {@link EppActionHandler} works
 * when responding to all possible types of {@link EppResponseMessage}s with corresponding {@link
 * EppRequestMessage} sent down channel pipeline.</p>
 */
@RunWith(Parameterized.class)
public class EppActionHandlerTest {

  private static final String USER_ID = "TEST_ID";
  private static final String USER_PASSWORD = "TEST_PASSWORD";
  private static final String USER_CLIENT_TRID = "prober-localhost-1234567891011-0";
  private static final String FAILURE_TRID = "TEST_FAILURE_TRID";
  private static final String DOMAIN_NAME = "TEST_DOMAIN_NAME.test";
  private static final String SERVER_ID = "TEST_SERVER_ID";
  private static final String SUCCESS_MSG = "SUCCESSFUL ACTION PERFORMED";
  private static final String FAILURE_MSG = "FAILURE IN ACTION PERFORMED";
  private static final int SUCCESS_RESULT_CODE = 1000;
  private static final int FAILURE_RESULT_CODE = 2500;

  @Parameter(0)
  public EppRequestMessage messageType;
  private EmbeddedChannel channel;
  private EppActionHandler actionHandler;
  private EppMessageHandler messageHandler;

  // We test all relevant EPP actions
  @Parameters(name = "{0}")
  public static EppRequestMessage[] data() {
    return new EppRequestMessage[]{
        new EppRequestMessage.Hello(new EppResponseMessage.Greeting()),
        new EppRequestMessage.Login(new EppResponseMessage.SimpleSuccess(), USER_ID, USER_PASSWORD),
        new EppRequestMessage.Create(new EppResponseMessage.SimpleSuccess()),
        new EppRequestMessage.Create(new EppResponseMessage.Failure()),
        new EppRequestMessage.Delete(new EppResponseMessage.SimpleSuccess()),
        new EppRequestMessage.Delete(new EppResponseMessage.Failure()),
        new EppRequestMessage.Logout(new EppResponseMessage.SimpleSuccess()),
        new EppRequestMessage.Check(new EppResponseMessage.DomainExists()),
        new EppRequestMessage.Check(new EppResponseMessage.DomainNotExists())
    };
  }

  /**
   * Setup main three handlers to be used in pipeline.
   */
  @Before
  public void setup() throws EppClientException {
    actionHandler = new EppActionHandler();
    messageHandler = new EppMessageHandler();

    messageType.modifyMessage(USER_CLIENT_TRID, DOMAIN_NAME);
  }

  private void setupEmbeddedChannel(ChannelHandler... handlers) {
    channel = new EmbeddedChannel(handlers);
  }

  private String getResponseString(EppResponseMessage response, boolean fail, String clTRID) {
    if (response instanceof EppResponseMessage.Greeting) {
      if (fail) {
        return EppUtils.getBasicResponse(
            SUCCESS_RESULT_CODE,
            SUCCESS_MSG,
            clTRID,
            SERVER_ID);
      } else {
        return EppUtils.getDefaultGreeting();
      }
    } else if (response instanceof EppResponseMessage.DomainExists) {
      return EppUtils.getCheckDomainResponse(
          fail,
          DOMAIN_NAME,
          clTRID,
          SERVER_ID);
    } else if (response instanceof EppResponseMessage.DomainNotExists) {
      return EppUtils.getCheckDomainResponse(
          !fail,
          DOMAIN_NAME,
          clTRID,
          SERVER_ID);
    } else if (response instanceof EppResponseMessage.SimpleSuccess) {
      return EppUtils.getBasicResponse(
          fail ? FAILURE_RESULT_CODE : SUCCESS_RESULT_CODE,
          fail ? FAILURE_MSG : SUCCESS_MSG,
          clTRID,
          SERVER_ID);
    } else {
      return EppUtils.getBasicResponse(
          fail ? SUCCESS_RESULT_CODE : FAILURE_RESULT_CODE,
          fail ? SUCCESS_MSG : FAILURE_MSG,
          clTRID,
          SERVER_ID);
    }
  }

  @Test
  public void testBasicAction_Success_Embedded()
      throws SAXException, IOException, EppClientException, FailureException {
    //We simply use an embedded channel in this instance
    setupEmbeddedChannel(actionHandler);

    ChannelFuture future = actionHandler.getFinishedFuture();

    EppResponseMessage response = messageType.getExpectedResponse();

    response.getDocument(EppUtils.stringToByteBuf(
        getResponseString(messageType.getExpectedResponse(), false, USER_CLIENT_TRID)));

    channel.writeInbound(response);

    ChannelFuture unusedFuture = future.syncUninterruptibly();

    assertThat(future.isSuccess()).isTrue();
  }

  @Test
  public void testBasicAction_FailCode_Embedded()
      throws SAXException, IOException, EppClientException, FailureException {
    //We simply use an embedded channel in this instance
    setupEmbeddedChannel(actionHandler);

    ChannelFuture future = actionHandler.getFinishedFuture();

    EppResponseMessage response = messageType.getExpectedResponse();

    response.getDocument(EppUtils.stringToByteBuf(
        getResponseString(messageType.getExpectedResponse(), true, USER_CLIENT_TRID)));

    channel.writeInbound(response);

    assertThrows(FailureException.class, () -> {
      ChannelFuture unusedFuture = future.syncUninterruptibly();
    });
  }

  @Test
  public void testBasicAction_FailTRID_Embedded()
      throws SAXException, IOException, EppClientException, FailureException {
    //We simply use an embedded channel in this instance
    setupEmbeddedChannel(actionHandler);

    ChannelFuture future = actionHandler.getFinishedFuture();

    EppResponseMessage response = messageType.getExpectedResponse();

    response.getDocument(EppUtils.stringToByteBuf(
        getResponseString(messageType.getExpectedResponse(), false, FAILURE_TRID)));

    channel.writeInbound(response);

    if (messageType instanceof EppRequestMessage.Hello) {
      ChannelFuture unusedFuture = future.syncUninterruptibly();
      assertThat(future.isSuccess()).isTrue();
    } else {
      assertThrows(FailureException.class, () -> {
        ChannelFuture unusedFuture = future.syncUninterruptibly();
      });
    }
  }

  @Test
  public void testIntegratedAction_Success_Embedded()
      throws IOException, SAXException, UndeterminedStateException {
    //We simply use an embedded channel in this instance
    setupEmbeddedChannel(messageHandler, actionHandler);

    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(messageType);

    channel.writeInbound(EppUtils.stringToByteBuf(
        getResponseString(messageType.getExpectedResponse(), false, USER_CLIENT_TRID)));

    ChannelFuture unusedFuture = future.syncUninterruptibly();

    assertThat(future.isSuccess()).isTrue();
  }

  @Test
  public void testIntegratedAction_FailCode_Embedded()
      throws IOException, SAXException, UndeterminedStateException {
    //We simply use an embedded channel in this instance
    setupEmbeddedChannel(messageHandler, actionHandler);

    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(messageType);

    channel.writeInbound(EppUtils.stringToByteBuf(
        getResponseString(messageType.getExpectedResponse(), true, USER_CLIENT_TRID)));

    assertThrows(FailureException.class, () -> {
      ChannelFuture unusedFuture = future.syncUninterruptibly();
    });
  }

  @Test
  public void testIntegratedAction_FailTRID_Embedded()
      throws IOException, SAXException, UndeterminedStateException {
    //We simply use an embedded channel in this instance
    setupEmbeddedChannel(messageHandler, actionHandler);

    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(messageType);

    channel.writeInbound(EppUtils.stringToByteBuf(
        getResponseString(messageType.getExpectedResponse(), false, FAILURE_TRID)));

    if (messageType instanceof EppRequestMessage.Hello) {
      ChannelFuture unusedFuture = future.syncUninterruptibly();
      assertThat(future.isSuccess()).isTrue();
    } else {
      assertThrows(FailureException.class, () -> {
        ChannelFuture unusedFuture = future.syncUninterruptibly();
      });
    }
  }
}
