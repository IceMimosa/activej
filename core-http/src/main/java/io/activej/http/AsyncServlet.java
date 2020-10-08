/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.http;

import io.activej.promise.Promisable;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static io.activej.promise.Promises.isResult;

/**
 * An interface for a servlet function that asynchronously receives a {@link HttpRequest},
 * processes it and then returns a {@link HttpResponse}.
 */
@FunctionalInterface
public interface AsyncServlet {
	@NotNull
	Promisable<HttpResponse> serve(@NotNull HttpRequest request);

	@NotNull
	default Promise<HttpResponse> serveAsync(@NotNull HttpRequest request) {
		return serve(request).promise();
	}

	default AsyncServlet then(AsyncServletDecorator decorator) {
		return decorator.serve(this);
	}

	/**
	 * Wraps given {@link BlockingServlet} into async one using given {@link Executor}.
	 */
	@NotNull
	static AsyncServlet ofBlocking(@NotNull Executor executor, @NotNull BlockingServlet blockingServlet) {
		return request -> request.loadBody()
				.then(() -> Promise.ofBlockingCallable(executor,
						() -> blockingServlet.serve(request)));
	}

	Promise<HttpResponse> NEXT = Promise.of(null);

	static AsyncServlet firstSuccessful(AsyncServlet... servlets) {
		return httpRequest -> Promises.first(isResult(Objects::nonNull),
				Stream.of(servlets).map(servlet -> () -> servlet.serveAsync(httpRequest)));
	}
}
