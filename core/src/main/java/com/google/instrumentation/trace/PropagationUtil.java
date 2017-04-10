/*
 * Copyright 2017, Google Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.instrumentation.trace;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.io.BaseEncoding;

/**
 * This is a helper class for {@link SpanContext} propagation on the wire.
 *
 * <p>Binary format:
 *
 * <ul>
 * <li>Binary value: &lt;version_id&gt;&lt;version_format&gt;
 * <li>version_id: 1-byte representing the version id.
 * <li>For version_id = 0:
 *     <ul>
 *     <li>version_format: &lt;field&gt;&lt;field&gt;...
 *     <li>field_format: &lt;field_id&gt;&lt;field_format&gt;
 *     <li>TraceId: (filed_id = 0, len = 16, default = “0000000000000000”) - 16-byte array
 *         representing the trace_id.
 *     <li>SpanId: (filed_id = 1, len = 8, default = “00000000”) - 8-byte array representing the
 *         span_id.
 *     <li>TraceOptions: (filed_id = 2, len = 4, default = “0”) - 1-byte array representing the
 *         trace_options. It is in little-endian order, if represented as an int.
 *     <li>Fields MUST be encoded using the filed id order (smaller to higher).
 *     <li>Valid value example:
 *         <ul>
 *         <li>{0, 0, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 1, 97, 98, 99,
 *             100, 101, 102, 103, 104, 2, 1}
 *         <li>version_id = 0;
 *         <li>trace_id = {64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79}
 *         <li>span_id = {97, 98, 99, 100, 101, 102, 103, 104};
 *         <li>trace_options = {1};
 *         </ul>
 *     </ul>
 *
 * </ul>
 *
 * <p>HTTP header format:
 *
 * <ul>
 * <li>Header name: Trace-Context
 * <li>Header value: base16(binary_format);
 * <li>All characters in the header value must be upper case and US-ASCII encoded.
 * <li>It is strongly encouraged to use this format when using HTTP as a RPC transport.
 * <li>Valid value example:
 *     <ul>
 *     <li>"0000404142434445464748494A4B4C4D4E4F0161626364656667680201"
 *     <li>version_id = 0;
 *     <li>trace_id = {64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79}
 *     <li>span_id = {97, 98, 99, 100, 101, 102, 103, 104};
 *     <li>trace_options = {1};
 *     </ul>
 *
 * </ul>
 *
 * <p>Example of usage on the client:
 *
 * <pre>{@code
 * private static final Tracer tracer = Tracer.getTracer();
 * void onSendRequest() {
 *   try (NonThrowingCloseable ss = tracer.spanBuilder("Sent.MyRequest")) {
 *     String headerName = PropagationUtil.HTTP_HEADER_NAME;
 *     String headerValue = PropagationUtil.toHttpHeaderValue(span.context());
 *     headers.add(headerName, headerValue);
 *     // Send the HTTP request and wait for the response.
 *   }
 * }
 * }</pre>
 *
 * <p>Example of usage on the server:
 *
 * <pre>{@code
 * private static final Tracer tracer = Tracer.getTracer();
 * void onRequestReceived() {
 *   String headerName = PropagationUtil.HTTP_HEADER_NAME;
 *   SpanContext spanContext = PropagationUtil.fromHttpHeaderValue(headers.find(headerName));
 *   try (NonThrowingCloseable ss =
 *            tracer.spanBuilderWithRemoteParent(spanContext, "Recv.MyRequest").startScopedSpan() {
 *     // Handle request and send response back.
 *   }
 * }
 * }</pre>
 */
public final class PropagationUtil {
  // Mask to extract a byte value.
  private static volatile Handler handler = DefaultHandler.INSTANCE;

  /** The header name that must be used in the HTTP request for the tracing context. */
  public static final String HTTP_HEADER_NAME = "Trace-Context";

  /**
   * Serializes a {@link SpanContext} using the HTTP standard format.
   *
   * @param spanContext the {@code SpanContext} to serialize.
   * @return the serialized US-ASCII encoded HTTP header value.
   * @throws NullPointerException if the {@code spanContext} is null.
   */
  public static String toHttpHeaderValue(SpanContext spanContext) {
    return BaseEncoding.base16().encode(toBinaryValue(spanContext));
  }

  /**
   * Parses the {@link SpanContext} from the HTTP standard format.
   *
   * @param input a US-ASCII encoded buffer of characters from which the {@code SpanContext} will be
   *     parsed.
   * @return the parsed {@code SpanContext}.
   * @throws NullPointerException if the {@code input} is null.
   * @throws IllegalArgumentException if the {@code input} is invalid.
   * @throws IllegalStateException if the version is not supported.
   */
  public static SpanContext fromHttpHeaderValue(CharSequence input) {
    return fromBinaryValue(BaseEncoding.base16().decode(input));
  }

  /**
   * Serializes a {@link SpanContext} using the binary format.
   *
   * @param spanContext the {@code SpanContext} to serialize.
   * @return the serialized binary value.
   * @throws NullPointerException if the {@code spanContext} is null.
   */
  public static byte[] toBinaryValue(SpanContext spanContext) {
    checkNotNull(spanContext, "spanContext");
    return handler.toBinaryFormat(spanContext);
  }

