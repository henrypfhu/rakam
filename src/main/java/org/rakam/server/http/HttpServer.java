package org.rakam.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.rakam.kume.Cluster;
import org.rakam.server.RouteMatcher;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.util.HostAddress;
import org.rakam.util.JsonHelper;
import org.rakam.util.RakamException;
import org.rakam.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Path;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static org.rakam.util.Lambda.produceLambda;

/**
 * Created by buremba on 20/12/13.
 */
public class HttpServer {
    final static Logger LOGGER = LoggerFactory.getLogger(Cluster.class);
    private static String REQUEST_HANDLER_ERROR_MESSAGE = "Request handler method %s.%s couldn't converted to request handler lambda expression: \n %s";

    public final RouteMatcher routeMatcher;
    private final HttpServerConfig config;

    EventLoopGroup bossGroup;
    EventLoopGroup workerGroup;
    private Channel channel;

    @Inject
    public HttpServer(HttpServerConfig config, Set<HttpService> httpServicePlugins) {
        this.config = checkNotNull(config, "config is null");
        this.routeMatcher = new RouteMatcher();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        registerPaths(httpServicePlugins);
    }

    private void registerPaths(Set<HttpService> httpServicePlugins) {
        httpServicePlugins.forEach(service -> {
            String mainPath = service.getClass().getAnnotation(Path.class).value();
            if(mainPath == null) {
                throw new IllegalStateException(format("Classes that implement HttpService must have %s annotation.", Path.class.getCanonicalName()));
            }
            RouteMatcher.MicroRouteMatcher microRouteMatcher = new RouteMatcher.MicroRouteMatcher(routeMatcher, mainPath);
            for (Method method : service.getClass().getMethods()) {
                Path annotation = method.getAnnotation(Path.class);

                if (annotation != null) {
                    String lastPath = annotation.value();
                    JsonRequest jsonRequest = method.getAnnotation(JsonRequest.class);
                    boolean mapped = false;
                    for (Annotation ann : method.getAnnotations()) {
                        javax.ws.rs.HttpMethod methodAnnotation = ann.annotationType().getAnnotation(javax.ws.rs.HttpMethod.class);

                        if (methodAnnotation != null) {
                            HttpRequestHandler handler = null;
                            HttpMethod httpMethod = HttpMethod.valueOf(methodAnnotation.value());
                            try {
                                if (jsonRequest == null) {
                                    handler = generateRequestHandler(service, method);
                                } else if (httpMethod == HttpMethod.POST) {
                                    mapped = true;
                                    handler = createPostRequestHandler(service, method);
                                } else if (httpMethod == HttpMethod.GET) {
                                    mapped = true;
                                    handler = createGetRequestHandler(service, method);
                                }
                            } catch (Throwable e) {
                                throw new RuntimeException(format(REQUEST_HANDLER_ERROR_MESSAGE,
                                        method.getClass().getName(), method.getName(), e));
                            }

                            microRouteMatcher.add(lastPath, httpMethod, handler);
                            if (lastPath.equals("/"))
                                microRouteMatcher.add("", httpMethod, handler);
                        }
                    }
                    if (!mapped && jsonRequest != null) {
//                        throw new IllegalStateException(format("Methods that have @JsonRequest annotation must also include one of HTTPStatus annotations. %s", method.toString()));
                        try {
                            microRouteMatcher.add(lastPath, HttpMethod.POST, createPostRequestHandler(service, method));
                            if(method.getParameterTypes()[0].equals(JsonNode.class))
                                microRouteMatcher.add(lastPath, HttpMethod.GET, createGetRequestHandler(service, method));
                        } catch (Throwable e) {
                            throw new RuntimeException(format(REQUEST_HANDLER_ERROR_MESSAGE,
                                    method.getDeclaringClass().getName(), method.getName(), e));
                        }
                    }
                }
            }
        });
    }

    private static BiFunction<HttpService, Object, Object> generateJsonRequestHandler(Method method) throws Throwable {
//        if (!Object.class.isAssignableFrom(method.getReturnType()) ||
//                method.getParameterCount() != 1 ||
//                !method.getParameterTypes()[0].equals(JsonNode.class))
//            throw new IllegalStateException(format("The signature of @JsonRequest methods must be [Object (%s)]", JsonNode.class.getCanonicalName()));

        MethodHandles.Lookup caller = MethodHandles.lookup();
        return produceLambda(caller, method, BiFunction.class.getMethod("apply", Object.class, Object.class));
    }

