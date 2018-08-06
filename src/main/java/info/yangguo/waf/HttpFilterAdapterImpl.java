package info.yangguo.waf;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import info.yangguo.waf.config.ContextHolder;
import info.yangguo.waf.model.WeightedRoundRobinScheduling;
import info.yangguo.waf.request.rewrite.RewriteFilter;
import info.yangguo.waf.request.security.CCSecurityFilter;
import info.yangguo.waf.request.security.PostSecurityFilter;
import info.yangguo.waf.request.security.SecurityFilter;
import info.yangguo.waf.request.security.SecurityFilterChain;
import info.yangguo.waf.response.HttpResponseFilterChain;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.impl.ClientToProxyConnection;
import org.littleshoot.proxy.impl.ProxyConnection;
import org.littleshoot.proxy.impl.ProxyToServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * @author:杨果
 * @date:2017/4/17 下午2:12
 * <p>
 * Description:
 */
public class HttpFilterAdapterImpl extends HttpFiltersAdapter {
    private static Logger logger = LoggerFactory.getLogger(HttpFilterAdapterImpl.class);
    private static final SecurityFilterChain SECURITY_FILTER_CHAIN = new SecurityFilterChain();
    private final HttpResponseFilterChain httpResponseFilterChain = new HttpResponseFilterChain();
    private final Cache<Object, Object> postReponseCache = CacheBuilder.newBuilder().maximumSize(10000).build();

    public HttpFilterAdapterImpl(HttpRequest originalRequest, ChannelHandlerContext ctx) {
        super(originalRequest, ctx);
    }

