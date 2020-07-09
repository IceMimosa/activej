package io.activej.remotefs.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufQueue;
import io.activej.common.exception.ExpectedException;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelSupplier;
import io.activej.csp.ChannelSuppliers;
import io.activej.http.AsyncServlet;
import io.activej.http.StubHttpClient;
import io.activej.remotefs.FileMetadata;
import io.activej.remotefs.FsClient;
import io.activej.remotefs.LocalFsClient;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.activej.bytebuf.ByteBufStrings.wrapUtf8;
import static io.activej.common.collection.CollectionUtils.first;
import static io.activej.common.collection.CollectionUtils.set;
import static io.activej.csp.binary.BinaryChannelSupplier.UNEXPECTED_DATA_EXCEPTION;
import static io.activej.csp.binary.BinaryChannelSupplier.UNEXPECTED_END_OF_STREAM_EXCEPTION;
import static io.activej.eventloop.Eventloop.getCurrentEventloop;
import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static io.activej.remotefs.FsClient.BAD_PATH;
import static io.activej.remotefs.FsClient.FILE_NOT_FOUND;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.junit.Assert.*;

public final class RemoteFsServletAndClientTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final TemporaryFolder tmpFolder = new TemporaryFolder();

	private static final Set<String> initialFiles = set(
			"file",
			"file2",
			"directory/subdir/file3.txt",
			"directory/file.txt",
			"directory2/file2.txt"
	);

	private Path storage;

	private FsClient client;

	@Before
	public void setUp() throws Exception {
		storage = tmpFolder.newFolder("storage").toPath();

		AsyncServlet servlet = RemoteFsServlet.create(LocalFsClient.create(getCurrentEventloop(), newSingleThreadExecutor(), storage));
		client = HttpFsClient.create("http://localhost", StubHttpClient.of(servlet));

		initializeDirs();
	}

	@Test
	public void list() {
		Map<String, FileMetadata> metadata = await(client.list("**"));
		assertEquals(initialFiles, metadata.keySet());
	}

	@Test
	public void upload() throws IOException {
		String content = "Test data";
		String fileName = "newDir/newFile";
		await(ChannelSupplier.of(wrapUtf8(content)).streamTo(client.upload(fileName)));
		List<String> strings = Files.readAllLines(storage.resolve(fileName));

		assertEquals(1, strings.size());
		assertEquals(content, strings.get(0));
	}

	@Test
	public void uploadIncompleteFile() {
		String filename = "incomplete.txt";
		Path path = storage.resolve(filename);
		assertFalse(Files.exists(path));

		ExpectedException expectedException = new ExpectedException();
		ChannelConsumer<ByteBuf> consumer = await(client.upload(filename));

		Throwable exception = awaitException(ChannelSuppliers.concat(
				ChannelSupplier.of(wrapUtf8("some"), wrapUtf8("test"), wrapUtf8("data")),
				ChannelSupplier.ofException(expectedException))
				.streamTo(consumer));

		assertSame(expectedException, exception);

		assertFalse(Files.exists(path));
	}

	@Test
	public void uploadLessThanSpecified() {
		String filename = "incomplete.txt";
		Path path = storage.resolve(filename);
		assertFalse(Files.exists(path));

		ChannelConsumer<ByteBuf> consumer = await(client.upload(filename, 10));

		Throwable exception = awaitException(ChannelSupplier.of(wrapUtf8("data")).streamTo(consumer));

		assertEquals(UNEXPECTED_END_OF_STREAM_EXCEPTION, exception);

		assertFalse(Files.exists(path));
	}

	@Test
	public void uploadMoreThanSpecified() {
		String filename = "incomplete.txt";
		Path path = storage.resolve(filename);
		assertFalse(Files.exists(path));

		ChannelConsumer<ByteBuf> consumer = await(client.upload(filename, 10));

		Throwable exception = awaitException(ChannelSupplier.of(wrapUtf8("data data data data")).streamTo(consumer));

		assertEquals(UNEXPECTED_DATA_EXCEPTION, exception);

		assertFalse(Files.exists(path));
	}

	@Test
	public void uploadIllegalPath() {
		Throwable exception = awaitException(ChannelSupplier.of(wrapUtf8("test")).streamTo(client.upload("../outside")));
		assertSame(BAD_PATH, exception);
	}

	@Test
	public void download() throws IOException {
		String fileName = "directory/subdir/file3.txt";
		ChannelSupplier<ByteBuf> supplier = await(client.download(fileName));
		ByteBuf result = await(supplier.toCollector(ByteBufQueue.collector()));
		byte[] expected = Files.readAllBytes(storage.resolve(fileName));

		assertArrayEquals(expected, result.asArray());
	}

	@Test
	public void downloadNonExistent() {
		Throwable exception = awaitException(client.download("nonExistent"));
		assertSame(FILE_NOT_FOUND, exception);
	}

	@Test
	public void testFilesWithNames() {
		// whitespaces
		doTestFileWithName("my file");
		doTestFileWithName("my     file");
		doTestFileWithName("my file.txt");
		doTestFileWithName("a/b/my file.txt");
		doTestFileWithName("a/b/my     file.txt");
		doTestFileWithName("a/b c/file.txt");
		doTestFileWithName("a/b     c/file.txt");
		doTestFileWithName("a/b c/d e f/my file.txt");
		doTestFileWithName(" my file ");
		doTestFileWithName(" a / b   c /   d e f / my   file   .   txt");

		// backslashes
		doTestFileWithName("back\\slash");
		doTestFileWithName("back\\\\\\\\\\slash");
		doTestFileWithName("back\\slash.txt");
		doTestFileWithName("a/b/back\\slash.txt");
		doTestFileWithName("a\\b/c/file.txt");
		doTestFileWithName("a\\\\\\\\\\b/c/file.txt");
		doTestFileWithName("a/b\\c/d\\e\\f/back\\slash.txt");
		doTestFileWithName("\\back\\slash\\");
		doTestFileWithName("\\a\\/\\b\\c\\/\\d\\e\\f\\/\\back\\slash\\.\\txt");
	}

	private void doTestFileWithName(String filename) {
		String data = "test data";
		await(ChannelSupplier.of(wrapUtf8(data)).streamTo(client.upload(filename)));

		FileMetadata metadata = await(client.info(filename));
		assertNotNull(metadata);

		Map<String, FileMetadata> map = await(client.list(filename));
		assertEquals(1, map.size());
		assertEquals(filename, first(map.keySet()));

		ChannelSupplier<ByteBuf> supplier = await(client.download(filename));
		ByteBuf buf = await(supplier.toCollector(ByteBufQueue.collector()));
		assertEquals(data, buf.asString(UTF_8));
	}

	private void clearDirectory(Path dir) throws IOException {
		for (Iterator<Path> iterator = Files.list(dir).iterator(); iterator.hasNext(); ) {
			Path file = iterator.next();
			if (Files.isDirectory(file))
				clearDirectory(file);
			Files.delete(file);
		}
	}

	private void initializeDirs() {
		try {
			clearDirectory(storage);

			for (String path : initialFiles) {
				Path file = this.storage.resolve(path);
				Files.createDirectories(file.getParent());
				Files.write(file, String.format("This is contents of file %s", file).getBytes(UTF_8), CREATE, TRUNCATE_EXISTING);
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
