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

package io.activej.fs;

import io.activej.common.ApplicationSettings;
import io.activej.common.CollectorsEx;
import io.activej.common.collection.CollectionUtils;
import io.activej.common.time.CurrentTimeProvider;
import io.activej.fs.exception.scalar.ForbiddenPathException;
import io.activej.fs.util.ForwardingOutputStream;
import io.activej.fs.util.LimitedInputStream;
import io.activej.fs.util.UploadOutputStream;
import io.activej.jmx.api.ConcurrentJmxBean;
import io.activej.service.BlockingService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Utils.sneakyThrow;
import static io.activej.common.collection.CollectionUtils.isBijection;
import static io.activej.fs.LocalFileUtils.*;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.*;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

public final class LocalBlockingFs implements BlockingFs, BlockingService, ConcurrentJmxBean {
	private static final Logger logger = LoggerFactory.getLogger(LocalBlockingFs.class);

	public static final String DEFAULT_TEMP_DIR = ".upload";
	public static final boolean DEFAULT_SYNCED = ApplicationSettings.getBoolean(LocalActiveFs.class, "synced", false);
	public static final boolean DEFAULT_SYNCED_APPEND = ApplicationSettings.getBoolean(LocalActiveFs.class, "syncedAppend", false);

	private static final char SEPARATOR_CHAR = SEPARATOR.charAt(0);
	private static final Function<String, String> toLocalName = File.separatorChar == SEPARATOR_CHAR ?
			Function.identity() :
			s -> s.replace(SEPARATOR_CHAR, File.separatorChar);

	private static final Function<String, String> toRemoteName = File.separatorChar == SEPARATOR_CHAR ?
			Function.identity() :
			s -> s.replace(File.separatorChar, SEPARATOR_CHAR);

	private final Path storage;

	private final Set<OpenOption> appendOptions = CollectionUtils.set(WRITE);
	private final Set<OpenOption> appendNewOptions = CollectionUtils.set(WRITE, CREATE);

	private boolean hardlinkOnCopy = false;
	private Path tempDir;
	private boolean synced = DEFAULT_SYNCED;

	CurrentTimeProvider now = CurrentTimeProvider.ofSystem();

	// region creators
	private LocalBlockingFs(Path storage) {
		this.storage = storage;
		this.tempDir = storage.resolve(DEFAULT_TEMP_DIR);

		if (DEFAULT_SYNCED_APPEND) {
			appendOptions.add(SYNC);
			appendNewOptions.add(SYNC);
		}
	}

	public static LocalBlockingFs create(Path storageDir) {
		return new LocalBlockingFs(storageDir);
	}

	/**
	 * If set to {@code true}, an attempt to create a hard link will be made when copying files
	 */
	@SuppressWarnings("UnusedReturnValue")
	public LocalBlockingFs withHardLinkOnCopy(boolean hardLinkOnCopy) {
		this.hardlinkOnCopy = hardLinkOnCopy;
		return this;
	}

	/**
	 * Sets a temporary directory for files to be stored while uploading.
	 */
	public LocalBlockingFs withTempDir(Path tempDir) {
		this.tempDir = tempDir;
		return this;
	}

	/**
	 * If set to {@code true}, all newly created files (via move, copy, upload) will be synchronously persisted to the storage device.
	 * <p>
	 * <b>Note: may be slow when there are a lot of new files created</b>
	 */
	public LocalBlockingFs withSynced(boolean synced) {
		this.synced = synced;
		return this;
	}

	/**
	 * If set to {@code true}, each write to {@link #append)} consumer will be synchronously written to the storage device.
	 * <p>
	 * <b>Note: significantly slows down appends</b>
	 */
	public LocalBlockingFs withSyncedAppend(boolean syncedAppend) {
		if (syncedAppend) {
			appendOptions.add(SYNC);
			appendNewOptions.add(SYNC);
		} else {
			appendOptions.remove(SYNC);
			appendNewOptions.remove(SYNC);
		}
		return this;
	}
	// endregion

	@Override
	public OutputStream upload(@NotNull String name) throws IOException {
		Path tempPath = Files.createTempFile(tempDir, "", "");
		return new UploadOutputStream(tempPath, resolve(name), synced, this::doMove);
	}

	@Override
	public OutputStream upload(@NotNull String name, long size) throws IOException {
		Path tempPath = Files.createTempFile(tempDir, "", "");
		return new UploadOutputStream(tempPath, resolve(name), synced, this::doMove) {
			long totalSize;

			@Override
			protected void onBytes(int len) throws IOException {
				if ((totalSize += len) > size) throw new IOException("Size mismatch");
			}

			@Override
			protected void onClose() throws IOException {
				if (totalSize != size) throw new IOException("Size mismatch");
			}
		};
	}

	@Override
	public OutputStream append(@NotNull String name, long offset) throws IOException {
		checkArgument(offset >= 0, "Offset cannot be less than 0");

		Path path = resolve(name);
		FileChannel channel;
		if (offset == 0) {
			channel = ensureTarget(null, path, () -> FileChannel.open(path, appendNewOptions));
			if (synced) {
				fsync(path.getParent());
			}
		} else {
			channel = FileChannel.open(path, appendOptions);
		}
		if (channel.size() < offset) {
			throw new IOException("Offset exceeds file size");
		}
		channel.position(offset);
		return new ForwardingOutputStream(Channels.newOutputStream(channel)) {
			boolean closed;

			@Override
			public void close() throws IOException {
				if (closed) return;
				closed = true;
				peer.close();
				if (synced && !appendOptions.contains(SYNC)) {
					fsync(path);
				}
			}
		};
	}