    @Override
    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
        HttpResponse httpResponse = null;
        try {
            RewriteFilter.doFilter(originalRequest, httpObject);
        } catch (Exception e) {
            httpResponse = createResponse(HttpResponseStatus.BAD_GATEWAY, originalRequest);
            logger.error("client's request failed when rewrite", e.getCause());
        }
        try {
            ImmutablePair<Boolean, SecurityFilter> immutablePair = SECURITY_FILTER_CHAIN.doFilter(originalRequest, httpObject, ctx, ContextHolder.getClusterService());
            if (immutablePair.left) {
                if (immutablePair.right instanceof CCSecurityFilter) {
                    httpResponse = createResponse(HttpResponseStatus.SERVICE_UNAVAILABLE, originalRequest);
                } else if (immutablePair.right instanceof PostSecurityFilter) {
                    httpResponse = createResponse(HttpResponseStatus.FORBIDDEN, originalRequest);
                    postReponseCache.put(ctx.channel().id().asLongText(), httpResponse);
                } else {
                    httpResponse = createResponse(HttpResponseStatus.FORBIDDEN, originalRequest);
                }
            }
        } catch (Exception e) {
            httpResponse = createResponse(HttpResponseStatus.BAD_GATEWAY, originalRequest);
            logger.error("client's request failed when security filter", e.getCause());
        }
        return httpResponse;
    }

    @Override
    public void proxyToServerResolutionSucceeded(String serverHostAndPort,
                                                 InetSocketAddress resolvedRemoteAddress) {
        if (resolvedRemoteAddress == null) {
            //在使用 Channel 写数据之前，建议使用 isWritable() 方法来判断一下当前 ChannelOutboundBuffer 里的写缓存水位，防止 OOM 发生。不过实践下来，正常的通信过程不太会 OOM，但当网络环境不好，同时传输报文很大时，确实会出现限流的情况。
            if (ctx.channel().isWritable()) {
                ctx.writeAndFlush(createResponse(HttpResponseStatus.BAD_GATEWAY, originalRequest));
            }
        }
    }

    /**
     * <b>Important:</b>：这个只能用在HTTP1.1上
     * 浏览器->Nginx->Waf->Tomcat，如果Nginx->Waf是Http1.0，那么Waf->Tomcat之间的链路会自动关闭，而关闭之时，Waf有可能还没有将报文返回给Nginx，所以
     * Nginx上会有大量的<b>upstream prematurely closed connection while reading upstream</b>异常！这样设计的前提是，waf->server的链接关闭只有两种情况
     * <p>
     * 1. idle超时关闭。
     * <p>
     * 2. 异常关闭，例如大文件上传超过tomcat中程序允许上传的最大值，并且tomcat未设置maxswallow时，从而导致tomcat发送RST。
     * <p>
     * 代理链接的是两个或多个使用相同协议的应用程序，此处的相同非常重要，所以中间最少别随意跟换协议！！
     */
    @Override
    public void proxyToServerRequestSending() {
        ClientToProxyConnection clientToProxyConnection = (ClientToProxyConnection) ctx.handler();
        ProxyConnection proxyConnection = clientToProxyConnection.getProxyToServerConnection();
        logger.debug("client channel:{}-{}", clientToProxyConnection.getChannel().localAddress().toString(), clientToProxyConnection.getChannel().remoteAddress().toString());
        logger.debug("server channel:{}-{}", proxyConnection.getChannel().localAddress().toString(), proxyConnection.getChannel().remoteAddress().toString());
        proxyConnection.getChannel().closeFuture().addListener(new GenericFutureListener() {
            @Override
            public void operationComplete(Future future) {
                if (clientToProxyConnection.getChannel().isActive()) {
                    logger.debug("channel:{}-{} will be closed", clientToProxyConnection.getChannel().localAddress().toString(), clientToProxyConnection.getChannel().remoteAddress().toString());
                    clientToProxyConnection.getChannel().close();
                } else {
                    logger.debug("channel:{}-{} has been closed", clientToProxyConnection.getChannel().localAddress().toString(), clientToProxyConnection.getChannel().remoteAddress().toString());
                }
            }
        });
    }

    @Override
    public HttpObject proxyToClientResponse(HttpObject httpObject) {
        if (httpObject instanceof HttpResponse) {
            try {
                if (postReponseCache.getIfPresent(ctx.channel().id().asLongText()) != null) {
                    httpObject = (HttpResponse) postReponseCache.getIfPresent(ctx.channel().id().asLongText());
                    postReponseCache.invalidate(ctx.channel().id().asLongText());
                } else {
                    httpResponseFilterChain.doFilter(originalRequest, (HttpResponse) httpObject, ContextHolder.getClusterService());
                }
            } catch (Exception e) {
                logger.error("response filter failed", e.getCause());
            }
        }
        return httpObject;
    }

    @Override
    public void proxyToServerConnectionFailed() {
        if ("on".equals(Constant.wafConfs.get("waf.lb"))) {
            try {
                ClientToProxyConnection clientToProxyConnection = (ClientToProxyConnection) ctx.handler();
                ProxyToServerConnection proxyToServerConnection = clientToProxyConnection.getProxyToServerConnection();

                String serverHostAndPort = proxyToServerConnection.getServerHostAndPort().replace(":", "_");

                String remoteHostName = proxyToServerConnection.getRemoteAddress().getAddress().getHostAddress();
                int remoteHostPort = proxyToServerConnection.getRemoteAddress().getPort();

                WeightedRoundRobinScheduling weightedRoundRobinScheduling = ContextHolder.getClusterService().getUpstreamConfig().get(serverHostAndPort);
                weightedRoundRobinScheduling.getUnhealthilyServerConfigs().add(weightedRoundRobinScheduling.getServersMap().get(remoteHostName + "_" + remoteHostPort));
                weightedRoundRobinScheduling.getHealthilyServerConfigs().remove(weightedRoundRobinScheduling.getServersMap().get(remoteHostName + "_" + remoteHostPort));
            } catch (Exception e) {
                logger.error("connection of proxy->server is failed", e);
            }
        }
    }

    @Override
    public void proxyToServerConnectionSucceeded(final ChannelHandlerContext serverCtx) {
        ChannelPipeline pipeline = serverCtx.pipeline();
        //当没有修改getMaximumResponseBufferSizeInBytes中buffer默认的大小时,下面两个handler是不存在的
        if (pipeline.get("inflater") != null) {
            pipeline.remove("inflater");
        }
        if (pipeline.get("aggregator") != null) {
            pipeline.remove("aggregator");
        }
        super.proxyToServerConnectionSucceeded(serverCtx);
    }

    private static HttpResponse createResponse(HttpResponseStatus httpResponseStatus, HttpRequest originalRequest) {
        HttpHeaders httpHeaders = new DefaultHttpHeaders();
        httpHeaders.add("Transfer-Encoding", "chunked");
        httpHeaders.add("Connection", "close");//如果不关闭，下游的server接收到部分数据会一直等待知道超时，会报如下大概异常
        //I/O error while reading input message; nested exception is java.net.SocketTimeoutException
        HttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpResponseStatus);

        //support CORS
        String origin = originalRequest.headers().getAsString(HttpHeaderNames.ORIGIN);
        if (origin != null) {
            httpHeaders.set("Access-Control-Allow-Credentials", "true");
            httpHeaders.set("Access-Control-Allow-Origin", origin);
        }
        httpResponse.headers().add(httpHeaders);
        return httpResponse;
    }
}