    private static HttpRequestHandler generateRequestHandler(HttpService service, Method method) throws Throwable {
        if (!method.getReturnType().equals(void.class) ||
                method.getParameterCount() != 1 ||
                !method.getParameterTypes()[0].equals(RakamHttpRequest.class))
            throw new IllegalStateException(format("The signature of HTTP request methods must be [void ()]", RakamHttpRequest.class.getCanonicalName()));

        MethodHandles.Lookup caller = MethodHandles.lookup();

        if (Modifier.isStatic(method.getModifiers())) {
            Consumer<RakamHttpRequest> lambda;
            lambda = produceLambda(caller, method, Consumer.class.getMethod("accept", Object.class));
            return request -> lambda.accept(request);
        } else {
            BiConsumer<HttpService, RakamHttpRequest> lambda;
            lambda = produceLambda(caller, method, BiConsumer.class.getMethod("accept", Object.class, Object.class));
            return request -> lambda.accept(service, request);
        }
    }

    private static HttpRequestHandler createPostRequestHandler(HttpService service, Method method) throws Throwable {

        BiFunction<HttpService, Object, Object> function = generateJsonRequestHandler(method);
        Class<?> jsonClazz = method.getParameterTypes()[0];
        return (request) -> request.bodyHandler(obj -> {
            Object json;
            try {
                json = JsonHelper.read(obj, jsonClazz);
            } catch (UnrecognizedPropertyException e) {
                returnError(request, "unrecognized field: " + e.getPropertyName(), 400);
                return;
            } catch (InvalidFormatException e) {
                returnError(request, format("field value couldn't validated: %s ", e.getOriginalMessage()), 400);
                return;
            } catch (IOException e) {
                returnError(request, "json couldn't parsed: " + e.getMessage(), 400);
                return;
            }

            handleJsonRequest(service, request, function, json, false);
        });
    }

    private static HttpRequestHandler createGetRequestHandler(HttpService service, Method method) throws Throwable {
        BiFunction<HttpService, Object, Object> function = generateJsonRequestHandler(method);

        if(method.getParameterTypes()[0].equals(ObjectNode.class)) {
            return (request) -> {
                ObjectNode json = JsonHelper.generate(request.params());

                boolean prettyPrint = JsonHelper.getOrDefault(json, "prettyprint", false);
                handleJsonRequest(service, request, function, json, prettyPrint);
            };
        }else {
            return (request) -> {
                ObjectNode json = JsonHelper.generate(request.params());
                handleJsonRequest(service, request, function, json, false);
            };
        }
    }

    private static void handleJsonRequest(HttpService serviceInstance, RakamHttpRequest request, BiFunction<HttpService, Object, Object> function, Object json, boolean prettyPrint) {
        try {
            Object apply = function.apply(serviceInstance, json);
            String response = JsonHelper.encode(apply, prettyPrint);
            request.response(response).end();
        } catch (RakamException e) {
            int statusCode = e.getStatusCode();
            String encode = JsonHelper.encode(errorMessage(e.getMessage(), statusCode), prettyPrint);
            request.response(encode, HttpResponseStatus.valueOf(statusCode)).end();
        } catch (Exception e) {
            LOGGER.error("An uncaught exception raised while processing request.", e);
            ObjectNode errorMessage = errorMessage("error processing request.", HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
            request.response(JsonHelper.encode(errorMessage, prettyPrint), HttpResponseStatus.BAD_REQUEST).end();
        }
    }

    public void bind() throws InterruptedException {
            ServerBootstrap b = new ServerBootstrap();
            b.option(ChannelOption.SO_BACKLOG, 1024);
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast("httpCodec", new HttpServerCodec());
                            p.addLast("serverHandler", new HttpServerHandler(routeMatcher));
                        }
                    });

            HostAddress address = config.getAddress();
            channel = b.bind(address.getHostText(), address.getPort()).sync().channel();
    }

    public void stop() {
        if(channel != null)
            channel.close().syncUninterruptibly();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    public static void returnError(RakamHttpRequest request, String message, Integer statusCode) {
        JsonObject obj = new JsonObject();
        obj.put("error", message);
        obj.put("error_code", statusCode);

        request.response(obj.encode(), HttpResponseStatus.valueOf(statusCode))
                .headers().set("Content-Type", "application/json; charset=utf-8");
        request.end();
    }

    public static ObjectNode errorMessage(String message, int statusCode) {
        return JsonHelper.jsonObject()
                .put("error", message)
                .put("error_code", statusCode);
    }

    public boolean isDisabled() {
        return config.getDisabled();
    }
}