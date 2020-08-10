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

package io.activej.csp.file;

import io.activej.async.file.AsyncFileService;
import io.activej.async.file.ExecutorAsyncFileService;
import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.MemSize;
import io.activej.common.exception.AsyncTimeoutException;
import io.activej.common.exception.CloseException;
import io.activej.csp.AbstractChannelSupplier;
import io.activej.eventloop.schedule.ScheduledRunnable;
import io.activej.promise.Promise;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executor;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Utils.nullify;
import static io.activej.eventloop.util.RunnableWithContext.wrapContext;
import static java.nio.file.StandardOpenOption.READ;

/**
 * This supplier allows you to asynchronously read binary data from a file.
 */
public final class ChannelFileReader extends AbstractChannelSupplier<ByteBuf> {
	private static final Logger logger = LoggerFactory.getLogger(ChannelFileReader.class);

	public static final AsyncTimeoutException IDLE_TIMEOUT_EXCEPTION = new AsyncTimeoutException(ChannelFileReader.class,
			"Reader was idle for too long");
	public static final int NO_TIMEOUT = 0;
	public static final MemSize DEFAULT_BUFFER_SIZE = MemSize.kilobytes(8);

	private static final OpenOption[] DEFAULT_OPTIONS = new OpenOption[]{READ};

	private final AsyncFileService fileService;
	private final FileChannel channel;

	private int bufferSize = DEFAULT_BUFFER_SIZE.toInt();
	private long position = 0;
	private long limit = Long.MAX_VALUE;
	private int idleTimeout = NO_TIMEOUT;

	@Nullable
	private ScheduledRunnable scheduledIdleTimeout;

	private ChannelFileReader(AsyncFileService fileService, FileChannel channel) {
		this.fileService = fileService;
		this.channel = channel;
	}

	public static ChannelFileReader create(Executor executor, FileChannel channel) {
		return create(new ExecutorAsyncFileService(executor), channel);
	}

	public static ChannelFileReader create(AsyncFileService fileService, FileChannel channel) {
		return new ChannelFileReader(fileService, channel);
	}

	public static Promise<ChannelFileReader> open(Executor executor, Path path) {
		return open(executor, path, DEFAULT_OPTIONS);
	}

	public static Promise<ChannelFileReader> open(Executor executor, Path path, OpenOption... openOptions) {
		checkArgument(Arrays.asList(openOptions).contains(READ), "'READ' option is not present");
		return Promise.ofBlockingCallable(executor,
				() -> {
					if (Files.isDirectory(path)) throw new FileSystemException(path.toString(), null, "Is a directory");
					return FileChannel.open(path, openOptions);
				})
				.map(channel -> create(executor, channel));
	}

	public static ChannelFileReader openBlocking(Executor executor, Path path) throws IOException {
		return openBlocking(executor, path, DEFAULT_OPTIONS);
	}

	public static ChannelFileReader openBlocking(Executor executor, Path path, OpenOption... openOptions) throws IOException {
		checkArgument(Arrays.asList(openOptions).contains(READ), "'READ' option is not present");
		if (Files.isDirectory(path)) throw new FileSystemException(path.toString(), null, "Is a directory");
		FileChannel channel = FileChannel.open(path, openOptions);
		return create(executor, channel);
	}

	public ChannelFileReader withBufferSize(MemSize bufferSize) {
		return withBufferSize(bufferSize.toInt());
	}

	public ChannelFileReader withBufferSize(int bufferSize) {
		checkArgument(bufferSize > 0, "Buffer size cannot be less than or equal to zero");
		this.bufferSize = bufferSize;
		return this;
	}

	public ChannelFileReader withOffset(long offset) {
		checkArgument(offset >= 0, "Offset cannot be less than zero");
		position = offset;
		return this;
	}

	public ChannelFileReader withLimit(long limit) {
		checkArgument(limit >= 0, "Limit cannot be less than zero");
		this.limit = limit;
		return this;
	}

	public ChannelFileReader withIdleTimeout(Duration idleTimeout) {
		this.idleTimeout = Math.toIntExact(idleTimeout.toMillis());
		if (this.idleTimeout != NO_TIMEOUT) {
			scheduleIdleTimeout();
		}
		return this;
	}

	public long getPosition() {
		return position;
	}

	@Override
	protected Promise<ByteBuf> doGet() {
		if (limit == 0) {
			close();
			return Promise.of(null);
		}
		scheduledIdleTimeout = nullify(scheduledIdleTimeout, ScheduledRunnable::cancel);
		ByteBuf buf = ByteBufPool.allocateExact((int) Math.min(bufferSize, limit));
		return fileService.read(channel, position, buf.array(), buf.head(), buf.writeRemaining()) // reads are synchronized at least on asyncFile, so if produce() is called twice, position wont be broken (i hope)
				.thenEx((bytesRead, e) -> {
					if (e != null) {
						buf.recycle();
						closeEx(e);
						return Promise.ofException(getException());
					}
					if (bytesRead == 0) { // no data read, assuming end of file
						buf.recycle();
						close();
						return Promise.of(null);
					}

					buf.moveTail(Math.toIntExact(bytesRead));
					position += bytesRead;
					if (limit != Long.MAX_VALUE) {
						limit -= bytesRead; // bytesRead is always <= the limit (^ see the min call)
					}
					if (idleTimeout != NO_TIMEOUT) {
						scheduleIdleTimeout();
					}
					return Promise.of(buf);
				});
	}

	@Override
	protected void onClosed(@NotNull Throwable e) {
		try {
			scheduledIdleTimeout = nullify(scheduledIdleTimeout, ScheduledRunnable::cancel);
			if (!channel.isOpen()) {
				throw new CloseException(ChannelFileReader.class, "File has been closed");
			}

			channel.close();
			logger.trace("{}: closed file", this);
		} catch (IOException | CloseException e1) {
			logger.error("{}: failed to close file", this, e1);
		}
	}

	private void scheduleIdleTimeout() {
		assert idleTimeout != NO_TIMEOUT;
		scheduledIdleTimeout = eventloop.delayBackground(idleTimeout, wrapContext(this, () -> {
			scheduledIdleTimeout = null;
			closeEx(IDLE_TIMEOUT_EXCEPTION);
		}));
	}

	@Override
	public String toString() {
		return "ChannelFileReader{" +
				"pos=" + position +
				(limit == Long.MAX_VALUE ? "" : ", limit=" + limit) +
				'}';
	}
}
