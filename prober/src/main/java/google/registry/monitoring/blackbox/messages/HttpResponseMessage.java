package google.registry.monitoring.blackbox.messages;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public class HttpResponseMessage extends DefaultFullHttpResponse implements InboundMessageType {

  public HttpResponseMessage(HttpVersion version, HttpResponseStatus status) {
    super(version, status);
  }

  public HttpResponseMessage(HttpVersion version, HttpResponseStatus status, ByteBuf content) {
    super(version, status, content);
  }


  public static HttpResponseMessage fromResponse(FullHttpResponse response) {
    HttpResponseMessage finalResponse;
    ByteBuf buf = response.content();

    if (buf == null)
      finalResponse = new HttpResponseMessage(HttpVersion.HTTP_1_1, response.status());
    else
      finalResponse = new HttpResponseMessage(HttpVersion.HTTP_1_1, response.status(), buf);


    if (response.headers().get("location") != null)
      finalResponse.headers().set("location", response.headers().get("location"));

    return finalResponse;
  }



}
