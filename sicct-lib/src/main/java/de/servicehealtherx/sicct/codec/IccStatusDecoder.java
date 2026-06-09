package de.servicehealtherx.sicct.codec;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

public class IccStatusDecoder {
    public enum IccStatusValue {
        CC_ABSENT,
        CC_PRESENT,
        CC_SWALLOWED,
        CC_POWERED,
        CC_NEGOTIABLE,
        CC_SPECIFIC,
        CC_UNKNOWN
    }

    public static List<IccStatusValue> decode(byte[] bytes) {
        return IntStream.range(0, bytes.length)
                .map(i -> bytes[i]) // get byte value at index i
                .mapToObj(IccStatusDecoder::decodeStatusValue)
                .toList();
    }

    private static IccStatusValue decodeStatusValue(int val) {
        // Mask to b8..b1 relevant bits per spec (ignore b6, b7 = RFU)
        int masked = val & 0x9F; // 1001 1111 — strips b6+b7

        return switch (masked) {
            case 0x00 -> IccStatusValue.CC_ABSENT;
            case 0x01 -> IccStatusValue.CC_PRESENT;
            case 0x03 -> IccStatusValue.CC_SWALLOWED;
            case 0x05 -> IccStatusValue.CC_POWERED;
            case 0x0D -> IccStatusValue.CC_NEGOTIABLE;
            case 0x15 -> IccStatusValue.CC_SPECIFIC;
            case 0x80 -> IccStatusValue.CC_UNKNOWN;
            default -> IccStatusValue.CC_UNKNOWN;
        };
    }
}
