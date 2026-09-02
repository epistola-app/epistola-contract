// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

/**
 * MurmurHash3 x86 32-bit.
 *
 * <p>Must match the server's partition assignment exactly (Guava's
 * {@code Hashing.murmur3_32_fixed(seed)}), otherwise {@link PartitionRouting} would hand out
 * routing keys that land on another node's partition. {@code Murmur3Test} pins it against known
 * vectors.
 *
 * <p>Public because it is the hash the protocol is defined in terms of; a consumer computing
 * routing keys ahead of time needs the same function.
 */
public final class Murmur3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    public static int hash32(byte[] data, int seed) {
        int h1 = seed;
        int length = data.length;
        int blocks = length / 4;

        for (int i = 0; i < blocks; i++) {
            int index = i * 4;
            int k1 = (data[index] & 0xFF)
                    | ((data[index + 1] & 0xFF) << 8)
                    | ((data[index + 2] & 0xFF) << 16)
                    | ((data[index + 3] & 0xFF) << 24);

            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int tail = blocks * 4;
        int k1 = 0;
        switch (length & 3) {
            case 3:
                k1 ^= (data[tail + 2] & 0xFF) << 16;
                // fall through
            case 2:
                k1 ^= (data[tail + 1] & 0xFF) << 8;
                // fall through
            case 1:
                k1 ^= data[tail] & 0xFF;
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
                break;
            default:
                break;
        }

        h1 ^= length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    private Murmur3() {
    }
}
