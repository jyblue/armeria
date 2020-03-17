/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http2ObjectEncoder;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Connection.Endpoint;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2LocalFlowController;

public final class ClientHttp2ObjectEncoder extends Http2ObjectEncoder {
    private final SessionProtocol protocol;

    public ClientHttp2ObjectEncoder(ChannelHandlerContext ctx, Http2ConnectionEncoder encoder,
                                    SessionProtocol protocol) {
        super(ctx, encoder);
        this.protocol = requireNonNull(protocol, "protocol");
    }

    @Override
    protected ChannelFuture doWriteHeaders(int id, int streamId, HttpHeaders headers, boolean endStream,
                                           HttpHeaders additionalHeaders, HttpHeaders additionalTrailers) {
        final Http2Connection conn = encoder().connection();
        final boolean isTrailer = !headers.contains(HttpHeaderNames.METHOD);
        final Http2Headers convertedHeaders;

        if (isStreamPresentAndWritable(streamId)) {
            if (!isTrailer) {
                convertedHeaders = convertHeaders(headers, additionalHeaders);
            } else {
                convertedHeaders = ArmeriaHttpUtil.toNettyHttp2ClientTrailer(headers);
            }
            // Writing to an existing stream.
            return encoder().writeHeaders(ctx(), streamId, convertedHeaders, 0, endStream,
                                          ctx().newPromise());
        }

        final Endpoint<Http2LocalFlowController> local = conn.local();
        if (local.mayHaveCreatedStream(streamId)) {
            final ClosedStreamException closedStreamException =
                    new ClosedStreamException("Cannot create a new stream. streamId: " + streamId +
                                              ", lastStreamCreated: " + local.lastStreamCreated());
            if (!isTrailer) {
                return newFailedFuture(new UnprocessedRequestException(closedStreamException));
            } else {
                return newFailedFuture(closedStreamException);
            }
        }

        if (!isTrailer) {
            convertedHeaders = convertHeaders(headers, additionalHeaders);
        } else {
            convertedHeaders = ArmeriaHttpUtil.toNettyHttp2ClientTrailer(headers);
        }

        // Client starts a new stream.
        return encoder().writeHeaders(ctx(), streamId, convertedHeaders, 0, endStream,
                                      ctx().newPromise());
    }

    private Http2Headers convertHeaders(HttpHeaders inputHeaders, HttpHeaders additionalHeaders) {
        final Http2Headers outputHeaders =
                ArmeriaHttpUtil.toNettyHttp2ClientHeader(inputHeaders, additionalHeaders);

        if (!outputHeaders.contains(HttpHeaderNames.USER_AGENT)) {
            outputHeaders.add(HttpHeaderNames.USER_AGENT, HttpHeaderUtil.USER_AGENT.toString());
        }

        if (!outputHeaders.contains(HttpHeaderNames.SCHEME)) {
            outputHeaders.add(HttpHeaderNames.SCHEME, protocol.isTls() ? SessionProtocol.HTTPS.uriText()
                                                                       : SessionProtocol.HTTP.uriText());
        }

        if (!outputHeaders.contains(HttpHeaderNames.AUTHORITY)) {
            final InetSocketAddress remoteAddress = (InetSocketAddress) channel().remoteAddress();
            outputHeaders.add(HttpHeaderNames.AUTHORITY,
                              ArmeriaHttpUtil.authorityHeader(remoteAddress.getHostName(),
                                                              remoteAddress.getPort(), protocol.defaultPort()));
        }
        return outputHeaders;
    }
}
