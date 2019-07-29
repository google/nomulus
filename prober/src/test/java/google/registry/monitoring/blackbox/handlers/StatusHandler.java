package google.registry.monitoring.blackbox.handlers;

import google.registry.monitoring.blackbox.handlers.ActionHandler.ResponseType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public class StatusHandler extends SimpleChannelInboundHandler<ResponseType> {

  private ResponseType response;

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, ResponseType msg) {
    response = msg;
  }

  public ResponseType getResponse() {
    return response;
  }
}
