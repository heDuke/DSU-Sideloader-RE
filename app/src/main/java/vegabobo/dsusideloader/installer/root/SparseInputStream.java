/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vegabobo.dsusideloader.installer.root;

import static java.lang.Math.min;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/**
 * SparseInputStream read from upstream and detects the data format. If the upstream is a valid
 * sparse data, it will unsparse it on the fly. Otherwise, it just passthrough as is.
 */
public class SparseInputStream extends InputStream {
    static final int FILE_HDR_SIZE = 28;
    static final int CHUNK_HDR_SIZE = 12;

    /**
     * This class represents a chunk in the Android sparse image.
     *
     * \@see system/core/libsparse/sparse_format.h
     */
    private class SparseChunk {
        static final short RAW = (short) 0xCAC1;
        static final short FILL = (short) 0xCAC2;
        static final short DONTCARE = (short) 0xCAC3;
        public short mChunkType;
        public int mChunkSize;
        public int mTotalSize;
        public byte[] fill;
        public String toString() {
            return String.format(Locale.getDefault(),
                    "type: %x, chunk_size: %d, total_size: %d", mChunkType, mChunkSize, mTotalSize);
        }
    }

    private byte[] readFull(InputStream in, int size) throws IOException {
        byte[] buf = new byte[size];
        for (int done = 0, n = 0; done < size; done += n) {
            if ((n = in.read(buf, done, size - done)) < 0) {
                throw new IOException("Failed to readFull");
            }
        }
        return buf;
    }

    private ByteBuffer readBuffer(InputStream in, int size) throws IOException {
        return ByteBuffer.wrap(readFull(in, size)).order(ByteOrder.LITTLE_ENDIAN);
    }

    private SparseChunk readChunk(InputStream in) throws IOException {
        SparseChunk chunk = new SparseChunk();
        ByteBuffer buf = readBuffer(in, CHUNK_HDR_SIZE);
        chunk.mChunkType = buf.getShort();
        buf.getShort();
        chunk.mChunkSize = buf.getInt();
        chunk.mTotalSize = buf.getInt();
        return chunk;
    }

    private BufferedInputStream mIn;
    private boolean mIsSparse;
    private long mBlockSize;
    private long mTotalBlocks;
    private long mTotalChunks;
    private SparseChunk mCur;
    private long mLeft;
    private long mCurChunks;
    // Blocks declared by the chunks read so far; must end up equal to mTotalBlocks.
    private long mBlocksSeen;

    /*
     * Local hardening (not in AOSP): header fields are read as unsigned, every chunk's
     * total_size must match its type and chunk_size, the chunks must cover exactly
     * total_blks blocks, and a RAW chunk ending early is reported as truncation instead of
     * a silent EOF. Any inconsistency throws IOException, so a corrupt or truncated sparse
     * image can never be written as if it were complete.
     */

    public SparseInputStream(BufferedInputStream in) throws IOException {
        mIn = in;
        in.mark(FILE_HDR_SIZE * 2);
        ByteBuffer buf = readBuffer(mIn, FILE_HDR_SIZE);
        mIsSparse = (buf.getInt() == 0xed26ff3a);
        if (!mIsSparse) {
            mIn.reset();
            return;
        }
        int major = buf.getShort();
        int minor = buf.getShort();

        if (major > 0x1 || minor > 0x0) {
            throw new IOException("Unsupported sparse version: " + major + "." + minor);
        }

        if (buf.getShort() != FILE_HDR_SIZE) {
            throw new IOException("Illegal file header size");
        }
        if (buf.getShort() != CHUNK_HDR_SIZE) {
            throw new IOException("Illegal chunk header size");
        }
        mBlockSize = Integer.toUnsignedLong(buf.getInt());
        if (mBlockSize == 0 || (mBlockSize & 0x3) != 0) {
            throw new IOException("Illegal block size, must be a non-zero multiple of 4");
        }
        mTotalBlocks = Integer.toUnsignedLong(buf.getInt());
        mTotalChunks = Integer.toUnsignedLong(buf.getInt());
        mLeft = mCurChunks = mBlocksSeen = 0;
    }

