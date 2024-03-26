/*
 * Copyright (c) 2017-2018 The Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package de.hub.se.jqf.bedivfuzz.examples.kaitai;

import de.hub.se.jqf.bedivfuzz.junit.quickcheck.SplitRandom;
import org.junit.AssumptionViolatedException;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * adapted from {@link edu.berkeley.cs.jqf.examples.kaitai.KaitaiStream}
 */
public class SplitKaitaiStream {

    final ByteBuffer buf;
    final SplitRandom random;

    public SplitKaitaiStream(ByteBuffer buf, SplitRandom random) {
        this.buf = buf;
        this.random = random;
    }

    public byte[] ensureFixedContents(byte[] bytes) {
        buf.put(bytes);
        return bytes;
    }

    private void checkLen(long len) {
        if (len > Integer.MAX_VALUE-2 || len < 0) {
            throw new AssumptionViolatedException("Incorrect length: " + len);
        }
        if (len > buf.remaining()) {
            throw new BufferOverflowException();
        }
    }

    public byte[] writeBytes(long len) {
        checkLen(len);
        byte[] bytes = random.nextValueBytes((int) len);
        buf.put(bytes);
        return bytes;
    }

    public boolean isEof() {
        return buf.position() == buf.capacity();
    }

    public byte writeU1value(byte val) {
        buf.put(val);
        return val;

    }

    public byte writeU1() {
        byte val = random.nextValueByte((byte) 0, Byte.MAX_VALUE);
        return writeU1value(val);
    }

    public byte writeU1OneOfValue(long... choices) {
        int idx = random.nextValueInt(0, choices.length);
        byte choice = (byte) choices[idx]; // TODO: Verify range of each choice
        return writeU1value(choice);
    }

    public byte writeU1OneOfStructure(long... choices) {
        int idx = random.nextStructureInt(0, choices.length);
        byte choice = (byte) choices[idx]; // TODO: Verify range of each choice
        return writeU1value(choice);
    }

    public int writeIntvalue(int val) {
        buf.putInt(val);
        return val;
    }

    private int writeU4Value(int min, int max) {
        /* To be called only after endinaness is configured */
        int val = random.nextValueInt(min, max);
        return writeIntvalue(val);
    }

    private int writeU4Structure(int min, int max) {
        /* To be called only after endinaness is configured */
        int val = random.nextStructureInt(min, max);
        return writeIntvalue(val);
    }

    public int writeU4beValue() {
        buf.order(ByteOrder.BIG_ENDIAN);
        return writeU4Value(0, Integer.MAX_VALUE);
    }

    public int writeU4beStructure() {
        buf.order(ByteOrder.BIG_ENDIAN);
        return writeU4Structure(0, Integer.MAX_VALUE);
    }

    public int writeU4leValue() {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return writeU4Value(0, Integer.MAX_VALUE);
    }

    public int writeU4leStructure() {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return writeU4Structure(0, Integer.MAX_VALUE);
    }

    public int writeU4beValue(int min, int max) {
        buf.order(ByteOrder.BIG_ENDIAN);
        return writeU4Value(min, max);
    }

    public int writeU4beStructure(int min, int max) {
        buf.order(ByteOrder.BIG_ENDIAN);
        return writeU4Structure(min, max);
    }

    public int writeU4leValue(int min, int max) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return writeU4Value(min, max);
    }

    public int writeU4leStructure(int min, int max) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return writeU4Structure(min, max);
    }

    public int writeU4beOneOf(long... choices) {
        int idx = random.nextValueInt(0, choices.length);
        int choice = (int) choices[idx]; // TODO: Verify range of each choice
        buf.order(ByteOrder.BIG_ENDIAN);
        return writeIntvalue(choice);
    }

    public int writeU4leOneOf(long... choices) {
        int idx = random.nextValueInt(0, choices.length);
        int choice = (int) choices[idx]; // TODO: Verify range of each choice
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return writeIntvalue(choice);
    }

    private short writeU2() {
        /* To be called only after endinaness is configured */
        short val = random.nextValueShort((short) 0, Short.MAX_VALUE);
        buf.putShort(val);
        return val;
    }

    public short writeU2be() {
        buf.order(ByteOrder.BIG_ENDIAN);
        return writeU2();
    }

    public short writeU2le() {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return writeU2();
    }

    public ByteBuffer getSlice(long len) {
        checkLen(len);
        ByteBuffer slice = buf.slice();
        slice.limit((int) len);
        buf.position(buf.position() + (int) len);
        return slice;
    }

    public ByteBuffer getSliceFull() {
        return getSlice(buf.remaining());
    }

    public byte[] writeBytesTerm(int term, boolean includeTerm, boolean consumeTerm, boolean eosError) {
        if (consumeTerm == false) {
            throw new UnsupportedOperationException("consumeTerm MUST be true");
        }
        if (eosError == false) {
            throw new UnsupportedOperationException("eosError MUST be true");
        }
        int size = buf.remaining() > 0 ? random.nextStructureInt(buf.remaining()) : 0; // Do not generate more than we can write
        byte[] bytes = new byte[size+1];
        int i = 0;
        boolean terminated = false;
        while (i < size) {
            int c = random.nextValueInt(0, 255);
            if (c == term) {
                // Always write terminator
                bytes[i++] = (byte) c;
                terminated = true;
                break;
            }
            bytes[i++] = (byte) c;
        }
        if (!terminated) {
            bytes[i++] = (byte) term;
        }
        byte[] actualBytes = new byte[i];
        System.arraycopy(bytes, 0, actualBytes, 0, i);
        buf.put(actualBytes);
        return actualBytes;
    }

    public byte[] writeBytesFull() {
        return writeBytes(buf.remaining());
    }

    public byte[] processZlib() {
        // TODO: generate zlib data of fixed size ???
        return buf.array();
    }

    public byte[] writeStringOneOfFixedSize(long len, String... choices) {
        checkLen(len);
        for (String choice : choices) {
            if (choice.length() != len) {
                throw new IllegalArgumentException("Choices must be of fixed size");
            }
        }
        int idx = random.nextValueInt(0, choices.length);
        String choice = choices[idx];
        buf.put(choice.getBytes());
        return choice.getBytes();
    }

    public byte[] writeStructureBytesOneOfFixedSize(long len, byte[]... choices) {
        checkLen(len);
        for (byte[] choice : choices) {
            if (choice.length != len) {
                throw new IllegalArgumentException("Choices must be of fixed size");
            }
        }
        int idx = random.nextStructureInt(0, choices.length);
        byte[] choice = choices[idx];
        buf.put(choice);
        return choice;
    }

}
