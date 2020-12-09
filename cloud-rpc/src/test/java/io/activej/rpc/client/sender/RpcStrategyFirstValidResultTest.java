package io.activej.rpc.client.sender;

import io.activej.async.callback.Callback;
import io.activej.common.exception.ExpectedException;
import io.activej.rpc.client.RpcClientConnectionPool;
import io.activej.rpc.client.sender.RpcStrategyFirstValidResult.ResultValidator;
import io.activej.rpc.client.sender.helper.RpcClientConnectionPoolStub;
import io.activej.rpc.client.sender.helper.RpcSenderStub;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static io.activej.rpc.client.sender.Callbacks.forFuture;
import static io.activej.rpc.client.sender.Callbacks.ignore;
import static io.activej.rpc.client.sender.RpcStrategies.firstValidResult;
import static io.activej.rpc.client.sender.RpcStrategies.servers;
import static io.activej.test.TestUtils.getFreePort;
import static org.junit.Assert.*;

@SuppressWarnings("ConstantConditions")
public class RpcStrategyFirstValidResultTest {

	private static final String HOST = "localhost";

	private static final InetSocketAddress ADDRESS_1 = new InetSocketAddress(HOST, getFreePort());
	private static final InetSocketAddress ADDRESS_2 = new InetSocketAddress(HOST, getFreePort());
	private static final InetSocketAddress ADDRESS_3 = new InetSocketAddress(HOST, getFreePort());

	private static final Exception NO_VALID_RESULT_EXCEPTION = new ExpectedException("No valid result");

	@Test
	public void itShouldSendRequestToAllAvailableSenders() {
		RpcClientConnectionPoolStub pool = new RpcClientConnectionPoolStub();
		RpcSenderStub connection1 = new RpcSenderStub();
		RpcSenderStub connection2 = new RpcSenderStub();
		RpcSenderStub connection3 = new RpcSenderStub();
		RpcStrategy firstValidResult = firstValidResult(servers(ADDRESS_1, ADDRESS_2, ADDRESS_3));
		int callsAmountIterationOne = 10;
		int callsAmountIterationTwo = 25;
		RpcSender senderToAll;

		pool.put(ADDRESS_1, connection1);
		pool.put(ADDRESS_2, connection2);
		pool.put(ADDRESS_3, connection3);
		senderToAll = firstValidResult.createSender(pool);
		for (int i = 0; i < callsAmountIterationOne; i++) {
			senderToAll.sendRequest(new Object(), 50, ignore());
		}
		pool.remove(ADDRESS_1);
		// we should recreate sender after changing in pool
		senderToAll = firstValidResult.createSender(pool);
		for (int i = 0; i < callsAmountIterationTwo; i++) {
			senderToAll.sendRequest(new Object(), 50, ignore());
		}

		assertEquals(callsAmountIterationOne, connection1.getRequests());
		assertEquals(callsAmountIterationOne + callsAmountIterationTwo, connection2.getRequests());
		assertEquals(callsAmountIterationOne + callsAmountIterationTwo, connection3.getRequests());
	}

	@Test
	public void itShouldCallOnResultWithNullIfAllSendersReturnedNullAndValidatorAndExceptionAreNotSpecified() throws ExecutionException, InterruptedException {
		RpcStrategy strategy1 = new RequestSenderOnResultWithNullStrategy();
		RpcStrategy strategy2 = new RequestSenderOnResultWithNullStrategy();
		RpcStrategy strategy3 = new RequestSenderOnResultWithNullStrategy();
		RpcStrategyFirstValidResult firstValidResult = firstValidResult(strategy1, strategy2, strategy3);
		RpcSender sender = firstValidResult.createSender(new RpcClientConnectionPoolStub());
		CompletableFuture<Object> future = new CompletableFuture<>();

		sender.sendRequest(new Object(), 50, forFuture(future));

		// despite there are several sender, sendResult should be called only once after all senders returned null

		assertNull(future.get());
	}

	@Test(expected = ExpectedException.class)
	public void itShouldCallOnExceptionIfAllSendersReturnsNullAndValidatorIsDefaultButExceptionIsSpecified() throws Throwable {
		// default validator should check whether result is not null
		RpcStrategy strategy1 = new RequestSenderOnResultWithNullStrategy();
		RpcStrategy strategy2 = new RequestSenderOnResultWithNullStrategy();
		RpcStrategy strategy3 = new RequestSenderOnResultWithNullStrategy();
		RpcStrategyFirstValidResult firstValidResult = firstValidResult(strategy1, strategy2, strategy3)
				.withNoValidResultException(NO_VALID_RESULT_EXCEPTION);
		RpcSender sender = firstValidResult.createSender(new RpcClientConnectionPoolStub());

		CompletableFuture<Object> future = new CompletableFuture<>();
		sender.sendRequest(new Object(), 50, forFuture(future));

		try {
			future.get();
		} catch (ExecutionException e) {
			throw e.getCause();
		}
	}