  /**
   * Parses the {@link SpanContext} from the binary format.
   *
   * @param bytes a binary encoded buffer from which the {@code SpanContext} will be parsed.
   * @return the parsed {@code SpanContext}.
   * @throws NullPointerException if the {@code input} is null.
   * @throws IllegalArgumentException if the {@code input} is invalid.
   * @throws IllegalStateException if the version is not supported.
   */
  public static SpanContext fromBinaryValue(byte[] bytes) {
    checkNotNull(bytes, "bytes");
    return handler.fromBinaryFormat(bytes);
  }

  /**
   * Sets a new {@link Handler}.
   *
   * @param newHandler the new {@code Handler} to be set.
   */
  static void setHandler(Handler newHandler) {
    handler = newHandler;
  }

  /** Abstract class that allows implementation of the new version format support. */
  public abstract static class Handler {
    /**
     * Serializes a {@link SpanContext} using the binary format.
     *
     * @param spanContext the {@code SpanContext} to serialize.
     * @return the serialized binary value.
     */
    public abstract byte[] toBinaryFormat(SpanContext spanContext);

    /**
     * Parses the {@link SpanContext} from the binary format.
     *
     * @param bytes a binary encoded buffer from which the {@code SpanContext} will be parsed.
     * @return the parsed {@code SpanContext}.
     */
    public abstract SpanContext fromBinaryFormat(byte[] bytes);
  }

  /** Version 0 implementation of the {@code VersionHandler}. */
  public static final class DefaultHandler extends Handler {
    /** Singleton instance of this class. */
    public static final DefaultHandler INSTANCE = new DefaultHandler();

    private static final byte VERSION_ID = 0;
    private static final int VERSION_ID_OFFSET = 0;
    // The version_id/field_id size in bytes.
    private static final byte ID_SIZE = 1;
    private static final byte TRACE_ID_FIELD_ID = 0;
    private static final int TRACE_ID_FIELD_ID_OFFSET = VERSION_ID_OFFSET + ID_SIZE;
    private static final int TRACE_ID_OFFSET = TRACE_ID_FIELD_ID_OFFSET + ID_SIZE;
    private static final byte SPAN_ID_FIELD_ID = 1;
    private static final int SPAN_ID_FIELD_ID_OFFSET = TRACE_ID_OFFSET + TraceId.SIZE;
    private static final int SPAN_ID_OFFSET = SPAN_ID_FIELD_ID_OFFSET + ID_SIZE;
    private static final byte TRACE_OPTION_FIELD_ID = 2;
    private static final int TRACE_OPTION_FIELD_ID_OFFSET = SPAN_ID_OFFSET + SpanId.SIZE;
    private static final int TRACE_OPTIONS_OFFSET = TRACE_OPTION_FIELD_ID_OFFSET + ID_SIZE;
    private static final int FORMAT_LENGTH =
        4 * ID_SIZE + TraceId.SIZE + SpanId.SIZE + TraceOptions.SIZE;

    @Override
    public byte[] toBinaryFormat(SpanContext spanContext) {
      checkNotNull(spanContext, "spanContext");
      byte[] bytes = new byte[FORMAT_LENGTH];
      bytes[VERSION_ID_OFFSET] = VERSION_ID;
      bytes[TRACE_ID_FIELD_ID_OFFSET] = TRACE_ID_FIELD_ID;
      spanContext.getTraceId().copyBytesTo(bytes, TRACE_ID_OFFSET);
      bytes[SPAN_ID_FIELD_ID_OFFSET] = SPAN_ID_FIELD_ID;
      spanContext.getSpanId().copyBytesTo(bytes, SPAN_ID_OFFSET);
      bytes[TRACE_OPTION_FIELD_ID_OFFSET] = TRACE_OPTION_FIELD_ID;
      spanContext.getTraceOptions().copyBytesTo(bytes, TRACE_OPTIONS_OFFSET);
      return bytes;
    }

    @Override
    public SpanContext fromBinaryFormat(byte[] bytes) {
      checkNotNull(bytes, "bytes");
      checkArgument(bytes.length > 0 && bytes[0] == VERSION_ID, "Unsupported version.");
      TraceId traceId = TraceId.INVALID;
      SpanId spanId = SpanId.INVALID;
      TraceOptions traceOptions = TraceOptions.DEFAULT;
      int pos = 1;
      if (bytes.length > pos && bytes[pos] == TRACE_ID_FIELD_ID) {
        traceId = TraceId.fromBytes(bytes, pos + ID_SIZE);
        pos += ID_SIZE + TraceId.SIZE;
      }
      if (bytes.length > pos && bytes[pos] == SPAN_ID_FIELD_ID) {
        spanId = SpanId.fromBytes(bytes, pos + ID_SIZE);
        pos += ID_SIZE + SpanId.SIZE;
      }
      if (bytes.length > pos && bytes[pos] == TRACE_OPTION_FIELD_ID) {
        traceOptions = TraceOptions.fromBytes(bytes, pos + ID_SIZE);
      }
      return new SpanContext(traceId, spanId, traceOptions);
    }

    private DefaultHandler() {}
  }

  // Disallow instances of this class.
  private PropagationUtil() {}
}
