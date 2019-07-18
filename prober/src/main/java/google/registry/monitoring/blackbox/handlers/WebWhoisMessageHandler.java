package google.registry.monitoring.blackbox.handlers;

import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.messages.HttpResponseMessage;
import google.registry.monitoring.blackbox.messages.InboundMessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpResponse;
import javax.inject.Inject;

public class WebWhoisMessageHandler extends MessageHandler {

  private HttpRequestMessage request;

  @Inject
  public WebWhoisMessageHandler() {}

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    request = (HttpRequestMessage) msg;
    request.retain();
    super.write(ctx, request, promise);
  }


  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

    FullHttpResponse originalResponse = (FullHttpResponse) msg;
    InboundMessageType response = HttpResponseMessage.fromResponse(originalResponse);
    super.channelRead(ctx, response);
  }
}
