/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.server.internal;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ratpack.event.internal.DefaultEventController;
import ratpack.exec.ExecController;
import ratpack.func.Action;
import ratpack.handling.Handler;
import ratpack.handling.Handlers;
import ratpack.handling.RequestOutcome;
import ratpack.handling.direct.DirectChannelAccess;
import ratpack.handling.direct.internal.DefaultDirectChannelAccess;
import ratpack.handling.internal.ChainHandler;
import ratpack.handling.internal.DefaultContext;
import ratpack.handling.internal.DescribingHandler;
import ratpack.handling.internal.DescribingHandlers;
import ratpack.http.MutableHeaders;
import ratpack.http.Response;
import ratpack.http.internal.*;
import ratpack.registry.Registry;
import ratpack.render.internal.DefaultRenderController;
import ratpack.server.ServerConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.CharBuffer;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@ChannelHandler.Sharable
public class NettyHandlerAdapter extends RatpackSimpleChannelInboundHandler {

  private static final AttributeKey<DefaultResponseTransmitter> RESPONSE_TRANSMITTER_ATTRIBUTE_KEY = AttributeKey.valueOf(DefaultResponseTransmitter.class.getName());
  private static final AttributeKey<Action<Object>> CHANNEL_SUBSCRIBER_ATTRIBUTE_KEY = AttributeKey.valueOf("ratpack.subscriber");

  private final static Logger LOGGER = LoggerFactory.getLogger(NettyHandlerAdapter.class);

  private final Handler[] handlers;

  private final DefaultContext.ApplicationConstants applicationConstants;

  private final Registry serverRegistry;
  private final boolean development;

  public NettyHandlerAdapter(Registry serverRegistry, Handler handler) throws Exception {
    super(false);
    this.handlers = ChainHandler.unpack(handler);
    this.serverRegistry = serverRegistry;
    this.applicationConstants = new DefaultContext.ApplicationConstants(this.serverRegistry, new DefaultRenderController(), serverRegistry.get(ExecController.class), Handlers.notFound());
    this.development = serverRegistry.get(ServerConfig.class).isDevelopment();
  }

  @Override
  public void channelRead(ChannelHandlerContext channelHandlerContext, Object msg) throws Exception {
    if (!(msg instanceof HttpRequest)) {
      Action<Object> subscriber = channelHandlerContext.attr(CHANNEL_SUBSCRIBER_ATTRIBUTE_KEY).get();
      if (subscriber != null) {
        subscriber.execute(msg);
        return;
      }
    }
    super.channelRead(channelHandlerContext, msg);
  }

  public void channelRead0(final ChannelHandlerContext ctx, final HttpRequest nettyRequest) throws Exception {
    ctx.channel().config().setAutoRead(false);

    if (!nettyRequest.decoderResult().isSuccess()) {
      sendError(ctx, HttpResponseStatus.BAD_REQUEST);
      //TODO?
//      nettyRequest.release();
      return;
    }
    if (HttpHeaderUtil.is100ContinueExpected(nettyRequest)) {
      FullHttpResponse continueResponse = new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER);
      ChannelFutureListener listener = future -> {
          if (!future.isSuccess()) {
            ctx.fireExceptionCaught(future.cause());
          }
      };
      ctx.writeAndFlush(continueResponse).addListener(listener);
      return;
    }

    final Channel channel = ctx.channel();
    InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
    InetSocketAddress socketAddress = (InetSocketAddress) channel.localAddress();

