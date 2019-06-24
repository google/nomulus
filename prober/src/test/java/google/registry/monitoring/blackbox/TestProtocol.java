package google.registry.monitoring.blackbox;

import static com.google.common.truth.Truth.assertThat;

import google.registry.monitoring.blackbox.TestModule.TestProtocolModule;
import google.registry.monitoring.blackbox.TestModule.TestComponent;
import com.google.common.collect.ImmutableList;
import io.netty.channel.ChannelHandler;
import javax.inject.Inject;
import javax.inject.Provider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Basic unit test for Protocol Class
 */
@RunWith(JUnit4.class)
public class TestProtocol {

  /**
   * Stores default values that constitute a Protocol
   */
  private static final String HOST_NAME = "127.0.0.1";
  private static final int PORT_NUM = 0;
  private static final String NAME = "Test";
  private static final ImmutableList<Provider<? extends ChannelHandler>> HANDLERS = ImmutableList.of();

  /**
   * Test Protocol that we build
   */
  private Protocol protocol;

  /**
   *
   * @return Protocol implementation built using fields above
   */
  static Protocol defaultImplementation() {
    return Protocol.builder()
        .host(HOST_NAME)
        .port(PORT_NUM)
        .name(NAME)
        .handlerProviders(HANDLERS)
        .build();
  }

  /**
   * Stores default implementation into private protocol field
   */
  private void basicProtocol() {
    protocol = TestProtocol.defaultImplementation();
  }


  /**
   * Basic unit test that insures stored values in protocol are accurate
   */
  @Test
  public void testProtocolAttributes() {
    basicProtocol();
    assertThat(protocol.host()).isEqualTo(HOST_NAME);
    assertThat(protocol.port()).isEqualTo(PORT_NUM);
    assertThat(protocol.name()).isEqualTo(NAME);
    assertThat(protocol.handlerProviders()).isEqualTo(HANDLERS);
  }



}
