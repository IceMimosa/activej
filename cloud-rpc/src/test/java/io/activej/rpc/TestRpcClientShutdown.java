package io.activej.rpc;

import io.activej.eventloop.Eventloop;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.rpc.client.RpcClient;
import io.activej.rpc.client.RpcClientConnection;
import io.activej.rpc.server.RpcServer;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.promise.TestUtils.awaitException;
import static io.activej.rpc.client.RpcClientConnection.CONNECTION_CLOSED;
import static io.activej.rpc.client.sender.RpcStrategies.server;
import static io.activej.test.TestUtils.getFreePort;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertSame;

public final class TestRpcClientShutdown {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	public int port;

	@Before
	public void setUp() {
		port = getFreePort();
	}

	@Test
	public void testServerOnClientShutdown() throws IOException {
		Eventloop eventloop = Eventloop.getCurrentEventloop();
		Executor executor = Executors.newSingleThreadExecutor();
		List<Class<?>> messageTypes = asList(Request.class, Response.class);

		RpcServer rpcServer = RpcServer.create(eventloop)
				.withMessageTypes(messageTypes)
				.withHandler(Request.class,
						request -> Promise.ofBlockingCallable(executor, () -> {
							Thread.sleep(100);
							return new Response();
						}))
				.withListenPort(port);

		RpcClient rpcClient = RpcClient.create(eventloop)
				.withMessageTypes(messageTypes)
				.withStrategy(server(new InetSocketAddress(port)));

		rpcServer.listen();

		Throwable exception = awaitException(rpcClient.start()
				.then(() -> Promises.all(
						rpcClient.sendRequest(new Request())
								.whenComplete(() -> {
									for (RpcClientConnection conn : rpcClient.getRequestStatsPerConnection().values()) {
										conn.onSenderError(new Error());
									}
								}),
						rpcClient.sendRequest(new Request()),
						rpcClient.sendRequest(new Request()),
						rpcClient.sendRequest(new Request()),
						rpcClient.sendRequest(new Request())
				))
				.whenComplete(rpcClient::stop)
				.whenComplete(rpcServer::close)
		);

		assertSame(CONNECTION_CLOSED, exception);
	}

	public static final class Request {
	}

	public static final class Response {
	}
}
