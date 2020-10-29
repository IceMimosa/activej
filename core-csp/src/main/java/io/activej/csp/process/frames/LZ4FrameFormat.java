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

package io.activej.csp.process.frames;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.parse.ParseException;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.lz4.LZ4SafeDecompressor;

import static io.activej.common.Checks.checkArgument;

public final class LZ4FrameFormat implements FrameFormat {
	static final byte[] MAGIC = {'L', 'Z', '4', 1};
	static final byte[] LAST_BLOCK_BYTES = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
	static final byte[] MAGIC_AND_LAST_BLOCK_BYTES;
	static final int MAGIC_LENGTH = MAGIC.length;

	static {
		MAGIC_AND_LAST_BLOCK_BYTES = new byte[MAGIC.length + LAST_BLOCK_BYTES.length];
		System.arraycopy(MAGIC, 0, MAGIC_AND_LAST_BLOCK_BYTES, 0, MAGIC.length);
		System.arraycopy(LAST_BLOCK_BYTES, 0, MAGIC_AND_LAST_BLOCK_BYTES, MAGIC.length, LAST_BLOCK_BYTES.length);
	}

	static final int COMPRESSED_LENGTH_MASK = 0x7fffffff;
	static final byte END_OF_BLOCK = 1;

	private final LZ4Factory factory;

	private int compressionLevel;
	private boolean safeDecompressor;

	private LZ4FrameFormat(LZ4Factory factory) {
		this.factory = factory;
	}

	public static LZ4FrameFormat create() {
		return new LZ4FrameFormat(LZ4Factory.fastestInstance());
	}

	public static LZ4FrameFormat create(LZ4Factory factory) {
		return new LZ4FrameFormat(factory);
	}

	public LZ4FrameFormat withHighCompression() {
		this.compressionLevel = -1;
		return this;
	}

	public LZ4FrameFormat withCompressionLevel(int compressionLevel) {
		checkArgument(compressionLevel >= -1);
		this.compressionLevel = compressionLevel;
		return this;
	}

	public LZ4FrameFormat withSafeDecompressor(boolean safeDecompressor) {
		this.safeDecompressor = safeDecompressor;
		return this;
	}

	@Override
	public BlockEncoder createEncoder() {
		LZ4Compressor compressor = compressionLevel == 0 ?
				factory.fastCompressor() :
				compressionLevel == -1 ?
						factory.highCompressor() :
						factory.highCompressor(compressionLevel);
		return new LZ4BlockEncoder(compressor);
	}

	@Override
	public BlockDecoder createDecoder() {
		return safeDecompressor ?
				new LZ4BlockDecoder() {
					final LZ4SafeDecompressor decompressor = factory.safeDecompressor();

					@Override
					protected void decompress(int compressedSize, int originalSize, ByteBuf compressedBuf, ByteBuf buf) {
						decompressor.decompress(compressedBuf.array(), compressedBuf.head(), compressedSize, buf.array(), 0, originalSize);
					}
				} :
				new LZ4BlockDecoder() {
					final LZ4FastDecompressor decompressor = factory.fastDecompressor();

					@Override
					protected void decompress(int compressedSize, int originalSize, ByteBuf compressedBuf, ByteBuf buf) throws ParseException {
						int readBytes = decompressor.decompress(compressedBuf.array(), compressedBuf.head(), buf.array(), 0, originalSize);
						if (readBytes != compressedSize) {
							buf.recycle();
							throw STREAM_IS_CORRUPTED;
						}
					}
				};
	}
}
