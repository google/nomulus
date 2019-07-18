package google.registry.monitoring.blackbox.messages;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

public class HttpRequestMessage extends DefaultFullHttpRequest implements OutboundMessageType {

  public HttpRequestMessage(HttpVersion httpVersion, HttpMethod method, String uri) {
    super(httpVersion, method, uri);
  }
  public HttpRequestMessage(HttpVersion httpVersion, HttpMethod method, String uri, ByteBuf content) {
    super(httpVersion, method, uri, content);
  }
  public HttpRequestMessage(HttpVersion httpVersion, HttpMethod method, String uri, ByteBuf content, boolean validateHeaders) {
    super(httpVersion, method, uri, content, validateHeaders);
  }



  @Override
  public HttpRequestMessage setUri(String path) {
    super.setUri(path);
    return this;
  }

  public static HttpRequestMessage fromRequest(FullHttpRequest request) {
    ByteBuf buf = request.content();
    HttpRequestMessage output;
    if (buf == null) {
      output = new HttpRequestMessage(HttpVersion.HTTP_1_1, request.method(), request.uri());
    } else {
      output = new HttpRequestMessage(HttpVersion.HTTP_1_1, request.method(), request.uri(), buf);
    }
    request.headers().forEach((entry) -> output.headers().set(entry.getKey(), entry.getValue()));
    return output;
  }

}