	@Override
	public InputStream download(@NotNull String name, long offset, long limit) throws IOException {
		Path path = resolve(name);
		if (!Files.exists(path)) {
			throw new FileNotFoundException(name);
		}
		if (offset > Files.size(path)) {
			throw new IOException("Offset exceeds file size");
		}
		FileInputStream fileInputStream = new FileInputStream(path.toFile());

		//noinspection ResultOfMethodCallIgnored
		fileInputStream.skip(offset);
		return new LimitedInputStream(fileInputStream, limit);
	}

	@Override
	public void delete(@NotNull String name) throws IOException {
		Path path = resolve(name);
		// cannot delete storage
		if (path.equals(storage)) return;

		Files.deleteIfExists(path);
	}

	@Override
	public void copy(@NotNull String name, @NotNull String target) throws IOException {
		copyImpl(singletonMap(name, target));
	}

	@Override
	public void copyAll(Map<String, String> sourceToTarget) throws IOException {
		checkArgument(isBijection(sourceToTarget), "Targets must be unique");
		copyImpl(sourceToTarget);
	}

	@Override
	public void move(@NotNull String name, @NotNull String target) throws IOException {
		moveImpl(singletonMap(name, target));
	}

	@Override
	public void moveAll(Map<String, String> sourceToTarget) throws IOException {
		checkArgument(isBijection(sourceToTarget), "Targets must be unique");
		moveImpl(sourceToTarget);
	}

	@Override
	public Map<String, FileMetadata> list(@NotNull String glob) throws IOException {
		if (glob.isEmpty()) return emptyMap();

		String subdir = extractSubDir(glob);
		Path subdirectory = resolve(subdir);
		String subglob = glob.substring(subdir.length());

		return findMatching(tempDir, subglob, subdirectory).stream()
				.collect(Collector.of(
						(Supplier<Map<String, FileMetadata>>) HashMap::new,
						(map, path) -> {
							FileMetadata metadata = toFileMetadata(path);
							if (metadata != null) {
								String filename = toRemoteName.apply(storage.relativize(path).toString());
								map.put(filename, metadata);
							}
						},
						CollectorsEx.throwingMerger())
				);
	}

	@Override
	@Nullable
	public FileMetadata info(@NotNull String name) throws IOException {
		return toFileMetadata(resolve(name));
	}

	@Override
	public void ping() {
		// local fs is always available
	}

	@Override
	public void start() throws IOException {
		LocalFileUtils.init(storage, tempDir, synced);
	}

	@Override
	public void stop() {
	}

	@Override
	public String toString() {
		return "LocalBlockingFs{storage=" + storage + '}';
	}

	private static FileMetadata toFileMetadata(Path path) {
		try {
			return LocalFileUtils.toFileMetadata(path);
		} catch (IOException e) {
			logger.warn("Failed to retrieve metadata for {}", path, e);
			return sneakyThrow(e);
		}
	}

	private Path resolve(String name) throws IOException {
		try {
			return LocalFileUtils.resolve(storage, tempDir, toLocalName.apply(name));
		} catch (ForbiddenPathException e) {
			throw new FileSystemException(name, null, e.getMessage());
		}
	}

	private void moveImpl(Map<String, String> sourceToTargetMap) throws IOException {
		Set<Path> toFSync = new HashSet<>();
		try {
			for (Map.Entry<String, String> entry : sourceToTargetMap.entrySet()) {
				Path path = resolve(entry.getKey());
				if (!Files.isRegularFile(path)) {
					throw new FileNotFoundException("File '" + entry.getKey() + "' not found");
				}
				Path targetPath = resolve(entry.getValue());
				if (path.equals(targetPath)) {
					touch(path, now);
					if (synced) {
						toFSync.add(path);
					}
					continue;
				}
				doMove(path, targetPath);
				if (synced) {
					toFSync.add(targetPath.getParent());
				}
			}
		} finally {
			for (Path path : toFSync) {
				fsync(path);
			}
		}
	}

	private void copyImpl(Map<String, String> sourceToTargetMap) throws IOException {
		Set<Path> toFSync = new HashSet<>();
		try {
			for (Map.Entry<String, String> entry : sourceToTargetMap.entrySet()) {
				Path path = resolve(entry.getKey());
				if (!Files.isRegularFile(path)) {
					throw new FileNotFoundException("File '" + entry.getKey() + "' not found");
				}
				Path targetPath = resolve(entry.getValue());

				if (path.equals(targetPath)) {
					touch(path, now);
					if (synced) {
						toFSync.add(path);
					}
					continue;
				}

				ensureTarget(path, targetPath, () -> {
					if (hardlinkOnCopy) {
						LocalFileUtils.copyViaHardlink(path, targetPath, now);
					} else {
						Files.copy(path, targetPath, REPLACE_EXISTING);
					}
					return null;
				});
				if (synced) {
					toFSync.add(targetPath.getParent());
				}
			}
		} finally {
			for (Path path : toFSync) {
				fsync(path);
			}
		}
	}

	private void doMove(Path path, Path targetPath) throws IOException {
		ensureTarget(path, targetPath, () -> {
			LocalFileUtils.moveViaHardlink(path, targetPath, now);
			return null;
		});
	}
}
