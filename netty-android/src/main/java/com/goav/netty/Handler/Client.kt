/*
 * Copyright (c) 2016.
 * chinaume@163.com
 */

package com.goav.netty.Handler

import android.system.Os.shutdown
import android.util.TimeUtils
import com.goav.netty.Impl.ChannelConnectImpl
import com.goav.netty.Impl.ClientImpl
import com.goav.netty.Impl.ClientOptImpl
import com.goav.netty.message.MessageBasic
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.internal.logging.InternalLoggerFactory
import java.util.concurrent.*

/**
 * 长链接
 *
 * @time: 16/10/8 12:41.<br></br>
 * @author: Created by moo<br></br>
 */

internal class Client constructor() : ClientImpl, ClientOptImpl {

    private var socketChannel: SocketChannel? = null
    private val service: ScheduledExecutorService
    private val messageSupers: BlockingDeque<MessageBasic>
    private var onDestrOY: Boolean
    private val logger = InternalLoggerFactory.getInstance(this.javaClass)
    private var bootstrap: Bootstrap? = null

    private var host: String = ""
    private var port: Int = 0
    private var callBack: ChannelConnectImpl = ChannelConnectImpl.DEFAULT
    private var readerIdleTimeSeconds: Int = 60
    private var writerIdleTimeSeconds: Int = 60
    private var allIdleTimeSeconds: Int = 90
    private var reConnect: ReConnect? = null
    private var mThread: Thread? = null
    private var eventLoopGroup: NioEventLoopGroup? = null

    init {
        eventLoopGroup = NioEventLoopGroup();
        service = Executors.newSingleThreadScheduledExecutor()
        messageSupers = LinkedBlockingDeque()
        onDestrOY = false

        mThread = object : Thread("Socket_Push_Message") {
            override fun run() {
                while (!onDestrOY) {
                    var message: Any?
                    try {
                        message = messageSupers.take()
                        if (socketChannel == null) {
                            request(message)
                            TimeUnit.SECONDS.sleep(1)
                        } else {
                            socketChannel?.writeAndFlush(message)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        mThread!!.start()
    }


    override fun reConnect(reConnect: ReConnect): ClientOptImpl {
        this.reConnect = reConnect
        return this
    }

    override
    fun address(host: String, port: Int): ClientOptImpl {
        this.host = host
        this.port = port
        return this
    }

    override
    fun addTimeOut(readerIdleTimeSeconds: Int, writerIdleTimeSeconds: Int, allIdleTimeSeconds: Int): ClientOptImpl {
        this.readerIdleTimeSeconds = readerIdleTimeSeconds
        this.writerIdleTimeSeconds = writerIdleTimeSeconds
        this.allIdleTimeSeconds = allIdleTimeSeconds
        return this
    }

    override
    fun addCallBack(callBack: ChannelConnectImpl): ClientOptImpl {
        this.callBack = callBack
        return this
    }


    override
    fun build(): ClientImpl {

        bootstrap = Bootstrap()
        bootstrap!!.channel(NioSocketChannel::class.java)
                .group(eventLoopGroup)
                .remoteAddress(host, port)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    @kotlin.jvm.Throws(java.lang.Exception::class)
                    override fun initChannel(ch: SocketChannel) {
                        var pipeline = ch.pipeline()
                                .addLast(IdleStateHandler(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds)
                                        , EncodeHandler()
                                        , DecodeHandler())

                        callBack.addChannelHandler(pipeline)
                    }
                })
        return this
    }


    private fun InitializationWithWorkThread() {

        if (onDestrOY) return

        try {
            val future = bootstrap!!.connect().sync()
            if (future.isSuccess) {
                socketChannel = future.channel() as SocketChannel
            } else {
                socketChannel?.disconnect()
                socketChannel?.close()

                if (reConnect != null && reConnect!!.able) {
                    future.channel().eventLoop().schedule(
                            { InitializationWithWorkThread() },
                            reConnect!!.time,
                            TimeUnit.SECONDS
                    )
                } else {
                    throw InterruptedException()
                }
            }


        } catch (e: Exception) {
            logger.info("socketChannel连接失败", e)
            //            eventLoopGroup.shutdownGracefully();//it's can't restart when server close
        } finally {
            callBack.onConnectCallBack(socketChannel)
        }

    }

    override fun connect() {
        if (onDestrOY) return
        logger.info("socket 连接2s后建立")
        service.schedule({ InitializationWithWorkThread() }, 2, TimeUnit.SECONDS)
    }

    override fun request(message: MessageBasic) {
        try {
            messageSupers.put(message)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    override fun destroy() {
        onDestrOY = true
        mThread?.interrupt()
        mThread?.join()
        messageSupers.clear()
        socketChannel?.close()
        socketChannel = null
        bootstrap = null
        shutdown(service)
        try {
            eventLoopGroup?.shutdownGracefully()
        } catch (e: Exception) {
        } finally {
            eventLoopGroup = null
        }
    }
}

@JvmSynthetic
fun shutdown(service: ExecutorService): Unit {
    service.shutdown()
    try {
        if (!service.awaitTermination(1, TimeUnit.SECONDS)) {
            service.shutdownNow()
        }
    } catch (e: Exception) {

    } finally {
        service.shutdownNow()
    }
}


class ReConnect(val able: Boolean, val time: Long) {
}
