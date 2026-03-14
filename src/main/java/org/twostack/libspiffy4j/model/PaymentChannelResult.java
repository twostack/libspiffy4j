package org.twostack.libspiffy4j.model;

public record PaymentChannelResult(
        boolean success,
        PaymentChannel channel,
        String transactionHex,
        String beefHex,
        String error
) {

    public static PaymentChannelResult success(PaymentChannel channel, String transactionHex, String beefHex) {
        return new PaymentChannelResult(true, channel, transactionHex, beefHex, null);
    }

    public static PaymentChannelResult failure(String error) {
        return new PaymentChannelResult(false, null, null, null, error);
    }
}
