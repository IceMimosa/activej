package io.activej.redis;

import io.activej.promise.Promise;
import org.junit.Assert;

import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertTrue;

public final class TestUtils {
	public static void assertOk(String result) {
		Assert.assertEquals("OK", result);
	}

	public static <T> T await(RedisClient client, Function<RedisConnection, Promise<T>> clientCommand) {
		return io.activej.promise.TestUtils.await(client.getConnection()
				.then(connection -> clientCommand.apply(connection)
						.whenComplete(connection::quit)));
	}

	public static void assertEquals(List<Object> expected, List<Object> actual) {
		assertTrue(Utils.deepEquals(expected, actual));
	}
}