	@Test
	public void itShouldUseCustomValidatorIfItIsSpecified() throws ExecutionException, InterruptedException {
		int invalidKey = 1;
		int validKey = 2;
		RpcStrategy strategy1 = new RequestSenderOnResultWithValueStrategy(invalidKey);
		RpcStrategy strategy2 = new RequestSenderOnResultWithValueStrategy(validKey);
		RpcStrategy strategy3 = new RequestSenderOnResultWithValueStrategy(invalidKey);
		RpcStrategyFirstValidResult firstValidResult = firstValidResult(strategy1, strategy2, strategy3)
				.withResultValidator((ResultValidator<Integer>) input -> input == validKey)
				.withNoValidResultException(NO_VALID_RESULT_EXCEPTION);
		RpcSender sender = firstValidResult.createSender(new RpcClientConnectionPoolStub());
		CompletableFuture<Object> future = new CompletableFuture<>();

		sender.sendRequest(new Object(), 50, forFuture(future));

		assertEquals(validKey, future.get());
	}

	@Test(expected = ExecutionException.class)
	public void itShouldCallOnExceptionIfNoSenderReturnsValidResultButExceptionWasSpecified() throws ExecutionException, InterruptedException {
		int invalidKey = 1;
		int validKey = 2;
		RpcStrategy strategy1 = new RequestSenderOnResultWithValueStrategy(invalidKey);
		RpcStrategy strategy2 = new RequestSenderOnResultWithValueStrategy(invalidKey);
		RpcStrategy strategy3 = new RequestSenderOnResultWithValueStrategy(invalidKey);
		RpcStrategyFirstValidResult firstValidResult = firstValidResult(strategy1, strategy2, strategy3)
				.withResultValidator((ResultValidator<Integer>) input -> input == validKey)
				.withNoValidResultException(NO_VALID_RESULT_EXCEPTION);
		RpcSender sender = firstValidResult.createSender(new RpcClientConnectionPoolStub());
		CompletableFuture<Object> future = new CompletableFuture<>();
		sender.sendRequest(new Object(), 50, forFuture(future));
		future.get();
	}

	@Test
	public void itShouldBeCreatedWhenThereIsAtLeastOneActiveSubSender() {
		RpcClientConnectionPoolStub pool = new RpcClientConnectionPoolStub();
		RpcSenderStub connection = new RpcSenderStub();
		// one connection is added
		pool.put(ADDRESS_2, connection);
		RpcStrategy firstValidResult = firstValidResult(servers(ADDRESS_1, ADDRESS_2));
		assertNotNull(firstValidResult.createSender(pool));
	}

	@Test
	public void itShouldNotBeCreatedWhenThereAreNoActiveSubSenders() {
		RpcClientConnectionPoolStub pool = new RpcClientConnectionPoolStub();
		// no connections were added to pool
		RpcStrategy firstValidResult = firstValidResult(servers(ADDRESS_1, ADDRESS_2, ADDRESS_3));
		assertNull(firstValidResult.createSender(pool));
	}

	private static final class SenderOnResultWithNullCaller implements RpcSender {
		@Override
		public <I, O> void sendRequest(I request, int timeout, @NotNull Callback<O> cb) {
			cb.accept(null, null);
		}
	}

	static final class SenderOnResultWithValueCaller implements RpcSender {
		private final Object data;

		public SenderOnResultWithValueCaller(Object data) {
			this.data = data;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <I, O> void sendRequest(I request, int timeout, @NotNull Callback<O> cb) {
			cb.accept((O) data, null);
		}
	}

	static final class RequestSenderOnResultWithNullStrategy implements RpcStrategy {
		@Override
		public Set<InetSocketAddress> getAddresses() {
			throw new UnsupportedOperationException();
		}

		@Override
		public RpcSender createSender(RpcClientConnectionPool pool) {
			return new SenderOnResultWithNullCaller();
		}
	}

	static final class RequestSenderOnResultWithValueStrategy implements RpcStrategy {
		private final Object data;

		public RequestSenderOnResultWithValueStrategy(Object data) {
			this.data = data;
		}

		@Override
		public Set<InetSocketAddress> getAddresses() {
			throw new UnsupportedOperationException();
		}

		@Override
		public RpcSender createSender(RpcClientConnectionPool pool) {
			return new SenderOnResultWithValueCaller(data);
		}
	}
}
