package com.myexampleproject.apigateway.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.netty.channel.ChannelOption;

import java.time.Duration;

@Configuration
public class GatewayNettyConfig {
    @Bean
    public NettyReactiveWebServerFactory nettyReactiveWebServerFactory() {
        NettyReactiveWebServerFactory factory = new NettyReactiveWebServerFactory();
        factory.addServerCustomizers(httpServer ->
                httpServer
                        .option(ChannelOption.SO_BACKLOG, 4096)     // tăng queue socket chờ accept
                        .option(ChannelOption.SO_REUSEADDR, true)
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
        );
        return factory;
    }
}
