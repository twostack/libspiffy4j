package org.twostack.libspiffy4j.aggregate.invoice;

public sealed interface InvoiceReply permits InvoiceReply.Success, InvoiceReply.Failure {

    record Success(InvoiceState state) implements InvoiceReply {}

    record Failure(String reason) implements InvoiceReply {}
}
