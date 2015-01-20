// @formatter:off
/*
 * Pexel Project - Minecraft minigame server platform. 
 * Copyright (C) 2014 Matej Kormuth <http://www.matejkormuth.eu>
 * 
 * This file is part of Pexel.
 * 
 * Pexel is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * 
 * Pexel is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 */
// @formatter:on
package eu.matejkormuth.pexel.network;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyServerComunicator extends MessageComunicator {
    protected final ChannelGroup                                              channels        = new DefaultChannelGroup(
                                                                                                      GlobalEventExecutor.INSTANCE);
    protected Map<String, ChannelHandlerContext>                              ctxByName       = new HashMap<String, ChannelHandlerContext>();
    protected Map<ChannelHandlerContext, ServerInfo>                          serverInfoByCTX = new HashMap<ChannelHandlerContext, ServerInfo>();
    protected MasterServer                                                    server;
    protected String                                                          authKey;
    
    protected Map<ChannelHandlerContext, PriorityBlockingQueue<NettyMessage>> queues          = new HashMap<ChannelHandlerContext, PriorityBlockingQueue<NettyMessage>>();
    
    protected Logger                                                          log             = LoggerFactory.getLogger(NettyServerComunicator.class);
    protected ServerBootstrap                                                 b;
    protected int                                                             port;
    
    static final boolean                                                      SSL             = System.getProperty("ssl") != null;
    
    public NettyServerComunicator(final PayloadHandler handler, final int port,
            final String authKey, final MasterServer server) {
        super(handler);
        
        this.authKey = authKey;
        this.server = server;
        this.port = port;
    }
    
    public void closeConnection(final ServerInfo server) {
        this.log.info("Closing connection for slave {}...", server.getName());
        ChannelHandlerContext ctx = this.ctxByName.get(server.getName());
        if (!this.queues.get(ctx).isEmpty()) {
            this.log.warn(" Throwing to trash " + this.queues.get(ctx).size()
                    + " unsend messages!");
        }
        this.queues.remove(ctx);
        
        this.serverInfoByCTX.remove(this.ctxByName.get(server.getName()));
        ctx.close();
        this.ctxByName.remove(server.getName());
    }
    
    @Override
    public void start() {
        // Use separated thread for netty, to not block main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    NettyServerComunicator.this.init(NettyServerComunicator.this.port);
                } catch (SSLException | CertificateException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, "Netty-ListenThread").start();;
    }
    
    @Override
    public void stop() {
        this.b.group().shutdownGracefully();
    }
    
    public void init(final int port) throws SSLException, CertificateException,
            InterruptedException {
        this.log.info("Initializing SSL...");
        SelfSignedCertificate ssc = new SelfSignedCertificate("pexel.eu");
        SslContext sslCtx = SslContext.newServerContext(ssc.certificate(),
                ssc.privateKey());
        
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            this.log.info("Starting up server...");
            this.b = new ServerBootstrap();
            this.b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new NettyServerComunicatorInitializer(sslCtx));
            
            workerGroup.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    NettyServerComunicator.this.sendQueues();
                }
            }, 0L, 10L, TimeUnit.MILLISECONDS);
            
            this.b.bind(port).sync().channel().closeFuture().sync();
        }
        finally {
            this.log.info("Stopping server...");
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
    
    protected void sendQueues() {
        for (ChannelHandlerContext ctx : this.queues.keySet()) {
            while (!this.queues.get(ctx).isEmpty()) {
                ctx.writeAndFlush(this.queues.get(ctx).poll());
            }
        }
    }
    
    @Override
    public void send(final ServerInfo target, final byte[] payload, final int priority) {
        ChannelHandlerContext ctx = this.getCTX(target);
        if (this.queues.containsKey(ctx)) {
            this.queues.get(ctx).add(new NettyMessage(payload, priority));
        } else {
            this.queues.put(ctx, new PriorityBlockingQueue<NettyMessage>());
            this.queues.get(ctx).add(new NettyMessage(payload, priority));
        }
    }
    
    @Override
    public void send(final ServerInfo target, final byte[] payload) {
        this.send(target, payload, 0);
    }
    
    public ServerInfo getServerInfo(final ChannelHandlerContext ctx) {
        return this.serverInfoByCTX.get(ctx);
    }
    
    public ChannelHandlerContext getCTX(final ServerInfo serverinfo) {
        return this.ctxByName.get(serverinfo.getName());
    }
    
    class NettyServerComunicatorInitializer extends ChannelInitializer<SocketChannel> {
        private final SslContext sslCtx;
        
        public NettyServerComunicatorInitializer(final SslContext sslCtx) {
            this.sslCtx = sslCtx;
        }
        
        @Override
        public void initChannel(final SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            
            // Add SSL handler first to encrypt and decrypt everything.
            // In this example, we use a bogus certificate in the server side
            // and accept any invalid certificates in the client side.
            // You will need something more complicated to identify both
            // and server in the real world.
            pipeline.addLast(this.sslCtx.newHandler(ch.alloc()));
            
            // On top of the SSL handler, add messages decoder and encoder.
            pipeline.addLast(new IntegerHeaderFrameDecoder());
            pipeline.addLast(new IntegerHeaderFrameEncoder());
            pipeline.addLast(new NettyMessageDecoder());
            pipeline.addLast(new NettyMessageEncoder());
            
            // and then business logic.
            pipeline.addLast(new NettyServerComunicatorHandler(
                    NettyServerComunicator.this));
        }
    }
    
    private static class NettyServerComunicatorHandler extends
            SimpleChannelInboundHandler<NettyMessage> {
        private final NettyServerComunicator i;
        
        public NettyServerComunicatorHandler(
                final NettyServerComunicator nettyServerComunicator) {
            this.i = nettyServerComunicator;
        }
        
        @Override
        public void channelActive(final ChannelHandlerContext ctx) {
            // Once session is secured, send a greeting and register the channel to the global channel
            // list so the channel received the messages from others.
            ctx.pipeline()
                    .get(SslHandler.class)
                    .handshakeFuture()
                    .addListener(new GenericFutureListener<Future<Channel>>() {
                        @Override
                        public void operationComplete(final Future<Channel> future)
                                throws Exception {
                            // Client logged in.
                            
                            // He should send log in packet.
                            NettyServerComunicatorHandler.this.i.log.debug("SSL handshake ok!");
                        }
                    });
        }
        
        @Override
        public void channelRead0(final ChannelHandlerContext ctx, final NettyMessage msg)
                throws Exception {
            this.i.log.debug(">NettyMessage#" + msg.hashCode() + " - "
                    + msg.payload.length + "[" + new String(msg.payload) + "]");
            // Invoke onReceive if registered server.
            if (this.i.channels.contains(ctx.channel())) {
                this.i.onReceive(this.i.getServerInfo(ctx), msg.payload);
            } else {
                // Try to register.
                if (NettyRegisterMesssage.validate(msg.payload, this.i.authKey)) {
                    // Add connected server to pool.
                    NettyServerComunicatorHandler.this.i.channels.add(ctx.channel());
                    String name = NettyRegisterMesssage.extractName(msg.payload);
                    SlaveServer server = new SlaveServer(name);
                    server.setCustom("ip", ((InetSocketAddress) ctx.channel()
                            .remoteAddress()).getHostString());
                    NettyServerComunicatorHandler.this.i.ctxByName.put(name, ctx);
                    NettyServerComunicatorHandler.this.i.server.addSlave(server);
                    NettyServerComunicatorHandler.this.i.serverInfoByCTX.put(ctx, server);
                    this.i.log.info("Registered new SLAVE server:{};ctx:{}", name,
                            ctx.hashCode());
                } else {
                    // Bad login. Disconnect.
                    this.i.log.warn("Bad login authKey from {}", ctx.name());
                    ctx.close();
                }
            }
        }
        
        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx,
                final Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
        
    }
}
