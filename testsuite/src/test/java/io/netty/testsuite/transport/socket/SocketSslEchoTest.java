/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.testsuite.transport.socket;

import static org.junit.Assert.*;
import io.netty.bootstrap.ClientBootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundByteHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactorySpi;
import javax.net.ssl.X509TrustManager;

import org.junit.Test;

public class SocketSslEchoTest extends AbstractSocketTest {

    private static final int FIRST_MESSAGE_SIZE = 16384;
    private static final Random random = new Random();
    static final byte[] data = new byte[1048576];

    static {
        random.nextBytes(data);
    }

    @Test
    public void testSslEcho() throws Throwable {
        run();
    }

    public void testSslEcho(ServerBootstrap sb, ClientBootstrap cb) throws Throwable {
        final EchoHandler sh = new EchoHandler(true);
        final EchoHandler ch = new EchoHandler(false);

        final SSLEngine sse = BogusSslContextFactory.getServerContext().createSSLEngine();
        final SSLEngine cse = BogusSslContextFactory.getClientContext().createSSLEngine();
        sse.setUseClientMode(false);
        cse.setUseClientMode(true);

        sb.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel sch) throws Exception {
                sch.pipeline().addFirst("ssl", new SslHandler(sse));
                sch.pipeline().addLast("handler", sh);
            }
        });

        cb.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel sch) throws Exception {
                sch.pipeline().addFirst("ssl", new SslHandler(cse));
                sch.pipeline().addLast("handler", ch);
            }
        });

        Channel sc = sb.bind().sync().channel();
        Channel cc = cb.connect().sync().channel();
        ChannelFuture hf = cc.pipeline().get(SslHandler.class).handshake();
        final ChannelFuture firstByteWriteFuture =
                cc.write(Unpooled.wrappedBuffer(data, 0, FIRST_MESSAGE_SIZE));
        final AtomicBoolean firstByteWriteFutureDone = new AtomicBoolean();
        hf.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                firstByteWriteFutureDone.set(firstByteWriteFuture.isDone());
            }
        });
        hf.sync();

        assertFalse(firstByteWriteFutureDone.get());

        for (int i = FIRST_MESSAGE_SIZE; i < data.length;) {
            int length = Math.min(random.nextInt(1024 * 64), data.length - i);
            cc.write(Unpooled.wrappedBuffer(data, i, length));
            i += length;
        }

        while (ch.counter < data.length) {
            if (sh.exception.get() != null) {
                break;
            }
            if (ch.exception.get() != null) {
                break;
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // Ignore.
            }
        }

        while (sh.counter < data.length) {
            if (sh.exception.get() != null) {
                break;
            }
            if (ch.exception.get() != null) {
                break;
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // Ignore.
            }
        }

        sh.channel.close().awaitUninterruptibly();
        ch.channel.close().awaitUninterruptibly();
        sc.close().awaitUninterruptibly();

        if (sh.exception.get() != null && !(sh.exception.get() instanceof IOException)) {
            throw sh.exception.get();
        }
        if (ch.exception.get() != null && !(ch.exception.get() instanceof IOException)) {
            throw ch.exception.get();
        }
        if (sh.exception.get() != null) {
            throw sh.exception.get();
        }
        if (ch.exception.get() != null) {
            throw ch.exception.get();
        }
    }

    private class EchoHandler extends ChannelInboundByteHandlerAdapter {
        volatile Channel channel;
        final AtomicReference<Throwable> exception = new AtomicReference<Throwable>();
        volatile int counter;
        private final boolean server;

        EchoHandler(boolean server) {
            this.server = server;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx)
                throws Exception {
            channel = ctx.channel();
        }

        @Override
        public void inboundBufferUpdated(
                ChannelHandlerContext ctx, ByteBuf in)
                throws Exception {
            byte[] actual = new byte[in.readableBytes()];
            in.readBytes(actual);

            int lastIdx = counter;
            for (int i = 0; i < actual.length; i ++) {
                assertEquals(data[i + lastIdx], actual[i]);
            }

            if (channel.parent() != null) {
                channel.write(Unpooled.wrappedBuffer(actual));
            }

            counter += actual.length;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx,
                Throwable cause) throws Exception {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "Unexpected exception from the " +
                        (server? "server" : "client") + " side", cause);
            }

            exception.compareAndSet(null, cause);
            ctx.close();
        }
    }

    private static class BogusSslContextFactory {

        private static final String PROTOCOL = "TLS";
        private static final SSLContext SERVER_CONTEXT;
        private static final SSLContext CLIENT_CONTEXT;

        static {
            String algorithm = Security.getProperty("ssl.KeyManagerFactory.algorithm");
            if (algorithm == null) {
                algorithm = "SunX509";
            }

            SSLContext serverContext;
            SSLContext clientContext;
            try {
                KeyStore ks = KeyStore.getInstance("JKS");
                ks.load(BogusKeyStore.asInputStream(),
                        BogusKeyStore.getKeyStorePassword());

                // Set up key manager factory to use our key store
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);
                kmf.init(ks, BogusKeyStore.getCertificatePassword());

                // Initialize the SSLContext to work with our key managers.
                serverContext = SSLContext.getInstance(PROTOCOL);
                serverContext.init(kmf.getKeyManagers(), null, null);
            } catch (Exception e) {
                throw new Error(
                        "Failed to initialize the server-side SSLContext", e);
            }

            try {
                clientContext = SSLContext.getInstance(PROTOCOL);
                clientContext.init(null, BogusTrustManagerFactory.getTrustManagers(), null);
            } catch (Exception e) {
                throw new Error(
                        "Failed to initialize the client-side SSLContext", e);
            }

            SERVER_CONTEXT = serverContext;
            CLIENT_CONTEXT = clientContext;
        }

        public static SSLContext getServerContext() {
            return SERVER_CONTEXT;
        }

        public static SSLContext getClientContext() {
            return CLIENT_CONTEXT;
        }
    }

    /**
     * Bogus {@link TrustManagerFactorySpi} which accepts any certificate
     * even if it is invalid.
     */
    private static class BogusTrustManagerFactory extends TrustManagerFactorySpi {

        private static final TrustManager DUMMY_TRUST_MANAGER = new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(
                    X509Certificate[] chain, String authType) throws CertificateException {
                // NOOP
            }

            @Override
            public void checkServerTrusted(
                    X509Certificate[] chain, String authType) throws CertificateException {
                // NOOP
            }
        };

        public static TrustManager[] getTrustManagers() {
            return new TrustManager[] { DUMMY_TRUST_MANAGER };
        }

        @Override
        protected TrustManager[] engineGetTrustManagers() {
            return getTrustManagers();
        }

        @Override
        protected void engineInit(KeyStore keystore) throws KeyStoreException {
            // Unused
        }

        @Override
        protected void engineInit(ManagerFactoryParameters managerFactoryParameters)
                throws InvalidAlgorithmParameterException {
            // Unused
        }
    }

    /**
     * A bogus key store which provides all the required information to
     * create an example SSL connection.
     *
     * To generate a bogus key store:
     * <pre>
     * keytool  -genkey -alias bogus -keysize 2048 -validity 36500
     *          -keyalg RSA -dname "CN=bogus"
     *          -keypass secret -storepass secret
     *          -keystore cert.jks
     * </pre>
     */
    private static final class BogusKeyStore {
        private static final short[] DATA = {
            0xfe, 0xed, 0xfe, 0xed, 0x00, 0x00, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c,
            0x65, 0x00, 0x00, 0x01, 0x1a, 0x9f, 0x57, 0xa5,
            0x27, 0x00, 0x00, 0x01, 0x9a, 0x30, 0x82, 0x01,
            0x96, 0x30, 0x0e, 0x06, 0x0a, 0x2b, 0x06, 0x01,
            0x04, 0x01, 0x2a, 0x02, 0x11, 0x01, 0x01, 0x05,
            0x00, 0x04, 0x82, 0x01, 0x82, 0x48, 0x6d, 0xcf,
            0x16, 0xb5, 0x50, 0x95, 0x36, 0xbf, 0x47, 0x27,
            0x50, 0x58, 0x0d, 0xa2, 0x52, 0x7e, 0x25, 0xab,
            0x14, 0x1a, 0x26, 0x5e, 0x2d, 0x8a, 0x23, 0x90,
            0x60, 0x7f, 0x12, 0x20, 0x56, 0xd1, 0x43, 0xa2,
            0x6b, 0x47, 0x5d, 0xed, 0x9d, 0xd4, 0xe5, 0x83,
            0x28, 0x89, 0xc2, 0x16, 0x4c, 0x76, 0x06, 0xad,
            0x8e, 0x8c, 0x29, 0x1a, 0x9b, 0x0f, 0xdd, 0x60,
            0x4b, 0xb4, 0x62, 0x82, 0x9e, 0x4a, 0x63, 0x83,
            0x2e, 0xd2, 0x43, 0x78, 0xc2, 0x32, 0x1f, 0x60,
            0xa9, 0x8a, 0x7f, 0x0f, 0x7c, 0xa6, 0x1d, 0xe6,
            0x92, 0x9e, 0x52, 0xc7, 0x7d, 0xbb, 0x35, 0x3b,
            0xaa, 0x89, 0x73, 0x4c, 0xfb, 0x99, 0x54, 0x97,
            0x99, 0x28, 0x6e, 0x66, 0x5b, 0xf7, 0x9b, 0x7e,
            0x6d, 0x8a, 0x2f, 0xfa, 0xc3, 0x1e, 0x71, 0xb9,
            0xbd, 0x8f, 0xc5, 0x63, 0x25, 0x31, 0x20, 0x02,
            0xff, 0x02, 0xf0, 0xc9, 0x2c, 0xdd, 0x3a, 0x10,
            0x30, 0xab, 0xe5, 0xad, 0x3d, 0x1a, 0x82, 0x77,
            0x46, 0xed, 0x03, 0x38, 0xa4, 0x73, 0x6d, 0x36,
            0x36, 0x33, 0x70, 0xb2, 0x63, 0x20, 0xca, 0x03,
            0xbf, 0x5a, 0xf4, 0x7c, 0x35, 0xf0, 0x63, 0x1a,
            0x12, 0x33, 0x12, 0x58, 0xd9, 0xa2, 0x63, 0x6b,
            0x63, 0x82, 0x41, 0x65, 0x70, 0x37, 0x4b, 0x99,
            0x04, 0x9f, 0xdd, 0x5e, 0x07, 0x01, 0x95, 0x9f,
            0x36, 0xe8, 0xc3, 0x66, 0x2a, 0x21, 0x69, 0x68,
            0x40, 0xe6, 0xbc, 0xbb, 0x85, 0x81, 0x21, 0x13,
            0xe6, 0xa4, 0xcf, 0xd3, 0x67, 0xe3, 0xfd, 0x75,
            0xf0, 0xdf, 0x83, 0xe0, 0xc5, 0x36, 0x09, 0xac,
            0x1b, 0xd4, 0xf7, 0x2a, 0x23, 0x57, 0x1c, 0x5c,
            0x0f, 0xf4, 0xcf, 0xa2, 0xcf, 0xf5, 0xbd, 0x9c,
            0x69, 0x98, 0x78, 0x3a, 0x25, 0xe4, 0xfd, 0x85,
            0x11, 0xcc, 0x7d, 0xef, 0xeb, 0x74, 0x60, 0xb1,
            0xb7, 0xfb, 0x1f, 0x0e, 0x62, 0xff, 0xfe, 0x09,
            0x0a, 0xc3, 0x80, 0x2f, 0x10, 0x49, 0x89, 0x78,
            0xd2, 0x08, 0xfa, 0x89, 0x22, 0x45, 0x91, 0x21,
            0xbc, 0x90, 0x3e, 0xad, 0xb3, 0x0a, 0xb4, 0x0e,
            0x1c, 0xa1, 0x93, 0x92, 0xd8, 0x72, 0x07, 0x54,
            0x60, 0xe7, 0x91, 0xfc, 0xd9, 0x3c, 0xe1, 0x6f,
            0x08, 0xe4, 0x56, 0xf6, 0x0b, 0xb0, 0x3c, 0x39,
            0x8a, 0x2d, 0x48, 0x44, 0x28, 0x13, 0xca, 0xe9,
            0xf7, 0xa3, 0xb6, 0x8a, 0x5f, 0x31, 0xa9, 0x72,
            0xf2, 0xde, 0x96, 0xf2, 0xb1, 0x53, 0xb1, 0x3e,
            0x24, 0x57, 0xfd, 0x18, 0x45, 0x1f, 0xc5, 0x33,
            0x1b, 0xa4, 0xe8, 0x21, 0xfa, 0x0e, 0xb2, 0xb9,
            0xcb, 0xc7, 0x07, 0x41, 0xdd, 0x2f, 0xb6, 0x6a,
            0x23, 0x18, 0xed, 0xc1, 0xef, 0xe2, 0x4b, 0xec,
            0xc9, 0xba, 0xfb, 0x46, 0x43, 0x90, 0xd7, 0xb5,
            0x68, 0x28, 0x31, 0x2b, 0x8d, 0xa8, 0x51, 0x63,
            0xf7, 0x53, 0x99, 0x19, 0x68, 0x85, 0x66, 0x00,
            0x00, 0x00, 0x01, 0x00, 0x05, 0x58, 0x2e, 0x35,
            0x30, 0x39, 0x00, 0x00, 0x02, 0x3a, 0x30, 0x82,
            0x02, 0x36, 0x30, 0x82, 0x01, 0xe0, 0xa0, 0x03,
            0x02, 0x01, 0x02, 0x02, 0x04, 0x48, 0x59, 0xf1,
            0x92, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48,
            0x86, 0xf7, 0x0d, 0x01, 0x01, 0x05, 0x05, 0x00,
            0x30, 0x81, 0xa0, 0x31, 0x0b, 0x30, 0x09, 0x06,
            0x03, 0x55, 0x04, 0x06, 0x13, 0x02, 0x4b, 0x52,
            0x31, 0x13, 0x30, 0x11, 0x06, 0x03, 0x55, 0x04,
            0x08, 0x13, 0x0a, 0x4b, 0x79, 0x75, 0x6e, 0x67,
            0x67, 0x69, 0x2d, 0x64, 0x6f, 0x31, 0x14, 0x30,
            0x12, 0x06, 0x03, 0x55, 0x04, 0x07, 0x13, 0x0b,
            0x53, 0x65, 0x6f, 0x6e, 0x67, 0x6e, 0x61, 0x6d,
            0x2d, 0x73, 0x69, 0x31, 0x1a, 0x30, 0x18, 0x06,
            0x03, 0x55, 0x04, 0x0a, 0x13, 0x11, 0x54, 0x68,
            0x65, 0x20, 0x4e, 0x65, 0x74, 0x74, 0x79, 0x20,
            0x50, 0x72, 0x6f, 0x6a, 0x65, 0x63, 0x74, 0x31,
            0x18, 0x30, 0x16, 0x06, 0x03, 0x55, 0x04, 0x0b,
            0x13, 0x0f, 0x45, 0x78, 0x61, 0x6d, 0x70, 0x6c,
            0x65, 0x20, 0x41, 0x75, 0x74, 0x68, 0x6f, 0x72,
            0x73, 0x31, 0x30, 0x30, 0x2e, 0x06, 0x03, 0x55,
            0x04, 0x03, 0x13, 0x27, 0x73, 0x65, 0x63, 0x75,
            0x72, 0x65, 0x63, 0x68, 0x61, 0x74, 0x2e, 0x65,
            0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65, 0x2e, 0x6e,
            0x65, 0x74, 0x74, 0x79, 0x2e, 0x67, 0x6c, 0x65,
            0x61, 0x6d, 0x79, 0x6e, 0x6f, 0x64, 0x65, 0x2e,
            0x6e, 0x65, 0x74, 0x30, 0x20, 0x17, 0x0d, 0x30,
            0x38, 0x30, 0x36, 0x31, 0x39, 0x30, 0x35, 0x34,
            0x31, 0x33, 0x38, 0x5a, 0x18, 0x0f, 0x32, 0x31,
            0x38, 0x37, 0x31, 0x31, 0x32, 0x34, 0x30, 0x35,
            0x34, 0x31, 0x33, 0x38, 0x5a, 0x30, 0x81, 0xa0,
            0x31, 0x0b, 0x30, 0x09, 0x06, 0x03, 0x55, 0x04,
            0x06, 0x13, 0x02, 0x4b, 0x52, 0x31, 0x13, 0x30,
            0x11, 0x06, 0x03, 0x55, 0x04, 0x08, 0x13, 0x0a,
            0x4b, 0x79, 0x75, 0x6e, 0x67, 0x67, 0x69, 0x2d,
            0x64, 0x6f, 0x31, 0x14, 0x30, 0x12, 0x06, 0x03,
            0x55, 0x04, 0x07, 0x13, 0x0b, 0x53, 0x65, 0x6f,
            0x6e, 0x67, 0x6e, 0x61, 0x6d, 0x2d, 0x73, 0x69,
            0x31, 0x1a, 0x30, 0x18, 0x06, 0x03, 0x55, 0x04,
            0x0a, 0x13, 0x11, 0x54, 0x68, 0x65, 0x20, 0x4e,
            0x65, 0x74, 0x74, 0x79, 0x20, 0x50, 0x72, 0x6f,
            0x6a, 0x65, 0x63, 0x74, 0x31, 0x18, 0x30, 0x16,
            0x06, 0x03, 0x55, 0x04, 0x0b, 0x13, 0x0f, 0x45,
            0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65, 0x20, 0x41,
            0x75, 0x74, 0x68, 0x6f, 0x72, 0x73, 0x31, 0x30,
            0x30, 0x2e, 0x06, 0x03, 0x55, 0x04, 0x03, 0x13,
            0x27, 0x73, 0x65, 0x63, 0x75, 0x72, 0x65, 0x63,
            0x68, 0x61, 0x74, 0x2e, 0x65, 0x78, 0x61, 0x6d,
            0x70, 0x6c, 0x65, 0x2e, 0x6e, 0x65, 0x74, 0x74,
            0x79, 0x2e, 0x67, 0x6c, 0x65, 0x61, 0x6d, 0x79,
            0x6e, 0x6f, 0x64, 0x65, 0x2e, 0x6e, 0x65, 0x74,
            0x30, 0x5c, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86,
            0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05,
            0x00, 0x03, 0x4b, 0x00, 0x30, 0x48, 0x02, 0x41,
            0x00, 0xc3, 0xe3, 0x5e, 0x41, 0xa7, 0x87, 0x11,
            0x00, 0x42, 0x2a, 0xb0, 0x4b, 0xed, 0xb2, 0xe0,
            0x23, 0xdb, 0xb1, 0x3d, 0x58, 0x97, 0x35, 0x60,
            0x0b, 0x82, 0x59, 0xd3, 0x00, 0xea, 0xd4, 0x61,
            0xb8, 0x79, 0x3f, 0xb6, 0x3c, 0x12, 0x05, 0x93,
            0x2e, 0x9a, 0x59, 0x68, 0x14, 0x77, 0x3a, 0xc8,
            0x50, 0x25, 0x57, 0xa4, 0x49, 0x18, 0x63, 0x41,
            0xf0, 0x2d, 0x28, 0xec, 0x06, 0xfb, 0xb4, 0x9f,
            0xbf, 0x02, 0x03, 0x01, 0x00, 0x01, 0x30, 0x0d,
            0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d,
            0x01, 0x01, 0x05, 0x05, 0x00, 0x03, 0x41, 0x00,
            0x65, 0x6c, 0x30, 0x01, 0xc2, 0x8e, 0x3e, 0xcb,
            0xb3, 0x77, 0x48, 0xe9, 0x66, 0x61, 0x9a, 0x40,
            0x86, 0xaf, 0xf6, 0x03, 0xeb, 0xba, 0x6a, 0xf2,
            0xfd, 0xe2, 0xaf, 0x36, 0x5e, 0x7b, 0xaa, 0x22,
            0x04, 0xdd, 0x2c, 0x20, 0xc4, 0xfc, 0xdd, 0xd0,
            0x82, 0x20, 0x1c, 0x3d, 0xd7, 0x9e, 0x5e, 0x5c,
            0x92, 0x5a, 0x76, 0x71, 0x28, 0xf5, 0x07, 0x7d,
            0xa2, 0x81, 0xba, 0x77, 0x9f, 0x2a, 0xd9, 0x44,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x05, 0x6d, 0x79,
            0x6b, 0x65, 0x79, 0x00, 0x00, 0x01, 0x1a, 0x9f,
            0x5b, 0x56, 0xa0, 0x00, 0x00, 0x01, 0x99, 0x30,
            0x82, 0x01, 0x95, 0x30, 0x0e, 0x06, 0x0a, 0x2b,
            0x06, 0x01, 0x04, 0x01, 0x2a, 0x02, 0x11, 0x01,
            0x01, 0x05, 0x00, 0x04, 0x82, 0x01, 0x81, 0x29,
            0xa8, 0xb6, 0x08, 0x0c, 0x85, 0x75, 0x3e, 0xdd,
            0xb5, 0xe5, 0x1a, 0x87, 0x68, 0xd1, 0x90, 0x4b,
            0x29, 0x31, 0xee, 0x90, 0xbc, 0x9d, 0x73, 0xa0,
            0x3f, 0xe9, 0x0b, 0xa4, 0xef, 0x30, 0x9b, 0x36,
            0x9a, 0xb2, 0x54, 0x77, 0x81, 0x07, 0x4b, 0xaa,
            0xa5, 0x77, 0x98, 0xe1, 0xeb, 0xb5, 0x7c, 0x4e,
            0x48, 0xd5, 0x08, 0xfc, 0x2c, 0x36, 0xe2, 0x65,
            0x03, 0xac, 0xe5, 0xf3, 0x96, 0xb7, 0xd0, 0xb5,
            0x3b, 0x92, 0xe4, 0x14, 0x05, 0x7a, 0x6a, 0x92,
            0x56, 0xfe, 0x4e, 0xab, 0xd3, 0x0e, 0x32, 0x04,
            0x22, 0x22, 0x74, 0x47, 0x7d, 0xec, 0x21, 0x99,
            0x30, 0x31, 0x64, 0x46, 0x64, 0x9b, 0xc7, 0x13,
            0xbf, 0xbe, 0xd0, 0x31, 0x49, 0xe7, 0x3c, 0xbf,
            0xba, 0xb1, 0x20, 0xf9, 0x42, 0xf4, 0xa9, 0xa9,
            0xe5, 0x13, 0x65, 0x32, 0xbf, 0x7c, 0xcc, 0x91,
            0xd3, 0xfd, 0x24, 0x47, 0x0b, 0xe5, 0x53, 0xad,
            0x50, 0x30, 0x56, 0xd1, 0xfa, 0x9c, 0x37, 0xa8,
            0xc1, 0xce, 0xf6, 0x0b, 0x18, 0xaa, 0x7c, 0xab,
            0xbd, 0x1f, 0xdf, 0xe4, 0x80, 0xb8, 0xa7, 0xe0,
            0xad, 0x7d, 0x50, 0x74, 0xf1, 0x98, 0x78, 0xbc,
            0x58, 0xb9, 0xc2, 0x52, 0xbe, 0xd2, 0x5b, 0x81,
            0x94, 0x83, 0x8f, 0xb9, 0x4c, 0xee, 0x01, 0x2b,
            0x5e, 0xc9, 0x6e, 0x9b, 0xf5, 0x63, 0x69, 0xe4,
            0xd8, 0x0b, 0x47, 0xd8, 0xfd, 0xd8, 0xe0, 0xed,
            0xa8, 0x27, 0x03, 0x74, 0x1e, 0x5d, 0x32, 0xe6,
            0x5c, 0x63, 0xc2, 0xfb, 0x3f, 0xee, 0xb4, 0x13,
            0xc6, 0x0e, 0x6e, 0x74, 0xe0, 0x22, 0xac, 0xce,
            0x79, 0xf9, 0x43, 0x68, 0xc1, 0x03, 0x74, 0x2b,
            0xe1, 0x18, 0xf8, 0x7f, 0x76, 0x9a, 0xea, 0x82,
            0x3f, 0xc2, 0xa6, 0xa7, 0x4c, 0xfe, 0xae, 0x29,
            0x3b, 0xc1, 0x10, 0x7c, 0xd5, 0x77, 0x17, 0x79,
            0x5f, 0xcb, 0xad, 0x1f, 0xd8, 0xa1, 0xfd, 0x90,
            0xe1, 0x6b, 0xb2, 0xef, 0xb9, 0x41, 0x26, 0xa4,
            0x0b, 0x4f, 0xc6, 0x83, 0x05, 0x6f, 0xf0, 0x64,
            0x40, 0xe1, 0x44, 0xc4, 0xf9, 0x40, 0x2b, 0x3b,
            0x40, 0xdb, 0xaf, 0x35, 0xa4, 0x9b, 0x9f, 0xc4,
            0x74, 0x07, 0xe5, 0x18, 0x60, 0xc5, 0xfe, 0x15,
            0x0e, 0x3a, 0x25, 0x2a, 0x11, 0xee, 0x78, 0x2f,
            0xb8, 0xd1, 0x6e, 0x4e, 0x3c, 0x0a, 0xb5, 0xb9,
            0x40, 0x86, 0x27, 0x6d, 0x8f, 0x53, 0xb7, 0x77,
            0x36, 0xec, 0x5d, 0xed, 0x32, 0x40, 0x43, 0x82,
            0xc3, 0x52, 0x58, 0xc4, 0x26, 0x39, 0xf3, 0xb3,
            0xad, 0x58, 0xab, 0xb7, 0xf7, 0x8e, 0x0e, 0xba,
            0x8e, 0x78, 0x9d, 0xbf, 0x58, 0x34, 0xbd, 0x77,
            0x73, 0xa6, 0x50, 0x55, 0x00, 0x60, 0x26, 0xbf,
            0x6d, 0xb4, 0x98, 0x8a, 0x18, 0x83, 0x89, 0xf8,
            0xcd, 0x0d, 0x49, 0x06, 0xae, 0x51, 0x6e, 0xaf,
            0xbd, 0xe2, 0x07, 0x13, 0xd8, 0x64, 0xcc, 0xbf,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x05, 0x58, 0x2e,
            0x35, 0x30, 0x39, 0x00, 0x00, 0x02, 0x34, 0x30,
            0x82, 0x02, 0x30, 0x30, 0x82, 0x01, 0xda, 0xa0,
            0x03, 0x02, 0x01, 0x02, 0x02, 0x04, 0x48, 0x59,
            0xf2, 0x84, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86,
            0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x05, 0x05,
            0x00, 0x30, 0x81, 0x9d, 0x31, 0x0b, 0x30, 0x09,
            0x06, 0x03, 0x55, 0x04, 0x06, 0x13, 0x02, 0x4b,
            0x52, 0x31, 0x13, 0x30, 0x11, 0x06, 0x03, 0x55,
            0x04, 0x08, 0x13, 0x0a, 0x4b, 0x79, 0x75, 0x6e,
            0x67, 0x67, 0x69, 0x2d, 0x64, 0x6f, 0x31, 0x14,
            0x30, 0x12, 0x06, 0x03, 0x55, 0x04, 0x07, 0x13,
            0x0b, 0x53, 0x65, 0x6f, 0x6e, 0x67, 0x6e, 0x61,
            0x6d, 0x2d, 0x73, 0x69, 0x31, 0x1a, 0x30, 0x18,
            0x06, 0x03, 0x55, 0x04, 0x0a, 0x13, 0x11, 0x54,
            0x68, 0x65, 0x20, 0x4e, 0x65, 0x74, 0x74, 0x79,
            0x20, 0x50, 0x72, 0x6f, 0x6a, 0x65, 0x63, 0x74,
            0x31, 0x15, 0x30, 0x13, 0x06, 0x03, 0x55, 0x04,
            0x0b, 0x13, 0x0c, 0x43, 0x6f, 0x6e, 0x74, 0x72,
            0x69, 0x62, 0x75, 0x74, 0x6f, 0x72, 0x73, 0x31,
            0x30, 0x30, 0x2e, 0x06, 0x03, 0x55, 0x04, 0x03,
            0x13, 0x27, 0x73, 0x65, 0x63, 0x75, 0x72, 0x65,
            0x63, 0x68, 0x61, 0x74, 0x2e, 0x65, 0x78, 0x61,
            0x6d, 0x70, 0x6c, 0x65, 0x2e, 0x6e, 0x65, 0x74,
            0x74, 0x79, 0x2e, 0x67, 0x6c, 0x65, 0x61, 0x6d,
            0x79, 0x6e, 0x6f, 0x64, 0x65, 0x2e, 0x6e, 0x65,
            0x74, 0x30, 0x20, 0x17, 0x0d, 0x30, 0x38, 0x30,
            0x36, 0x31, 0x39, 0x30, 0x35, 0x34, 0x35, 0x34,
            0x30, 0x5a, 0x18, 0x0f, 0x32, 0x31, 0x38, 0x37,
            0x31, 0x31, 0x32, 0x33, 0x30, 0x35, 0x34, 0x35,
            0x34, 0x30, 0x5a, 0x30, 0x81, 0x9d, 0x31, 0x0b,
            0x30, 0x09, 0x06, 0x03, 0x55, 0x04, 0x06, 0x13,
            0x02, 0x4b, 0x52, 0x31, 0x13, 0x30, 0x11, 0x06,
            0x03, 0x55, 0x04, 0x08, 0x13, 0x0a, 0x4b, 0x79,
            0x75, 0x6e, 0x67, 0x67, 0x69, 0x2d, 0x64, 0x6f,
            0x31, 0x14, 0x30, 0x12, 0x06, 0x03, 0x55, 0x04,
            0x07, 0x13, 0x0b, 0x53, 0x65, 0x6f, 0x6e, 0x67,
            0x6e, 0x61, 0x6d, 0x2d, 0x73, 0x69, 0x31, 0x1a,
            0x30, 0x18, 0x06, 0x03, 0x55, 0x04, 0x0a, 0x13,
            0x11, 0x54, 0x68, 0x65, 0x20, 0x4e, 0x65, 0x74,
            0x74, 0x79, 0x20, 0x50, 0x72, 0x6f, 0x6a, 0x65,
            0x63, 0x74, 0x31, 0x15, 0x30, 0x13, 0x06, 0x03,
            0x55, 0x04, 0x0b, 0x13, 0x0c, 0x43, 0x6f, 0x6e,
            0x74, 0x72, 0x69, 0x62, 0x75, 0x74, 0x6f, 0x72,
            0x73, 0x31, 0x30, 0x30, 0x2e, 0x06, 0x03, 0x55,
            0x04, 0x03, 0x13, 0x27, 0x73, 0x65, 0x63, 0x75,
            0x72, 0x65, 0x63, 0x68, 0x61, 0x74, 0x2e, 0x65,
            0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65, 0x2e, 0x6e,
            0x65, 0x74, 0x74, 0x79, 0x2e, 0x67, 0x6c, 0x65,
            0x61, 0x6d, 0x79, 0x6e, 0x6f, 0x64, 0x65, 0x2e,
            0x6e, 0x65, 0x74, 0x30, 0x5c, 0x30, 0x0d, 0x06,
            0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01,
            0x01, 0x01, 0x05, 0x00, 0x03, 0x4b, 0x00, 0x30,
            0x48, 0x02, 0x41, 0x00, 0x95, 0xb3, 0x47, 0x17,
            0x95, 0x0f, 0x57, 0xcf, 0x66, 0x72, 0x0a, 0x7e,
            0x5b, 0x54, 0xea, 0x8c, 0x6f, 0x79, 0xde, 0x94,
            0xac, 0x0b, 0x5a, 0xd4, 0xd6, 0x1b, 0x58, 0x12,
            0x1a, 0x16, 0x3d, 0xfe, 0xdf, 0xa5, 0x2b, 0x86,
            0xbc, 0x64, 0xd4, 0x80, 0x1e, 0x3f, 0xf9, 0xe2,
            0x04, 0x03, 0x79, 0x9b, 0xc1, 0x5c, 0xf0, 0xf1,
            0xf3, 0xf1, 0xe3, 0xbf, 0x3f, 0xc0, 0x1f, 0xdd,
            0xdb, 0xc0, 0x5b, 0x21, 0x02, 0x03, 0x01, 0x00,
            0x01, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48,
            0x86, 0xf7, 0x0d, 0x01, 0x01, 0x05, 0x05, 0x00,
            0x03, 0x41, 0x00, 0x02, 0xd7, 0xdd, 0xbd, 0x0c,
            0x8e, 0x21, 0x20, 0xef, 0x9e, 0x4f, 0x1f, 0xf5,
            0x49, 0xf1, 0xae, 0x58, 0x9b, 0x94, 0x3a, 0x1f,
            0x70, 0x33, 0xf0, 0x9b, 0xbb, 0xe9, 0xc0, 0xf3,
            0x72, 0xcb, 0xde, 0xb6, 0x56, 0x72, 0xcc, 0x1c,
            0xf0, 0xd6, 0x5a, 0x2a, 0xbc, 0xa1, 0x7e, 0x23,
            0x83, 0xe9, 0xe7, 0xcf, 0x9e, 0xa5, 0xf9, 0xcc,
            0xc2, 0x61, 0xf4, 0xdb, 0x40, 0x93, 0x1d, 0x63,
            0x8a, 0x50, 0x4c, 0x11, 0x39, 0xb1, 0x91, 0xc1,
            0xe6, 0x9d, 0xd9, 0x1a, 0x62, 0x1b, 0xb8, 0xd3,
            0xd6, 0x9a, 0x6d, 0xb9, 0x8e, 0x15, 0x51 };

        public static InputStream asInputStream() {
            byte[] data = new byte[DATA.length];
            for (int i = 0; i < data.length; i ++) {
                data[i] = (byte) DATA[i];
            }
            return new ByteArrayInputStream(data);
        }

        public static char[] getCertificatePassword() {
            return "secret".toCharArray();
        }

        public static char[] getKeyStorePassword() {
            return "secret".toCharArray();
        }

        private BogusKeyStore() {
            // Unused
        }
    }
}