    final DefaultRequest request = new DefaultRequest(
      Instant.now(),
      new NettyHeadersBackedHeaders(nettyRequest.headers()),
      nettyRequest.method(),
      nettyRequest.protocolVersion(),
      nettyRequest.uri(),
      remoteAddress,
      socketAddress,
      downstream -> {
        ChannelPipeline pipeline = ctx.pipeline();
        ChannelHandler adapter = pipeline.remove("adapter");
        ChannelHandler innerHandler = null;
        if (pipeline.names().contains("inner")) {
          innerHandler = pipeline.remove("inner");
        }
        ChannelHandler inner = innerHandler;
        ResumableHttpRequestDecoder decoder = (ResumableHttpRequestDecoder) pipeline.get("decoder");
        decoder.setSingleDecode(false);
        pipeline.addLast("aggregator", new HttpObjectAggregator(serverRegistry.get(ServerConfig.class).getMaxContentLength()));
        pipeline.addLast("bodyHandler", new SimpleChannelInboundHandler<FullHttpMessage>() {

          @Override
          protected void channelRead0(ChannelHandlerContext ctx, FullHttpMessage msg) throws Exception {
            msg.retain();
            downstream.success(msg.content());
          }
        });

        downstream.onComplete(() -> {
          pipeline.remove("aggregator");
          pipeline.remove("bodyHandler");
          decoder.setSingleDecode(true);
          pipeline.addLast("adapter", adapter);
          if (inner != null) {
            pipeline.addLast("inner", inner);
          }
        });
        pipeline.fireChannelRead(nettyRequest);
        channel.config().setAutoRead(true);
      });
    final HttpHeaders nettyHeaders = new DefaultHttpHeaders(false);
    final MutableHeaders responseHeaders = new NettyHeadersBackedMutableHeaders(nettyHeaders);
    final DefaultEventController<RequestOutcome> requestOutcomeEventController = new DefaultEventController<>();
    final AtomicBoolean transmitted = new AtomicBoolean(false);

    final DefaultResponseTransmitter responseTransmitter = new DefaultResponseTransmitter(transmitted, channel, nettyRequest, request, nettyHeaders, requestOutcomeEventController);

    ctx.attr(RESPONSE_TRANSMITTER_ATTRIBUTE_KEY).set(responseTransmitter);

    Action<Action<Object>> subscribeHandler = thing -> {
      transmitted.set(true);
      ctx.attr(CHANNEL_SUBSCRIBER_ATTRIBUTE_KEY).set(thing);
    };

    final DirectChannelAccess directChannelAccess = new DefaultDirectChannelAccess(channel, subscribeHandler);

    final DefaultContext.RequestConstants requestConstants = new DefaultContext.RequestConstants(
      applicationConstants, request, directChannelAccess, requestOutcomeEventController.getRegistry()
    );

    final Response response = new DefaultResponse(responseHeaders, ctx.alloc(), responseTransmitter);
    requestConstants.response = response;

    DefaultContext.start(channel.eventLoop(), requestConstants, serverRegistry, handlers, execution -> {
      if (!transmitted.get()) {
        Handler lastHandler = requestConstants.handler;
        StringBuilder description = new StringBuilder();
        description
          .append("No response sent for ")
          .append(request.getMethod().getName())
          .append(" request to ")
          .append(request.getUri())
          .append(" (last handler: ");

        if (lastHandler instanceof DescribingHandler) {
          ((DescribingHandler) lastHandler).describeTo(description);
        } else {
          DescribingHandlers.describeTo(lastHandler, description);
        }

        description.append(")");
        String message = description.toString();
        LOGGER.warn(message);

        response.getHeaders().clear();

        ByteBuf body;
        if (development) {
          CharBuffer charBuffer = CharBuffer.wrap(message);
          body = ByteBufUtil.encodeString(ctx.alloc(), charBuffer, CharsetUtil.UTF_8);
          response.contentType(HttpHeaderConstants.PLAIN_TEXT_UTF8);
        } else {
          body = ctx.alloc().buffer(0, 0);
        }

        response.getHeaders().set(HttpHeaderConstants.CONTENT_LENGTH, body.readableBytes());
        responseTransmitter.transmit(HttpResponseStatus.INTERNAL_SERVER_ERROR, body);
      }
      if (!channel.config().isAutoRead()) {
        channel.config().setAutoRead(true);
      }
    });
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (!isIgnorableException(cause)) {
      LOGGER.error("", cause);
      if (ctx.channel().isActive()) {
        sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
      }
    }
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    ctx.attr(RESPONSE_TRANSMITTER_ATTRIBUTE_KEY).get().writabilityChanged();
  }

  private boolean isIgnorableException(Throwable throwable) {
    // There really does not seem to be a better way of detecting this kind of exception
    return throwable instanceof IOException && throwable.getMessage().endsWith("Connection reset by peer");
  }

  private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
    FullHttpResponse response = new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer("Failure: " + status.toString() + "\r\n", CharsetUtil.UTF_8));
    response.headers().set(HttpHeaderConstants.CONTENT_TYPE, HttpHeaderConstants.PLAIN_TEXT_UTF8);

    // Close the connection as soon as the error message is sent.
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }
}