    /**
     * Opens chunks until one with data remains (zero-length chunks are skipped).
     *
     * @return true if it's EOF (all declared chunks consumed and fully validated)
     */
    private boolean prepareChunk() throws IOException {
        while (mCur == null || mLeft <= 0) {
            if (mCurChunks >= mTotalChunks) {
                if (mBlocksSeen != mTotalBlocks) {
                    throw new IOException("Sparse chunks cover " + mBlocksSeen
                            + " blocks but header declares " + mTotalBlocks);
                }
                return true;
            }
            mCurChunks++;
            mCur = readChunk(mIn);
            long chunkBlocks = Integer.toUnsignedLong(mCur.mChunkSize);
            long totalSize = Integer.toUnsignedLong(mCur.mTotalSize);
            long expected;
            switch (mCur.mChunkType) {
                case SparseChunk.RAW:
                    expected = CHUNK_HDR_SIZE + chunkBlocks * mBlockSize;
                    break;
                case SparseChunk.FILL:
                    expected = CHUNK_HDR_SIZE + 4;
                    break;
                case SparseChunk.DONTCARE:
                    expected = CHUNK_HDR_SIZE;
                    break;
                default:
                    throw new IOException("Unsupported Chunk:" + mCur.toString());
            }
            if (totalSize != expected) {
                throw new IOException("Inconsistent sparse chunk (" + mCur
                        + "), expected total_size " + expected);
            }
            mBlocksSeen += chunkBlocks;
            if (mBlocksSeen > mTotalBlocks) {
                throw new IOException("Sparse chunks exceed declared " + mTotalBlocks + " blocks");
            }
            if (mCur.mChunkType == SparseChunk.FILL) {
                mCur.fill = readFull(mIn, 4);
            }
            mLeft = chunkBlocks * mBlockSize;
        }
        return false;
    }

    private byte fillByte() {
        return mCur.fill[(4 - ((int) mLeft & 0x3)) & 0x3];
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (!mIsSparse) {
            return mIn.read(buf, off, len);
        }
        if (len == 0) return 0;
        if (prepareChunk()) return -1;
        int n;
        switch (mCur.mChunkType) {
            case SparseChunk.RAW:
                n = mIn.read(buf, off, (int) min(mLeft, len));
                if (n < 0) {
                    throw new IOException("Truncated sparse image: RAW chunk ended "
                            + mLeft + " bytes early");
                }
                mLeft -= n;
                return n;
            case SparseChunk.DONTCARE:
                n = (int) min(mLeft, len);
                Arrays.fill(buf, off, off + n, (byte) 0);
                mLeft -= n;
                return n;
            case SparseChunk.FILL:
                n = (int) min(mLeft, len);
                for (int i = 0; i < n; i++) {
                    buf[off + i] = fillByte();
                    mLeft--;
                }
                return n;
            default:
                throw new IOException("Unsupported Chunk:" + mCur.toString());
        }
    }

    @Override
    public int read() throws IOException {
        if (!mIsSparse) {
            return mIn.read();
        }
        if (prepareChunk()) return -1;
        int ret;
        switch (mCur.mChunkType) {
            case SparseChunk.RAW:
                ret = mIn.read();
                if (ret < 0) {
                    throw new IOException("Truncated sparse image: RAW chunk ended "
                            + mLeft + " bytes early");
                }
                break;
            case SparseChunk.DONTCARE:
                ret = 0;
                break;
            case SparseChunk.FILL:
                ret = Byte.toUnsignedInt(fillByte());
                break;
            default:
                throw new IOException("Unsupported Chunk:" + mCur.toString());
        }
        mLeft--;
        return ret;
    }

    /**
     * Get the unsparse size
     * @return -1 if unknown
     */
    public long getUnsparseSize() {
        if (!mIsSparse) {
            return -1;
        }
        return mBlockSize * mTotalBlocks;
    }
}
