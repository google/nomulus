package google.registry.monitoring.blackbox.handlers;

import static com.google.common.truth.Truth.assertThat;

import google.registry.monitoring.blackbox.exceptions.FailureException;
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.testservers.EppServer;
import google.registry.monitoring.blackbox.messages.EppMessage;
import google.registry.monitoring.blackbox.messages.EppRequestMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.xml.sax.SAXException;


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

  private EmbeddedChannel channel;
  private StatusHandler statusHandler;
  private EppActionHandler actionHandler;
  private EppMessageHandler messageHandler;

  @Parameter(0)
  public EppRequestMessage messageType;

  // We do our best effort to test all available SSL providers.
  @Parameters(name = "{0}")
  public static EppRequestMessage[] data() {
    return new EppRequestMessage[] {
        new EppRequestMessage.CHECK(),
        new EppRequestMessage.CLAIMSCHECK(),
        new EppRequestMessage.CREATE(),
        new EppRequestMessage.DELETE(),
        new EppRequestMessage.LOGOUT()
    };
  }

  @Before
  public void setup() throws ParserConfigurationException {
    setupHandlers();
  }


  private void setupHandlers() {
    statusHandler = new StatusHandler();
    actionHandler = new EppActionHandler();
    messageHandler = new EppMessageHandler();
  }

  private void setupEmbeddedChannel(ChannelHandler... handlers) {
    channel = new EmbeddedChannel(handlers);
  }


  @Test
  public void testBasicLogin_Success()
      throws IOException, SAXException, UndeterminedStateException, FailureException {
    setupEmbeddedChannel(messageHandler, actionHandler, statusHandler);

    ChannelFuture future = actionHandler.getFuture();
    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getDefaultGreeting()));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.SUCCESS);

    EppRequestMessage login = new EppRequestMessage.LOGIN(USER_ID, USER_PASSWORD);
    login.modifyMessage(USER_CLIENT_TRID, DOMAIN_NAME);

    channel.writeOutbound(login);
    ByteBuf buf = channel.readOutbound();

    int capacity = buf.readInt();
    byte[] bytestream = new byte[capacity - 4];
    buf.readBytes(bytestream);

    assertThat(login.toString())
        .isEqualTo(EppMessage.xmlDocToString(EppMessage.byteArrayToXmlDoc(bytestream)));

    actionHandler.resetFuture();
    future = actionHandler.getFuture();

    System.out.println(EppServer.getBasicResponse(
        SUCCESS_RESULT_CODE,
        SUCCESS_MSG,
        USER_CLIENT_TRID,
        SERVER_ID));
    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getBasicResponse(
        SUCCESS_RESULT_CODE,
        SUCCESS_MSG,
        USER_CLIENT_TRID,
        SERVER_ID)));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.SUCCESS);
  }

  @Test
  public void testBasicGreeting_Failure()
      throws IOException, SAXException, UndeterminedStateException {
    setupEmbeddedChannel(messageHandler, actionHandler, statusHandler);

    ChannelFuture future = actionHandler.getFuture();
    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getBasicResponse(
        FAILURE_RESULT_CODE,
        FAILURE_MSG,
        USER_CLIENT_TRID,
        SERVER_ID)));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.FAILURE);
  }

  @Test
  public void testBasicLogin_Failure()
      throws IOException, SAXException, UndeterminedStateException, FailureException {
    setupEmbeddedChannel(messageHandler, actionHandler, statusHandler);

    ChannelFuture future = actionHandler.getFuture();
    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getDefaultGreeting()));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.SUCCESS);

    EppRequestMessage login = new EppRequestMessage.LOGIN(USER_ID, USER_PASSWORD);
    login.modifyMessage(USER_CLIENT_TRID, DOMAIN_NAME);

    channel.writeOutbound(login);
    ByteBuf buf = channel.readOutbound();

    int capacity = buf.readInt();
    byte[] bytestream = new byte[capacity - 4];
    buf.readBytes(bytestream);

    assertThat(login.toString())
        .isEqualTo(EppMessage.xmlDocToString(EppMessage.byteArrayToXmlDoc(bytestream)));

    actionHandler.resetFuture();
    future = actionHandler.getFuture();

    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getBasicResponse(
        FAILURE_RESULT_CODE,
        FAILURE_MSG,
        USER_CLIENT_TRID,
        SERVER_ID)));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.FAILURE);

  }

  @Test
  public void testGeneralActions_Success()
      throws IOException, SAXException, UndeterminedStateException, FailureException {
    setupEmbeddedChannel(messageHandler, actionHandler, statusHandler);

    ChannelFuture future = actionHandler.getFuture();
    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getDefaultGreeting()));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.SUCCESS);

    messageType.modifyMessage(USER_CLIENT_TRID, DOMAIN_NAME);

    channel.writeOutbound(messageType);
    ByteBuf buf = channel.readOutbound();

    int capacity = buf.readInt();
    byte[] bytestream = new byte[capacity - 4];
    buf.readBytes(bytestream);

    assertThat(messageType.toString())
        .isEqualTo(EppMessage.xmlDocToString(EppMessage.byteArrayToXmlDoc(bytestream)));

    actionHandler.resetFuture();
    future = actionHandler.getFuture();

    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getBasicResponse(
        SUCCESS_RESULT_CODE,
        SUCCESS_MSG,
        USER_CLIENT_TRID,
        SERVER_ID)));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.SUCCESS);

  }

  @Test
  public void testGeneralActions_Failure()
      throws IOException, SAXException, UndeterminedStateException, FailureException {
    setupEmbeddedChannel(messageHandler, actionHandler, statusHandler);

    ChannelFuture future = actionHandler.getFuture();
    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getDefaultGreeting()));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.SUCCESS);

    messageType.modifyMessage(USER_CLIENT_TRID, DOMAIN_NAME);

    channel.writeOutbound(messageType);
    ByteBuf buf = channel.readOutbound();

    int capacity = buf.readInt();
    byte[] bytestream = new byte[capacity - 4];
    buf.readBytes(bytestream);

    assertThat(messageType.toString())
        .isEqualTo(EppMessage.xmlDocToString(EppMessage.byteArrayToXmlDoc(bytestream)));

    actionHandler.resetFuture();
    future = actionHandler.getFuture();

    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getBasicResponse(
        FAILURE_RESULT_CODE,
        FAILURE_MSG,
        USER_CLIENT_TRID,
        SERVER_ID)));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.FAILURE);

  }

  @Test
  public void testGeneralActions_ClTRIDFailure()
      throws IOException, SAXException, UndeterminedStateException, FailureException {
    setupEmbeddedChannel(messageHandler, actionHandler, statusHandler);

    ChannelFuture future = actionHandler.getFuture();
    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getDefaultGreeting()));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.SUCCESS);

    EppRequestMessage login = new EppRequestMessage.LOGIN(USER_ID, USER_PASSWORD);
    login.modifyMessage(USER_CLIENT_TRID, DOMAIN_NAME);

    channel.writeOutbound(login);
    ByteBuf buf = channel.readOutbound();

    int capacity = buf.readInt();
    byte[] bytestream = new byte[capacity - 4];
    buf.readBytes(bytestream);

    assertThat(login.toString())
        .isEqualTo(EppMessage.xmlDocToString(EppMessage.byteArrayToXmlDoc(bytestream)));

    actionHandler.resetFuture();
    future = actionHandler.getFuture();

    channel.writeInbound(EppServer.stringToByteBuf(EppServer.getBasicResponse(
        SUCCESS_RESULT_CODE,
        SUCCESS_MSG,
        FAILURE_TRID,
        SERVER_ID)));

    future.syncUninterruptibly();
    assertThat(statusHandler.getResponse()).isEqualTo(ResponseType.FAILURE);

  }
}
